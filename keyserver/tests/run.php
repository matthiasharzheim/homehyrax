<?php
// Tests des Schluesseldienstes mit SQLite im Arbeitsspeicher und nachgebauter Play-Integrity-Pruefung.
// Aufruf: php keyserver/tests/run.php   (Ende mit "OK n Pruefungen", sonst Exit-Code 1)
declare(strict_types=1);

require __DIR__ . '/../src/server.php';

use HHKeys\Service;
use function HHKeys\{b64u, requestHash};

$checks = 0;
$failed = 0;
function check(string $name, bool $ok, mixed $got = null): void
{
    global $checks, $failed;
    $checks++;
    if (!$ok) {
        $failed++;
        echo "FEHLER: $name\n  bekommen: " . json_encode($got, JSON_UNESCAPED_SLASHES) . "\n";
    }
}

/** Nachgebautes Integrity-Token, das nur zur Anfrage mit diesem requestHash passt (Form wie ein echtes JWE) */
function tok(string $hash): string
{
    return 'ok.' . $hash . '.' . str_repeat('x', 100);
}

final class T
{
    public Service $svc;
    public int $now = 1_800_000_000;
    public string $ip = '198.51.100.7';

    public function __construct(bool $integrity = false, array $cfg = [])
    {
        $cfg += ['master_key' => base64_encode(random_bytes(32)), 'ip_salt' => 'x'];
        $stub = $integrity
            ? static fn(string $token, string $hash): ?string => $token === tok($hash) ? null : 'request'
            : null;
        $this->svc = new Service(new PDO('sqlite::memory:'), $cfg, $stub ? Closure::fromCallable($stub) : null);
        $this->svc->clock = fn(): int => $this->now;
    }

    public function call(string $path, array $in, string $method = 'POST'): array
    {
        return $this->svc->handle($method, $path, json_encode($in), $this->ip);
    }

    public static function nonce(): string
    {
        return b64u(random_bytes(16));
    }
}

final class Device
{
    public OpenSSLAsymmetricKey $priv;
    public string $pub;

    public function __construct()
    {
        $this->priv = openssl_pkey_new(['private_key_type' => OPENSSL_KEYTYPE_EC, 'curve_name' => 'prime256v1']);
        $pem = openssl_pkey_get_details($this->priv)['key'];
        $this->pub = base64_decode(preg_replace('/-----[^-]+-----|\s/', '', $pem));
    }

    public function sign(string $msg): string
    {
        openssl_sign("HH1|$msg", $sig, $this->priv, OPENSSL_ALGO_SHA256);
        return b64u($sig);
    }

    /** Anfrage an /v1/key oder /v1/bind wie die App */
    public function req(T $t, string $action, string $id, array $extra = [], ?string $nonce = null, ?int $ts = null): array
    {
        $ts ??= $t->now;
        $nonce ??= T::nonce();
        $in = ['id' => $id, 'ts' => $ts, 'nonce' => $nonce, 'sig' => $this->sign("$action|$id|$ts|$nonce")] + $extra;
        if (($extra['integrity'] ?? null) === 'auto') $in["integrity"] = tok(requestHash("$action|$id|$ts|$nonce"));
        return $t->call("/v1/$action", $in);
    }
}

function create(T $t, string $owner, array $extra = []): array
{
    return $t->call('/v1/create', ['owner' => $owner, 'ts' => $t->now, 'nonce' => T::nonce()] + $extra);
}

$owner = b64u(random_bytes(32));
$other = b64u(random_bytes(32));

// --- Grundablauf: anlegen, binden, Schluessel holen, sperren, freigeben, loeschen ---
$t = new T();
[$s, $r] = $t->call('/v1/health', [], 'GET');
check('health', $s === 200 && $r['ok'] === true && $r['integrity'] === false, $r);
[$s, $inv] = create($t, $owner);
check('create', $s === 200 && strlen($inv['id']) === 22 && strlen($inv['key']) === 43 && strlen($inv['bind']) === 43
    && $inv['expires'] === null, $inv);
