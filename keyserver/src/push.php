<?php
// Push-Benachrichtigungen: stellt eine Nachricht ueber Firebase Cloud Messaging (HTTP v1) an ein Handy zu.
// Nur Datennachrichten - die App baut die Benachrichtigung selbst (Kanal Dringend/Mitteilungen).
declare(strict_types=1);

namespace HHKeys;

require_once __DIR__ . '/google.php';

class Fcm
{
    use GoogleCalls;

    private const SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';

    public function __construct(private array $cfg, private \PDO $db)
    {
        $this->loadServiceAccount((string)($cfg['push_service_account'] ?? $cfg['service_account'] ?? ''));
        if (!preg_match('/^[a-z][a-z0-9-]{4,29}$/', (string)($cfg['firebase']['project_id'] ?? ''))) {
            throw new \RuntimeException('firebase project_id missing or invalid');
        }
    }

    /**
     * @param array<string,string> $data Inhalt der Datennachricht
     * @return string 'ok', 'gone' (Token ungueltig: App deinstalliert oder neu registriert) oder 'error'
     */
    public function __invoke(string $token, array $data, bool $urgent, bool $validateOnly = false): string
    {
        $msg = ['message' => ['token' => $token, 'data' => (object)$data,
            // dringend: sofort zustellen, auch im Doze-Modus; nach einem Tag ist sie nicht mehr dringend
            'android' => $urgent ? ['priority' => 'high', 'ttl' => '86400s'] : ['priority' => 'normal']]];
        // validate_only: Google prueft nur das Token, nichts wird zugestellt
        if ($validateOnly) $msg['validate_only'] = true;
        [$code, $res] = $this->post('https://fcm.googleapis.com/v1/projects/'
            . rawurlencode((string)$this->cfg['firebase']['project_id']) . '/messages:send',
            json_encode($msg, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE),
            ['Content-Type: application/json', 'Authorization: Bearer ' . $this->accessToken(self::SCOPE)]);
        if ($code === 200) return 'ok';
        // UNREGISTERED bzw. Token aus einem anderen Projekt (SENDER_ID_MISMATCH): Geraet ist weg.
        // 400 nur, wenn Google das Token selbst bemaengelt - sonst wuerde ein Fehler bei uns Geraete abmelden
        $err = json_decode($res, true)['error'] ?? [];
        $codes = array_column(is_array($err['details'] ?? null) ? $err['details'] : [], 'errorCode');
        // nur ausdrueckliche Codes: ein 404 wegen falscher Projekt-id darf nicht alle Geraete abmelden
        if (in_array('UNREGISTERED', $codes, true) || in_array('SENDER_ID_MISMATCH', $codes, true)) return 'gone';
        if ($code === 400 && str_contains(strtolower((string)($err['message'] ?? '')), 'registration token')) return 'gone';
        return 'error';
    }
}
