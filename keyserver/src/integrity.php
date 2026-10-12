<?php
// Google Play Integrity: laesst Google das Token der App entschluesseln und prueft, dass die Anfrage von der
// unveraenderten Play-Store-App auf einem echten Geraet kommt und zu genau dieser Anfrage gehoert (requestHash).
declare(strict_types=1);

namespace HHKeys;

class PlayIntegrity
{
    private const SCOPE = 'https://www.googleapis.com/auth/playintegrity';
    private const MAX_AGE_MS = 300_000;

    private array $sa;

    public function __construct(private array $cfg, private \PDO $db)
    {
        $sa = json_decode((string)@file_get_contents((string)($cfg['service_account'] ?? '')), true);
        if (!is_array($sa) || empty($sa['client_email']) || empty($sa['private_key'])) {
            throw new \RuntimeException('service_account file missing or invalid');
        }
        $this->sa = $sa;
        // ohne gueltige Projektnummer koennte keine App einen Nachweis holen -> sofort als Einstellungsfehler melden
        if (!preg_match('/^[1-9][0-9]{4,19}$/', (string)($cfg['cloud_project'] ?? ''))) {
            throw new \RuntimeException('cloud_project missing or invalid');
        }
    }

    /** @return ?string null = in Ordnung, sonst Grund der Ablehnung */
    public function __invoke(string $token, string $requestHash): ?string
    {
        $pkg = (string)$this->cfg['package'];
        [$code, $res] = $this->post('https://playintegrity.googleapis.com/v1/' . rawurlencode($pkg)
            . ':decodeIntegrityToken', json_encode(['integrity_token' => $token]),
            ['Content-Type: application/json', 'Authorization: Bearer ' . $this->accessToken()]);
        if ($code !== 200) return $code === 400 ? 'token' : 'google';
        $p = (json_decode($res, true) ?: [])['tokenPayloadExternal'] ?? null;
        if (!is_array($p)) return 'google';
        $req = $p['requestDetails'] ?? [];
        if (($req['requestPackageName'] ?? '') !== $pkg) return 'package';
        if (!hash_equals($requestHash, (string)($req['requestHash'] ?? ''))) return 'request';
        if (abs((int)($req['timestampMillis'] ?? 0) - (int)(microtime(true) * 1000)) > self::MAX_AGE_MS) return 'stale';
        if (($p['appIntegrity']['appRecognitionVerdict'] ?? '') !== 'PLAY_RECOGNIZED') return 'app';
        if (($p['appIntegrity']['packageName'] ?? '') !== $pkg) return 'package';
        // 'device' (Standard) = echtes Geraet, laeuft auf fast allen Handys. 'strong' = Sicherheitschip + aktuelles
        // Sicherheitsupdate, schwer vorzutaeuschen, sperrt aber aeltere/nicht aktualisierte Handys aus
        $need = ($this->cfg['integrity_level'] ?? 'device') === 'strong' ? 'MEETS_STRONG_INTEGRITY' : 'MEETS_DEVICE_INTEGRITY';
        $device = $p['deviceIntegrity']['deviceRecognitionVerdict'] ?? [];
        if (!is_array($device) || !in_array($need, $device, true)) return 'device';
        return null;
    }

    /** OAuth-Zugang des Dienstkontos, ~55 min in der Datenbank zwischengespeichert */
    private function accessToken(): string
    {
        $st = $this->db->prepare("SELECT v FROM meta WHERE k = 'google_token'");
        $st->execute();
        $c = json_decode((string)$st->fetchColumn(), true);
        if (is_array($c) && ($c['exp'] ?? 0) > time() + 60) return $c['token'];

        $now = time();
        $uri = $this->sa['token_uri'] ?? 'https://oauth2.googleapis.com/token';
        $jwt = b64u(json_encode(['alg' => 'RS256', 'typ' => 'JWT'])) . '.' . b64u(json_encode([
            'iss' => $this->sa['client_email'], 'scope' => self::SCOPE, 'aud' => $uri,
            'iat' => $now, 'exp' => $now + 3600]));
        if (!openssl_sign($jwt, $sig, $this->sa['private_key'], OPENSSL_ALGO_SHA256)) {
            throw new \RuntimeException('cannot sign service account JWT');
        }
        [$code, $res] = $this->post($uri, http_build_query([
            'grant_type' => 'urn:ietf:params:oauth:grant-type:jwt-bearer', 'assertion' => $jwt . '.' . b64u($sig)]),
            ['Content-Type: application/x-www-form-urlencoded']);
        $t = json_decode($res, true) ?: [];
        if ($code !== 200 || empty($t['access_token'])) throw new \RuntimeException("google token: HTTP $code");
        $this->db->prepare("INSERT OR REPLACE INTO meta (k, v) VALUES ('google_token', ?)")
            ->execute([json_encode(['token' => $t['access_token'], 'exp' => $now + min(3300, (int)($t['expires_in'] ?? 3300))])]);
        return $t['access_token'];
    }

    /** @return array{0:int,1:string} */
    protected function post(string $url, string $body, array $headers): array
    {
        $ch = curl_init($url);
        curl_setopt_array($ch, [CURLOPT_POST => true, CURLOPT_POSTFIELDS => $body, CURLOPT_HTTPHEADER => $headers,
            CURLOPT_RETURNTRANSFER => true, CURLOPT_CONNECTTIMEOUT => 5, CURLOPT_TIMEOUT => 10,
            CURLOPT_PROTOCOLS => CURLPROTO_HTTPS]);
        $res = curl_exec($ch);
        $code = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
        curl_close($ch);
        if ($res === false) throw new \RuntimeException('google unreachable');
        return [$code, (string)$res];
    }
}