$dev = new Device();
[$s, $r] = $dev->req($t, 'key', $inv['id']);
check('key vor bind', $s === 403 && $r['error'] === 'not_bound', $r);
[$s, $r] = $dev->req($t, 'bind', $inv['id'], ['bind' => $inv['bind'], 'pub' => b64u($dev->pub)]);
check('bind', $s === 200 && $r['ok'] === true && strlen($r['device']) === 8, $r);
$fp = $r['device'];
[$s, $r] = $dev->req($t, 'bind', $inv['id'], ['bind' => $inv['bind'], 'pub' => b64u($dev->pub)]);
check('erneutes bind desselben Geraets', $s === 409 && $r['error'] === 'bound_here', $r);
[$s, $r] = $dev->req($t, 'bind', $inv['id'], ['bind' => b64u(random_bytes(32)), 'pub' => b64u($dev->pub)]);
check('Zustand nur mit Geheimnis', $s === 403 && $r['error'] === 'denied', $r);
[$s, $r] = $dev->req($t, 'key', $inv['id']);
check('key', $s === 200 && $r['key'] === $inv['key'], $r);

$thief = new Device();
[$s, $r] = $thief->req($t, 'bind', $inv['id'], ['bind' => $inv['bind'], 'pub' => b64u($thief->pub)]);
check('zweites bind (weitergeleitete Datei)', $s === 409 && $r['error'] === 'already_bound', $r);
[$s, $r] = $thief->req($t, 'key', $inv['id']);
check('fremdes Geraet bekommt keinen Schluessel', $s === 403 && $r['error'] === 'bad_signature', $r);

$nonce = T::nonce();
$dev->req($t, 'key', $inv['id'], [], $nonce);
[$s, $r] = $dev->req($t, 'key', $inv['id'], [], $nonce);
check('Wiederholung derselben Anfrage', $s === 409 && $r['error'] === 'replay', $r);
[$s, $r] = $dev->req($t, 'key', $inv['id'], [], null, $t->now - 301);
check('alte Uhrzeit', $s === 400 && $r['error'] === 'clock' && $r['now'] === $t->now, $r);

[$s, $r] = $t->call('/v1/manage', ['owner' => $other, 'id' => $inv['id'], 'action' => 'block']);
check('fremder Besitzer kann nicht sperren', $s === 404 && $r['error'] === 'unknown', $r);
[$s, $r] = $t->call('/v1/manage', ['owner' => $owner, 'id' => $inv['id'], 'action' => 'block']);
check('sperren', $s === 200, $r);
[$s, $r] = $dev->req($t, 'key', $inv['id']);
check('gesperrt -> kein Schluessel', $s === 403 && $r['error'] === 'blocked' && !isset($r['key']), $r);
[$s, $r] = $t->call('/v1/info', ['owner' => $owner]);
check('info zeigt gesperrt', $s === 200 && count($r['invites']) === 1 && $r['invites'][0]['status'] === 'blocked'
    && $r['invites'][0]['bound'] === true && is_int($r['invites'][0]['last_used'])
    && $r['invites'][0]['device'] === $fp, $r);
[$s, $r] = $t->call('/v1/info', ['owner' => $other]);
check('info fremder Besitzer leer', $s === 200 && $r['invites'] === [], $r);
$t->call('/v1/manage', ['owner' => $owner, 'id' => $inv['id'], 'action' => 'activate']);
[$s, $r] = $dev->req($t, 'key', $inv['id']);
check('wieder freigegeben', $s === 200 && $r['key'] === $inv['key'], $r);
[$s, $r] = $t->call('/v1/manage', ['owner' => $owner, 'id' => $inv['id'], 'action' => 'delete']);
check('loeschen', $s === 200, $r);
[$s, $r] = $dev->req($t, 'key', $inv['id']);
check('geloescht -> unbekannt', $s === 404 && $r['error'] === 'unknown', $r);

