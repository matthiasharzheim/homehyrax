<?php
// HomeHyrax-Schluesseldienst: verwahrt je Gast-Einladung einen Zufallsschluessel und gibt ihn nur an das
// gebundene Geraet heraus, solange der Besitzer die Einladung nicht gesperrt hat. Kennt keine VPN-Daten.
// Protokoll und Betrieb: keyserver/README.md
declare(strict_types=1);

namespace HHKeys;

const API = 1;
const PROTO = 'HH1';
const CLOCK_SKEW = 300;          // erlaubte Abweichung der Handy-Uhr in Sekunden
const INTEGRITY_CACHE = 86400;   // Play-Integrity-Ergebnis gilt je Einladung (= gebundenes Geraet) 24 h
const PURGE_AFTER = 30 * 86400;  // abgelaufene Einladungen werden 30 Tage nach Ablauf geloescht
const HOUSEKEEPING_EVERY = 600;
const UNOPENED_MAX = 180 * 86400;
const IDLE_MAX = 365 * 86400;    // geoeffnete Einladungen, die so lange nicht genutzt wurden, werden geloescht
const MAX_BODY = 16384;

final class Fail extends \Exception
{
    public function __construct(public readonly int $status, public readonly string $error,
                                public readonly array $extra = [])
    {
        parent::__construct($error);
    }
}

final class Service
{
    // Anfragen je Adresse (IPv6: je /64) und Stunde; create zusaetzlich je Tag, integrity = Aufrufe bei Google
    private const LIMITS = ['create' => 30, 'bind' => 30, 'key' => 1200, 'manage' => 600, 'info' => 600,
                            'integrity' => 60];
    private const CREATE_PER_DAY = 100;

    /** @var \Closure(): int */
    public \Closure $clock;
    private string $ip = '';

    /**
     * @param array $cfg siehe config.example.php
     * @param null|\Closure(string $token, string $requestHash): ?string $integrity null = Pruefung aus;
     *        liefert null bei Erfolg, sonst einen Fehlertext
     */
    public function __construct(private \PDO $db, private array $cfg, private ?\Closure $integrity)
    {
        $this->clock = static fn(): int => time();
        $this->db->setAttribute(\PDO::ATTR_ERRMODE, \PDO::ERRMODE_EXCEPTION);
        $this->db->exec('PRAGMA busy_timeout = 5000');
        $this->db->exec('PRAGMA journal_mode = WAL');
        $this->db->exec('CREATE TABLE IF NOT EXISTS invites (
            id TEXT PRIMARY KEY, owner TEXT NOT NULL, key_enc TEXT NOT NULL, bind_hash TEXT,
            pubkey TEXT, status TEXT NOT NULL DEFAULT \'active\', created INTEGER NOT NULL,
            expires INTEGER, bound_at INTEGER, last_used INTEGER, integrity_at INTEGER)');
        $this->db->exec('CREATE INDEX IF NOT EXISTS invites_owner ON invites(owner)');
        $this->db->exec('CREATE TABLE IF NOT EXISTS nonces (n TEXT PRIMARY KEY, ts INTEGER NOT NULL)');
        $this->db->exec('CREATE TABLE IF NOT EXISTS hits (k TEXT PRIMARY KEY, win INTEGER NOT NULL, n INTEGER NOT NULL)');
        $this->db->exec('CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)');
        if (strlen($this->masterKey()) !== SODIUM_CRYPTO_SECRETBOX_KEYBYTES) {
            throw new \RuntimeException('master_key must be 32 bytes (base64)');
        }
    }

