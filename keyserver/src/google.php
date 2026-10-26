<?php
// Zugang zu Google-APIs mit dem Dienstkonto (JWT -> OAuth-Zugang), gemeinsam fuer Play Integrity und Push (FCM)
declare(strict_types=1);

namespace HHKeys;

trait GoogleCalls
{
    private array $sa;

    private function loadServiceAccount(string $file): void
    {
        $sa = json_decode((string)@file_get_contents($file), true);
        if (!is_array($sa) || empty($sa['client_email']) || empty($sa['private_key'])) {
            throw new \RuntimeException('service_account file missing or invalid');
        }
        $this->sa = $sa;
    }

    /** OAuth-Zugang des Dienstkontos je Scope, ~55 min in der Datenbank zwischengespeichert */
    private function accessToken(string $scope): string
    {
        $cacheKey = 'google_token:' . $scope;
        $st = $this->db->prepare('SELECT v FROM meta WHERE k = ?');
        $st->execute([$cacheKey]);
        $c = json_decode((string)$st->fetchColumn(), true);
        if (is_array($c) && ($c['exp'] ?? 0) > time() + 60) return $c['token'];

        $now = time();
        $uri = $this->sa['token_uri'] ?? 'https://oauth2.googleapis.com/token';
        $jwt = b64u(json_encode(['alg' => 'RS256', 'typ' => 'JWT'])) . '.' . b64u(json_encode([
            'iss' => $this->sa['client_email'], 'scope' => $scope, 'aud' => $uri,
            'iat' => $now, 'exp' => $now + 3600]));
        if (!openssl_sign($jwt, $sig, $this->sa['private_key'], OPENSSL_ALGO_SHA256)) {
            throw new \RuntimeException('cannot sign service account JWT');
        }
        [$code, $res] = $this->post($uri, http_build_query([
            'grant_type' => 'urn:ietf:params:oauth:grant-type:jwt-bearer', 'assertion' => $jwt . '.' . b64u($sig)]),
            ['Content-Type: application/x-www-form-urlencoded']);
        $t = json_decode($res, true) ?: [];
        if ($code !== 200 || empty($t['access_token'])) throw new \RuntimeException("google token: HTTP $code");
        $this->db->prepare('INSERT OR REPLACE INTO meta (k, v) VALUES (?, ?)')
            ->execute([$cacheKey, json_encode(['token' => $t['access_token'],
                                               'exp' => $now + min(3300, (int)($t['expires_in'] ?? 3300))])]);
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