// --- Ablaufdatum ---
[$s, $inv] = create($t, $owner, ['expires' => $t->now + 3600]);
check('create mit Ablauf', $s === 200 && $inv['expires'] === $t->now + 3600, $inv);
$dev = new Device();
$dev->req($t, 'bind', $inv['id'], ['bind' => $inv['bind'], 'pub' => b64u($dev->pub)]);
[$s, $r] = $dev->req($t, 'key', $inv['id']);
check('vor Ablauf', $s === 200 && $r['expires'] === $t->now + 3600, $r);
$t->now += 3600;
[$s, $r] = $dev->req($t, 'key', $inv['id']);
check('nach Ablauf', $s === 403 && $r['error'] === 'expired', $r);
[$s, $r] = $t->call('/v1/manage', ['owner' => $owner, 'id' => $inv['id'], 'action' => 'expires', 'expires' => $t->now + 60]);
check('Ablauf verlaengern', $s === 200, $r);
[$s, $r] = $dev->req($t, 'key', $inv['id']);
check('nach Verlaengerung', $s === 200, $r);
$t->call('/v1/manage', ['owner' => $owner, 'id' => $inv['id'], 'action' => 'expires', 'expires' => null]);
$t->now += 86400 * 400;
[$s, $r] = $dev->req($t, 'key', $inv['id']);
check('Ablauf entfernt', $s === 200, $r);
[$s, $r] = create($t, $owner, ['expires' => $t->now - 1]);
check('Ablauf in der Vergangenheit abgelehnt', $s === 400 && $r['error'] === 'expires', $r);
[$s, $r] = create($t, $owner, ['expires' => '2030-01-01']);
check('Ablauf als Text abgelehnt', $s === 400 && $r['error'] === 'expires', $r);

// Aufraeumen: 30 Tage nach Ablauf geloescht
[$s, $inv2] = create($t, $owner, ['expires' => $t->now + 10]);
$t->now += 10 + 30 * 86400 + 1;
$t->svc->housekeeping();
[$s, $r] = $t->call('/v1/info', ['owner' => $owner]);
check('abgelaufene Einladung nach 30 Tagen weg', !in_array($inv2['id'], array_column($r['invites'], 'id'), true), $r);

// --- Binden: falsches Geheimnis, falscher Schluesseltyp, falsche Signatur ---
[$s, $inv] = create($t, $owner);
$dev = new Device();
[$s, $r] = $dev->req($t, 'bind', $inv['id'], ['bind' => b64u(random_bytes(32)), 'pub' => b64u($dev->pub)]);
check('bind mit falschem Geheimnis', $s === 403 && $r['error'] === 'denied', $r);
$rsa = openssl_pkey_new(['private_key_type' => OPENSSL_KEYTYPE_RSA, 'private_key_bits' => 2048]);
$rsaPub = base64_decode(preg_replace('/-----[^-]+-----|\s/', '', openssl_pkey_get_details($rsa)['key']));
[$s, $r] = $dev->req($t, 'bind', $inv['id'], ['bind' => $inv['bind'], 'pub' => b64u($rsaPub)]);
check('bind mit RSA-Schluessel abgelehnt', $s === 400 && $r['error'] === 'pub', $r);
$other384 = openssl_pkey_new(['private_key_type' => OPENSSL_KEYTYPE_EC, 'curve_name' => 'secp384r1']);
$p384 = base64_decode(preg_replace('/-----[^-]+-----|\s/', '', openssl_pkey_get_details($other384)['key']));
[$s, $r] = $dev->req($t, 'bind', $inv['id'], ['bind' => $inv['bind'], 'pub' => b64u($p384)]);
check('bind mit P-384 abgelehnt', $s === 400 && $r['error'] === 'pub', $r);
$imposter = new Device();
[$s, $r] = $dev->req($t, 'bind', $inv['id'], ['bind' => $inv['bind'], 'pub' => b64u($imposter->pub)]);
check('bind ohne passenden privaten Schluessel', $s === 403 && $r['error'] === 'bad_signature', $r);
[$s, $r] = $dev->req($t, 'bind', $inv['id'], ['bind' => $inv['bind'], 'pub' => b64u($dev->pub)]);
check('bind danach richtig', $s === 200, $r);
[$s, $inv3] = create($t, $owner);
$t->call('/v1/manage', ['owner' => $owner, 'id' => $inv3['id'], 'action' => 'block']);
[$s, $r] = $dev->req($t, 'bind', $inv3['id'], ['bind' => $inv3['bind'], 'pub' => b64u($dev->pub)]);
check('gesperrte Einladung laesst sich nicht binden', $s === 403 && $r['error'] === 'blocked', $r);