    /** @return array{0:int,1:array} HTTP-Status und JSON-Antwort */
    public function handle(string $method, string $path, string $body, string $ip): array
    {
        try {
            $path = '/' . trim($path, '/');
            if ($path === '/v1/health' && ($method === 'GET' || $method === 'HEAD')) {
                return [200, ['ok' => true, 'api' => API, 'integrity' => $this->integrity !== null]];
            }
            $action = match ($path) {
                '/v1/create' => 'create', '/v1/bind' => 'bind', '/v1/key' => 'key',
                '/v1/manage' => 'manage', '/v1/info' => 'info', default => null,
            };
            if ($action === null) throw new Fail(404, 'not_found');
            if ($method !== 'POST') throw new Fail(405, 'method');
            if (strlen($body) > MAX_BODY) throw new Fail(413, 'too_large');
            $in = json_decode($body, true, 8);
            if (!is_array($in) || array_is_list($in) && $in !== []) throw new Fail(400, 'bad_json');
            $this->ip = $ip;
            $this->limit($action, self::LIMITS[$action], 3600);
            $this->housekeepingDue();
            return [200, $this->$action($in)];
        } catch (Fail $f) {
            return [$f->status, ['error' => $f->error] + $f->extra];
        }
    }

    // ---- Endpunkte ----

