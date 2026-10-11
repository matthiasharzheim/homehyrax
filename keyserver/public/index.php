<?php
// Einziger oeffentlicher Einstieg; Programm, Einstellungen und Datenbank liegen ausserhalb des Webordners
declare(strict_types=1);

// PHP-Meldungen nie in die Antwort (Hoster koennte display_errors anhaben)
ini_set('display_errors', '0');
header_remove('X-Powered-By');
$home = getenv('HH_KEYS_HOME') ?: dirname(__DIR__, 2) . '/hh-keys';
require $home . '/src/server.php';
\HHKeys\serve($home);