// gesperrt und zugleich abgelaufen -> blocked; Binden einer abgelaufenen Einladung
[$s, $inv4] = create($t, $owner, ['expires' => $t->now + 5]);
$t->call('/v1/manage', ['owner' => $owner, 'id' => $inv4['id'], 'action' => 'block']);
$t->now += 10;
[$s, $r] = $t->call('/v1/info', ['owner' => $owner]);
$row = array_values(array_filter($r['invites'], fn($i) => $i['id'] === $inv4['id']))[0];
check('gesperrt + abgelaufen = gesperrt', $row['status'] === 'blocked', $row);
[$s, $inv5] = create($t, $owner, ['expires' => $t->now + 5]);
$t->now += 5;
$d5 = new Device();
[$s, $r] = $d5->req($t, 'bind', $inv5['id'], ['bind' => $inv5['bind'], 'pub' => b64u($d5->pub)]);
check('abgelaufene Einladung laesst sich nicht binden', $s === 403 && $r['error'] === 'expired', $r);
[$s, $r] = $t->call('/v1/manage', ['owner' => $owner, 'id' => $inv5['id'], 'action' => 'expires', 'expires' => 'bald']);
check('manage expires ungueltig', $s === 400 && $r['error'] === 'expires', $r);

// kaputte Signaturen und Schluessel
$d6 = new Device();
[$s, $inv6] = create($t, $owner);
foreach (['kurz' => b64u('xx'), 'Muell' => b64u(random_bytes(70)), 'zu lang' => b64u(random_bytes(200))] as $name => $sig) {
    $ts = $t->now;
    $n = T::nonce();
    [$s, $r] = $t->call('/v1/bind', ['id' => $inv6['id'], 'bind' => $inv6['bind'], 'pub' => b64u($d6->pub),
                                     'ts' => $ts, 'nonce' => $n, 'sig' => $sig]);
    check("Signatur $name abgelehnt", in_array($r['error'] ?? '', ['bad_signature', 'sig'], true), $r);
}
$junk = random_bytes(91);
[$s, $r] = $d6->req($t, 'bind', $inv6['id'], ['bind' => $inv6['bind'], 'pub' => b64u($junk)]);
check('Muell-Schluessel mit 91 Byte', $s === 400 && $r['error'] === 'pub', $r);
$compressed = random_bytes(59);
[$s, $r] = $d6->req($t, 'bind', $inv6['id'], ['bind' => $inv6['bind'], 'pub' => b64u($compressed)]);
check('komprimierter Schluessel (59 Byte)', $s === 400 && $r['error'] === 'pub', $r);

