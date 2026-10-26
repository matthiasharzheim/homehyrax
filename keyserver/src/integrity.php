<?php
// Google Play Integrity: laesst Google das Token der App entschluesseln und prueft, dass die Anfrage von der
// unveraenderten Play-Store-App auf einem echten Geraet kommt und zu genau dieser Anfrage gehoert (requestHash).
declare(strict_types=1);

namespace HHKeys;

require_once __DIR__ . '/google.php';

class PlayIntegrity
{
    use GoogleCalls;

    private const SCOPE = 'https://www.googleapis.com/auth/playintegrity';
    private const MAX_AGE_MS = 300_000;

    public function __construct(private array $cfg, private \PDO $db)
    {
        $this->loadServiceAccount((string)($cfg['service_account'] ?? ''));
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
            ['Content-Type: application/json', 'Authorization: Bearer ' . $this->accessToken(self::SCOPE)]);
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
}