    /** Besitzer legt eine Einladung an; der Schluessel verlaesst den Server nur hier und beim gebundenen Geraet */
    private function create(array $in): array
    {
        $owner = $this->ownerHash($in);
        [$ts, $nonce] = $this->fresh($in);
        $expires = $this->expiresField($in);
        // Tagesgrenze je Adresse: Besitzer-Kennungen sind frei waehlbar, die Grenze je Besitzer allein reicht nicht
        $this->limit('create-day', self::CREATE_PER_DAY, 86400);
        $this->checkIntegrity($in, "create|$owner|$ts|$nonce");
        $this->useNonce("o:$owner", $nonce);

        $id = b64u(random_bytes(16));
        $key = random_bytes(32);
        $bind = random_bytes(32);
        $this->db->exec('BEGIN IMMEDIATE');
        try {
            $st = $this->db->prepare('SELECT COUNT(*) FROM invites WHERE owner = ?');
            $st->execute([$owner]);
            if ((int)$st->fetchColumn() >= (int)($this->cfg['max_invites_per_owner'] ?? 200)) {
                throw new Fail(429, 'owner_limit');
            }
            if ((int)$this->db->query('SELECT COUNT(*) FROM invites')->fetchColumn()
                >= (int)($this->cfg['max_invites_total'] ?? 100000)) {
                throw new Fail(503, 'full');
            }
            $this->db->prepare('INSERT INTO invites (id, owner, key_enc, bind_hash, created, expires)
                                VALUES (?, ?, ?, ?, ?, ?)')
                ->execute([$id, $owner, $this->seal($key), hash('sha256', $bind), $this->now(), $expires]);
            $this->db->exec('COMMIT');
        } catch (\Throwable $e) {
            $this->db->exec('ROLLBACK');
            throw $e;
        }
        return ['id' => $id, 'key' => b64u($key), 'bind' => b64u($bind), 'expires' => $expires];
    }

    /** Gast-App bindet die Einladung beim ersten Oeffnen an ihren Geraeteschluessel (einmalig) */
    private function bind(array $in): array
    {
        $id = $this->idField($in);
        $bind = $this->bytesField($in, 'bind', 32, 32);
        $pubDer = $this->bytesField($in, 'pub', 91, 91);
        [$ts, $nonce] = $this->fresh($in);
        $inv = $this->invite($id);
        // erst das Geheimnis aus der Datei pruefen, dann den Zustand verraten
        if (!hash_equals((string)$inv['bind_hash'], hash('sha256', $bind))) throw new Fail(403, 'denied');
        if ($inv['pubkey'] !== null) {
            // Datei wurde schon auf einem anderen Geraet geoeffnet (oder dieses Geraet bindet erneut)
            $same = hash_equals($inv['pubkey'], base64_encode($pubDer));
            throw new Fail(409, $same ? 'bound_here' : 'already_bound');
        }
        $msg = "bind|$id|$ts|$nonce";
        $this->verifySig(ecKey($pubDer), $msg, $in);
        $this->checkIntegrity($in, $msg);
        $this->useNonce("i:$id", $nonce);
        $this->checkUsable($inv);
        $st = $this->db->prepare('UPDATE invites SET pubkey = ?, bound_at = ?, integrity_at = ?
                                  WHERE id = ? AND pubkey IS NULL');
        $st->execute([base64_encode($pubDer), $this->now(), $this->integrity ? $this->now() : null, $id]);
        if ($st->rowCount() !== 1) throw new Fail(409, 'already_bound');
        return ['ok' => true, 'device' => fingerprint($pubDer),
                'expires' => $inv['expires'] === null ? null : (int)$inv['expires']];
    }

    /** Gast-App holt bei jedem Tipp den Schluessel (nur im Arbeitsspeicher verwendet) */
    private function key(array $in): array
    {
        $id = $this->idField($in);
        [$ts, $nonce] = $this->fresh($in);
        $inv = $this->invite($id);
        if ($inv['pubkey'] === null) throw new Fail(403, 'not_bound');
        $msg = "key|$id|$ts|$nonce";
        $this->verifySig(ecKey(base64_decode($inv['pubkey'], true)), $msg, $in);
        $cached = $inv['integrity_at'] !== null && $this->now() - (int)$inv['integrity_at'] < INTEGRITY_CACHE;
        if (!$cached) $this->checkIntegrity($in, $msg);
        $this->useNonce("i:$id", $nonce);
        $this->checkUsable($inv);
        $this->db->prepare('UPDATE invites SET last_used = ?, integrity_at = ? WHERE id = ?')
            ->execute([$this->now(), $this->integrity && !$cached ? $this->now() : $inv['integrity_at'], $id]);
        return ['key' => b64u($this->open($inv['key_enc'])),
                'expires' => $inv['expires'] === null ? null : (int)$inv['expires']];
    }

    /** Besitzer sperrt, gibt frei, aendert das Ablaufdatum oder loescht eine Einladung */
    private function manage(array $in): array
    {
        $owner = $this->ownerHash($in);
        $id = $this->idField($in);
        $inv = $this->invite($id);
        if (!hash_equals($inv['owner'], $owner)) throw new Fail(404, 'unknown');
        $action = is_string($in['action'] ?? null) ? $in['action'] : '';
        match ($action) {
            'block' => $this->db->prepare("UPDATE invites SET status = 'blocked' WHERE id = ?")->execute([$id]),
            'activate' => $this->db->prepare("UPDATE invites SET status = 'active' WHERE id = ?")->execute([$id]),
            'expires' => $this->db->prepare('UPDATE invites SET expires = ? WHERE id = ?')
                ->execute([$this->expiresField($in), $id]),
            'delete' => $this->db->prepare('DELETE FROM invites WHERE id = ?')->execute([$id]),
            default => throw new Fail(400, 'action'),
        };
        return ['ok' => true];
    }

    /** Besitzer sieht Zustand aller eigenen Einladungen (Namen der Gaeste kennt nur seine App) */
    private function info(array $in): array
    {
        $st = $this->db->prepare('SELECT id, status, created, expires, bound_at, last_used, pubkey FROM invites
                                  WHERE owner = ? ORDER BY created, id');
        $st->execute([$this->ownerHash($in)]);
        $out = [];
        foreach ($st->fetchAll(\PDO::FETCH_ASSOC) as $r) {
            $out[] = ['id' => $r['id'], 'status' => $this->state($r), 'bound' => $r['bound_at'] !== null,
                      'created' => (int)$r['created'], 'expires' => self::intOrNull($r['expires']),
                      'bound_at' => self::intOrNull($r['bound_at']), 'last_used' => self::intOrNull($r['last_used']),
                      // kurzer Fingerabdruck des Geraeteschluessels – zeigt auch die Gast-App, zum Vergleich
                      'device' => $r['pubkey'] === null ? null : fingerprint(base64_decode($r['pubkey'], true))];
        }
        return ['invites' => $out, 'now' => $this->now()];
    }

    // ---- Pruefungen ----

    private function now(): int
    {
        return ($this->clock)();
    }

    private function invite(string $id): array
    {
        $st = $this->db->prepare('SELECT * FROM invites WHERE id = ?');
        $st->execute([$id]);
        $r = $st->fetch(\PDO::FETCH_ASSOC);
        if (!$r) throw new Fail(404, 'unknown');
        return $r;
    }

    private function state(array $inv): string
    {
        if ($inv['status'] === 'blocked') return 'blocked';
        if ($inv['expires'] !== null && (int)$inv['expires'] <= $this->now()) return 'expired';
        return 'active';
    }

    private function checkUsable(array $inv): void
    {
        $s = $this->state($inv);
        if ($s !== 'active') throw new Fail(403, $s);
    }

    private function ownerHash(array $in): string
    {
        // Besitzer-Kennung = 32 Zufallsbytes aus der App des Besitzers; gespeichert wird nur der Hash
        return hash('sha256', $this->bytesField($in, 'owner', 32, 32));
    }

    private function idField(array $in): string
    {
        $id = $in['id'] ?? null;
        if (!is_string($id) || !preg_match('/^[A-Za-z0-9_-]{22}$/', $id)) throw new Fail(400, 'id');
        return $id;
    }

    private function bytesField(array $in, string $name, int $min, int $max): string
    {
        $v = $in[$name] ?? null;
        $raw = is_string($v) ? unb64u($v) : null;
        if ($raw === null || strlen($raw) < $min || strlen($raw) > $max) throw new Fail(400, $name);
        return $raw;
    }

    private function expiresField(array $in): ?int
    {
        $e = $in['expires'] ?? null;
        if ($e === null) return null;
        if (!is_int($e) || $e <= $this->now() || $e > $this->now() + 5 * 366 * 86400) throw new Fail(400, 'expires');
        return $e;
    }

    /** @return array{0:int,1:string} */
    private function fresh(array $in): array
    {
        $ts = $in['ts'] ?? null;
        $nonce = $in['nonce'] ?? null;
        if (!is_int($ts)) throw new Fail(400, 'ts');
        if (!is_string($nonce) || !preg_match('/^[A-Za-z0-9_-]{16,64}$/', $nonce)) throw new Fail(400, 'nonce');
        if (abs($ts - $this->now()) > CLOCK_SKEW) throw new Fail(400, 'clock', ['now' => $this->now()]);
        return [$ts, $nonce];
    }

    private function useNonce(string $scope, string $nonce): void
    {
        try {
            $this->db->prepare('INSERT INTO nonces (n, ts) VALUES (?, ?)')
                ->execute([hash('sha256', "$scope|$nonce"), $this->now()]);
        } catch (\PDOException) {
            throw new Fail(409, 'replay');
        }
    }

    private function verifySig(\OpenSSLAsymmetricKey $key, string $msg, array $in): void
    {
        $sig = $this->bytesField($in, 'sig', 8, 80);
        if (openssl_verify(PROTO . '|' . $msg, $sig, $key, OPENSSL_ALGO_SHA256) !== 1) {
            throw new Fail(403, 'bad_signature');
        }
    }

    private function checkIntegrity(array $in, string $msg): void
    {
        if ($this->integrity === null) return;
        $token = $in['integrity'] ?? null;
        if (!is_string($token) || $token === '') throw new Fail(401, 'integrity_required');
        // offensichtlicher Unsinn kostet keinen Aufruf bei Google; Google-Aufrufe je Adresse begrenzt (Tageskontingent)
        if (strlen($token) < 100 || strlen($token) > 8192 || !preg_match('/^[A-Za-z0-9_.=-]+$/', $token)) {
            throw new Fail(403, 'integrity', ['reason' => 'token']);
        }
        $this->limit('integrity', self::LIMITS['integrity'], 3600);
        $err = ($this->integrity)($token, requestHash($msg));
        if ($err !== null) throw new Fail(403, 'integrity', ['reason' => $err]);
    }

    /** Zaehlt Anfragen je Adresse und Zeitfenster; gespeichert wird nur ein gesalzener Hash, bis Fensterende */
    private function limit(string $bucket, int $max, int $period): void
    {
        $end = (intdiv($this->now(), $period) + 1) * $period;
        $k = hash('sha256', ($this->cfg['ip_salt'] ?? '') . '|' . ipBucket($this->ip) . "|$bucket|$end");
        $this->db->prepare('INSERT INTO hits (k, win, n) VALUES (?, ?, 1)
                            ON CONFLICT(k) DO UPDATE SET n = n + 1')->execute([$k, $end]);
        $st = $this->db->prepare('SELECT n FROM hits WHERE k = ?');
        $st->execute([$k]);
        if ((int)$st->fetchColumn() > $max) throw new Fail(429, 'rate_limit');
    }

    /** Aufraeumen hoechstens alle 10 min, angestossen von der naechsten Anfrage */
    private function housekeepingDue(): void
    {
        $last = (int)$this->db->query("SELECT v FROM meta WHERE k = 'housekeeping'")->fetchColumn();
        if ($this->now() - $last < HOUSEKEEPING_EVERY) return;
        $this->db->prepare("INSERT OR REPLACE INTO meta (k, v) VALUES ('housekeeping', ?)")->execute([$this->now()]);
        $this->housekeeping();
    }

    public function housekeeping(): void
    {
        $now = $this->now();
        $this->db->prepare('DELETE FROM nonces WHERE ts < ?')->execute([$now - 2 * CLOCK_SKEW]);
        $this->db->prepare('DELETE FROM hits WHERE win <= ?')->execute([$now]);
        $this->db->prepare('DELETE FROM invites WHERE expires IS NOT NULL AND expires < ?')
            ->execute([$now - PURGE_AFTER]);
        // nie geoeffnete Einladungen verfallen (sonst liessen sich Plaetze dauerhaft belegen): ohne Ablaufdatum nach
        // 30 Tagen, mit Ablaufdatum spaetestens nach 180 Tagen (Einladung fuer einen spaeteren Urlaub bleibt gueltig)
        $this->db->prepare('DELETE FROM invites WHERE pubkey IS NULL AND (created < ? OR expires IS NULL AND created < ?)')
            ->execute([$now - UNOPENED_MAX, $now - PURGE_AFTER]);
        // geoeffnet, aber 12 Monate nicht genutzt (auch gesperrte): nichts bleibt unbegrenzt liegen.
        // CAST: COALESCE hat keine Spalten-Affinitaet, sonst vergleicht SQLite Zahl < Text (immer wahr = alles weg)
        $this->db->prepare('DELETE FROM invites WHERE pubkey IS NOT NULL AND COALESCE(last_used, bound_at) < CAST(? AS INTEGER)')
            ->execute([$now - IDLE_MAX]);
    }

    // ---- Verschluesselung der gespeicherten Schluessel ----

    private function masterKey(): string
    {
        return base64_decode((string)($this->cfg['master_key'] ?? ''), true) ?: '';
    }

    private function seal(string $plain): string
    {
        $n = random_bytes(SODIUM_CRYPTO_SECRETBOX_NONCEBYTES);
        return base64_encode($n . sodium_crypto_secretbox($plain, $n, $this->masterKey()));
    }

    private function open(string $sealed): string
    {
        $raw = base64_decode($sealed, true);
        $n = substr($raw, 0, SODIUM_CRYPTO_SECRETBOX_NONCEBYTES);
        $plain = sodium_crypto_secretbox_open(substr($raw, SODIUM_CRYPTO_SECRETBOX_NONCEBYTES), $n, $this->masterKey());
        if ($plain === false) throw new \RuntimeException('stored key cannot be decrypted (master_key changed?)');
        return $plain;
    }

    private static function intOrNull(mixed $v): ?int
    {
        return $v === null ? null : (int)$v;
    }
}

// ---- Hilfsfunktionen ----

function b64u(string $raw): string
{
    return rtrim(strtr(base64_encode($raw), '+/', '-_'), '=');
}

function unb64u(string $s): ?string
{
    if ($s === '' || strlen($s) > 4096 || !preg_match('/^[A-Za-z0-9_-]+$/', $s)) return null;
    $raw = base64_decode(strtr($s, '-_', '+/'), true);
    return $raw === false ? null : $raw;
}

/** requestHash fuer Play Integrity: base64url(SHA-256("HH1|<nachricht>")) */
function requestHash(string $msg): string
{
    return b64u(hash('sha256', PROTO . '|' . $msg, true));
}

/** Adresse fuer die Anfragebremse: IPv4 ganz, IPv6 je /64 (ein Anschluss hat meist ein ganzes /64) */
function ipBucket(string $ip): string
{
    $bin = @inet_pton($ip);
    if ($bin === false || strlen($bin) !== 16) return $ip;
    if (str_starts_with($bin, "\0\0\0\0\0\0\0\0\0\0\xff\xff")) return inet_ntop(substr($bin, 12));
    return bin2hex(substr($bin, 0, 8)) . '::/64';
}

/** Kurzer Fingerabdruck eines Geraeteschluessels (8 Hex-Zeichen), damit Besitzer und Gast vergleichen koennen */
function fingerprint(string $der): string
{
    return substr(hash('sha256', $der), 0, 8);
}

/** Oeffentlicher Geraeteschluessel (X.509 SubjectPublicKeyInfo, DER) – nur EC P-256 */
function ecKey(string $der): \OpenSSLAsymmetricKey
{
    $pem = "-----BEGIN PUBLIC KEY-----\n" . chunk_split(base64_encode($der), 64, "\n") . "-----END PUBLIC KEY-----\n";
    $k = openssl_pkey_get_public($pem);
    if ($k === false) throw new Fail(400, 'pub');
    $d = openssl_pkey_get_details($k);
    if (($d['type'] ?? -1) !== OPENSSL_KEYTYPE_EC || ($d['ec']['curve_name'] ?? '') !== 'prime256v1') {
        throw new Fail(400, 'pub');
    }
    return $k;
}

/** Einstieg fuer den Webserver (public/index.php) */
function serve(string $home): void
{
    umask(077);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    header('X-Content-Type-Options: nosniff');
    header('Referrer-Policy: no-referrer');
    try {
        $cfg = require $home . '/config.php';
        $db = new \PDO('sqlite:' . $cfg['db']);
        $integrity = null;
        if (($cfg['integrity'] ?? 'off') === 'on') {
            require_once __DIR__ . '/integrity.php';
            $integrity = \Closure::fromCallable(new PlayIntegrity($cfg, $db));
        }
        $svc = new Service($db, $cfg, $integrity);
        $base = rtrim(str_replace('\\', '/', dirname($_SERVER['SCRIPT_NAME'] ?? '/')), '/');
        $path = (string)parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH);
        if ($base !== '' && str_starts_with($path, $base)) $path = substr($path, strlen($base));
        $body = (string)file_get_contents('php://input', false, null, 0, MAX_BODY + 1);
        [$status, $out] = $svc->handle($_SERVER['REQUEST_METHOD'] ?? 'GET', $path, $body,
                                       (string)($_SERVER['REMOTE_ADDR'] ?? ''));
    } catch (\Throwable $e) {
        // keine Anfragedaten ins Protokoll, nur Ort und Art des Fehlers; ueber 1 MB wird neu begonnen
        $log = $home . '/data/error.log';
        @file_put_contents($log, gmdate('c') . ' ' . get_class($e) . ': ' . $e->getMessage()
            . ' @' . basename($e->getFile()) . ':' . $e->getLine() . "\n", @filesize($log) > 1048576 ? 0 : FILE_APPEND);
        [$status, $out] = [500, ['error' => 'server']];
    }
    http_response_code($status);
    echo json_encode($out, JSON_UNESCAPED_SLASHES);
}