// Wiederholung an der Fenstergrenze: Nonce lebt 600 s, Zeitfenster +-300 s
$d6->req($t, 'bind', $inv6['id'], ['bind' => $inv6['bind'], 'pub' => b64u($d6->pub)]);
$ts0 = $t->now;
$n0 = T::nonce();
[$s, $r] = $d6->req($t, 'key', $inv6['id'], [], $n0, $ts0);
check('key fuer Grenztest', $s === 200, $r);
$t->now = $ts0 + 300;
$t->svc->housekeeping();
[$s, $r] = $d6->req($t, 'key', $inv6['id'], [], $n0, $ts0);
check('Wiederholung nach 300 s', $s === 409 && $r['error'] === 'replay', $r);
$t->now = $ts0 + 301;
$t->svc->housekeeping();
[$s, $r] = $d6->req($t, 'key', $inv6['id'], [], $n0, $ts0);
check('nach 301 s ausserhalb des Zeitfensters', $s === 400 && $r['error'] === 'clock', $r);

// Typverwirrung
foreach (['ts float' => ['ts' => 1.5e9], 'ts bool' => ['ts' => true], 'ts riesig' => ['ts' => PHP_INT_MAX]] as $name => $bad) {
    [$s, $r] = $t->call('/v1/create', $bad + ['owner' => $owner, 'nonce' => T::nonce()]);
    check("Eingabe: $name", $s === 400 && in_array($r['error'], ['ts', 'clock'], true), $r);
}
foreach (['expires float' => 1900000000.5, 'expires bool' => true, 'expires riesig' => PHP_INT_MAX] as $name => $bad) {
    [$s, $r] = create($t, $owner, ['expires' => $bad]);
    check("Eingabe: $name", $s === 400 && $r['error'] === 'expires', $r);
}

// nie geoeffnete Einladung verfaellt nach 30 Tagen, mit spaeterem Ablaufdatum erst nach 180 Tagen
[$s, $inv7] = create($t, $owner);
[$s, $inv8] = create($t, $owner, ['expires' => $t->now + 60 * 86400]);
$t->now += 30 * 86400 + 1;
$t->svc->housekeeping();
[$s, $r] = $t->call('/v1/info', ['owner' => $owner]);
check('ungebundene Einladung nach 30 Tagen weg', !in_array($inv7['id'], array_column($r['invites'], 'id'), true)
    && in_array($inv6['id'], array_column($r['invites'], 'id'), true)
    && in_array($inv8['id'], array_column($r['invites'], 'id'), true), $r);

// --- Eingaben ---
foreach ([
    ['GET /v1/key', '/v1/key', '{}', 'GET', 405, 'method'],
    ['unbekannter Pfad', '/v1/nix', '{}', 'POST', 404, 'not_found'],
    ['kaputtes JSON', '/v1/create', '{', 'POST', 400, 'bad_json'],
    ['JSON-Liste', '/v1/create', '[1]', 'POST', 400, 'bad_json'],
    ['zu gross', '/v1/create', str_repeat(' ', 20000), 'POST', 413, 'too_large'],
    ['owner zu kurz', '/v1/create', json_encode(['owner' => b64u(random_bytes(16)), 'ts' => $t->now, 'nonce' => T::nonce()]), 'POST', 400, 'owner'],
    ['owner Standard-base64', '/v1/info', json_encode(['owner' => base64_encode(random_bytes(32))]), 'POST', 400, 'owner'],
    ['id falsch', '/v1/key', json_encode(['id' => '../../etc', 'ts' => $t->now, 'nonce' => T::nonce(), 'sig' => 'AA']), 'POST', 400, 'id'],
    ['ts als Text', '/v1/create', json_encode(['owner' => $owner, 'ts' => (string)$t->now, 'nonce' => T::nonce()]), 'POST', 400, 'ts'],
    ['nonce zu kurz', '/v1/create', json_encode(['owner' => $owner, 'ts' => $t->now, 'nonce' => 'abc']), 'POST', 400, 'nonce'],
    ['action unbekannt', '/v1/manage', json_encode(['owner' => $owner, 'id' => $inv['id'], 'action' => 'drop']), 'POST', 400, 'action'],
] as [$name, $path, $body, $method, $want, $err]) {
    [$s, $r] = $t->svc->handle($method, $path, $body, $t->ip);
    check("Eingabe: $name", $s === $want && ($r['error'] ?? '') === $err, [$s, $r]);
}
[$s, $r] = $t->svc->handle('GET', '/v1/health/', '', $t->ip);
check('health mit Schraegstrich', $s === 200, $r);

// --- Grenzen ---
$t2 = new T(false, ['max_invites_per_owner' => 2, 'max_invites_total' => 3]);
create($t2, $owner);
create($t2, $owner);
[$s, $r] = create($t2, $owner);
check('Grenze je Besitzer', $s === 429 && $r['error'] === 'owner_limit', $r);
create($t2, $other);
[$s, $r] = create($t2, b64u(random_bytes(32)));
check('Grenze gesamt', $s === 503 && $r['error'] === 'full', $r);

$t3 = new T();
for ($i = 0; $i < 30; $i++) create($t3, b64u(random_bytes(32)));
[$s, $r] = create($t3, b64u(random_bytes(32)));
check('Anfragebegrenzung create', $s === 429 && $r['error'] === 'rate_limit', $r);
$t3->ip = '203.0.113.9';
[$s, $r] = create($t3, b64u(random_bytes(32)));
check('andere IP nicht betroffen', $s === 200, $r);
$t3->ip = '198.51.100.7';
$t3->now += 3600;
[$s, $r] = create($t3, b64u(random_bytes(32)));
check('naechste Stunde wieder frei', $s === 200, $r);
// Tagesgrenze 100 (bisher 31 an diesem Tag): zwei volle Stunden, dann noch 9 erlaubt
for ($h = 0; $h < 2; $h++) {
    $t3->now += 3600;
    for ($i = 0; $i < 30; $i++) create($t3, b64u(random_bytes(32)));
}
$t3->now += 3600;
for ($i = 0; $i < 9; $i++) [$s, $r] = create($t3, b64u(random_bytes(32)));
check('Tagesgrenze noch nicht erreicht', $s === 200, $r);
[$s, $r] = create($t3, b64u(random_bytes(32)));
check('Tagesgrenze create', $s === 429 && $r['error'] === 'rate_limit', $r);
// IPv6: ganzes /64 zaehlt als eine Adresse
$t5 = new T();
for ($i = 0; $i < 30; $i++) {
    $t5->ip = '2001:db8:1:2:' . dechex($i) . '::1';
    create($t5, b64u(random_bytes(32)));
}
$t5->ip = '2001:db8:1:2:ffff::9';
[$s, $r] = create($t5, b64u(random_bytes(32)));
check('IPv6 /64 gemeinsam begrenzt', $s === 429 && $r['error'] === 'rate_limit', $r);
$t5->ip = '2001:db8:1:3::1';
[$s, $r] = create($t5, b64u(random_bytes(32)));
check('anderes /64 frei', $s === 200, $r);
check('IPv4-mapped = IPv4', HHKeys\ipBucket('::ffff:198.51.100.7') === '198.51.100.7', HHKeys\ipBucket('::ffff:198.51.100.7'));
// Bremse fuer bind
$t6 = new T();
[$s, $inv] = create($t6, $owner);
for ($i = 0; $i < 30; $i++) {
    $d = new Device();
    $d->req($t6, 'bind', $inv['id'], ['bind' => b64u(random_bytes(32)), 'pub' => b64u($d->pub)]);
}
[$s, $r] = (new Device())->req($t6, 'bind', $inv['id'], ['bind' => $inv['bind'], 'pub' => b64u((new Device())->pub)]);
check('Anfragebremse bind (Raten des Geheimnisses)', $s === 429 && $r['error'] === 'rate_limit', $r);

// --- Play Integrity (nachgebaut) ---
$ti = new T(true);
[$s, $r] = $ti->call('/v1/health', [], 'GET');
check('health integrity an', $r['integrity'] === true, $r);
[$s, $r] = create($ti, $owner);
check('create ohne Token', $s === 401 && $r['error'] === 'integrity_required', $r);
$ts = $ti->now;
$n = T::nonce();
$oh = hash('sha256', HHKeys\unb64u($owner));
[$s, $r] = $ti->call('/v1/create', ['owner' => $owner, 'ts' => $ts, 'nonce' => $n, "integrity" => tok(requestHash("create|$oh|$ts|$n"))]);
check('create mit Token', $s === 200, $r);
$inv = $r;
[$s, $r] = create($ti, $owner, ["integrity" => tok(requestHash("create|falsch"))]);
check('create mit Token einer anderen Anfrage', $s === 403 && $r['error'] === 'integrity' && $r['reason'] === 'request', $r);
$dev = new Device();
[$s, $r] = $dev->req($ti, 'bind', $inv['id'], ['bind' => $inv['bind'], 'pub' => b64u($dev->pub)]);
check('bind ohne Token', $s === 401 && $r['error'] === 'integrity_required', $r);
[$s, $r] = $dev->req($ti, 'bind', $inv['id'], ['bind' => $inv['bind'], 'pub' => b64u($dev->pub), 'integrity' => 'auto']);
check('bind mit Token', $s === 200, $r);
[$s, $r] = $dev->req($ti, 'key', $inv['id']);
check('key innerhalb 24 h ohne Token', $s === 200 && $r['key'] === $inv['key'], $r);
$ti->now += 86400;
[$s, $r] = $dev->req($ti, 'key', $inv['id']);
check('key nach 24 h verlangt Token', $s === 401 && $r['error'] === 'integrity_required', $r);
[$s, $r] = $dev->req($ti, 'key', $inv['id'], ['integrity' => 'auto']);
check('key mit Token', $s === 200, $r);
[$s, $r] = $dev->req($ti, 'key', $inv['id']);
check('danach wieder 24 h ohne Token', $s === 200, $r);

[$s, $r] = create($ti, $owner, ['integrity' => 'zu-kurz']);
check('offensichtlich ungueltiges Token ohne Google-Aufruf', $s === 403 && $r['reason'] === 'token', $r);
$ti->now += 86400;  // Zwischenspeicher abgelaufen -> jeder key-Abruf fragt Google
$ti->now = (intdiv($ti->now, 3600) + 1) * 3600;
for ($i = 0; $i < 60; $i++) [$s, $r] = $dev->req($ti, 'key', $inv['id'], ['integrity' => tok('falsch')]);
check('60 Google-Aufrufe je Stunde erlaubt', $s === 403 && $r['error'] === 'integrity', $r);
[$s, $r] = $dev->req($ti, 'key', $inv['id'], ['integrity' => 'auto']);
check('Google-Aufrufe je Adresse begrenzt', $s === 429 && $r['error'] === 'rate_limit', $r);

// --- PlayIntegrity mit nachgebautem Google ---
require __DIR__ . '/../src/integrity.php';
final class FakeGoogle extends HHKeys\PlayIntegrity
{
    public array $calls = [];
    public array $payload = [];

    protected function post(string $url, string $body, array $headers): array
    {
        $this->calls[] = $url;
        if (str_contains($url, 'oauth2')) {
            parse_str($body, $f);
            [$h, $c, $sig] = explode('.', $f['assertion']);
            $claims = json_decode(base64_decode(strtr($c, '-_', '+/')), true);
            $ok = $claims['scope'] === 'https://www.googleapis.com/auth/playintegrity' && $claims['aud'] === $url;
            return $ok ? [200, json_encode(['access_token' => 'at', 'expires_in' => 3599])] : [400, '{}'];
        }
        return [200, json_encode(['tokenPayloadExternal' => $this->payload])];
    }
}
$rsa = openssl_pkey_new(['private_key_type' => OPENSSL_KEYTYPE_RSA, 'private_key_bits' => 2048]);
openssl_pkey_export($rsa, $pemPriv);
$saFile = tempnam(sys_get_temp_dir(), 'sa');
file_put_contents($saFile, json_encode(['client_email' => 'x@y.iam.gserviceaccount.com', 'private_key' => $pemPriv,
                                        'token_uri' => 'https://oauth2.googleapis.com/token']));
$gdb = new PDO('sqlite::memory:');
new Service($gdb, ['master_key' => base64_encode(random_bytes(32))], null); // legt Tabellen an
$g = new FakeGoogle(['service_account' => $saFile, 'package' => 'de.camperflower.homehyrax'], $gdb);
$good = ['requestDetails' => ['requestPackageName' => 'de.camperflower.homehyrax', 'requestHash' => 'H',
                              'timestampMillis' => (string)(int)(microtime(true) * 1000)],
         'appIntegrity' => ['appRecognitionVerdict' => 'PLAY_RECOGNIZED', 'packageName' => 'de.camperflower.homehyrax'],
         'deviceIntegrity' => ['deviceRecognitionVerdict' => ['MEETS_DEVICE_INTEGRITY']]];
$g->payload = $good;
check('Integrity echt', $g('t', 'H') === null, $g->calls);
check('Integrity anderer requestHash', $g('t', 'X') === 'request');
$g->payload = array_replace_recursive($good, ['appIntegrity' => ['appRecognitionVerdict' => 'UNRECOGNIZED_VERSION']]);
check('Integrity umgebaute App', $g('t', 'H') === 'app');
$g->payload = $good;
$g->payload['deviceIntegrity']['deviceRecognitionVerdict'] = [];
check('Integrity kein echtes Geraet', $g('t', 'H') === 'device');
$g->payload = array_replace_recursive($good, ['requestDetails' => ['timestampMillis' => '1000']]);
check('Integrity altes Token', $g('t', 'H') === 'stale');
$g->payload = array_replace_recursive($good, ['requestDetails' => ['requestPackageName' => 'evil']]);
check('Integrity anderes Paket', $g('t', 'H') === 'package');
check('Google-Zugang zwischengespeichert', count(array_filter($g->calls, fn($u) => str_contains($u, 'oauth2'))) === 1, $g->calls);
unlink($saFile);

// --- gespeicherte Schluessel sind verschluesselt ---
$pdo = new PDO('sqlite::memory:');
$cfg = ['master_key' => base64_encode(random_bytes(32)), 'ip_salt' => 'x'];
$t4 = new T();
$t4->svc = new Service($pdo, $cfg, null);
$t4->svc->clock = fn(): int => $t4->now;
[$s, $inv] = create($t4, $owner);
$row = $pdo->query('SELECT * FROM invites')->fetch(PDO::FETCH_ASSOC);
check('Schluessel nicht im Klartext gespeichert',
    !str_contains(json_encode($row), $inv['key']) && !str_contains(json_encode($row), $inv['bind'])
    && !str_contains(json_encode($row), $owner), $row);
$svc2 = new Service($pdo, ['master_key' => base64_encode(random_bytes(32))] + $cfg, null);
$svc2->clock = fn(): int => $t4->now;
$dev = new Device();
$dev->req($t4, 'bind', $inv['id'], ['bind' => $inv['bind'], 'pub' => b64u($dev->pub)]);
$t4->svc = $svc2;
try {
    $dev->req($t4, 'key', $inv['id']);
    check('falscher master_key faellt auf', false);
} catch (RuntimeException) {
    check('falscher master_key faellt auf', true);
}
try {
    new Service(new PDO('sqlite::memory:'), ['master_key' => 'kurz'], null);
    check('master_key-Laenge geprueft', false);
} catch (RuntimeException) {
    check('master_key-Laenge geprueft', true);
}

echo $failed ? "\n$failed von $checks Pruefungen FEHLGESCHLAGEN\n" : "OK $checks Pruefungen\n";
exit($failed ? 1 : 0);
