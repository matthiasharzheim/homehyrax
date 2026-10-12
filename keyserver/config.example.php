<?php
// Vorlage fuer <home>/config.php (nie ins Repo). Die Werte erzeugt das Einrichtungsskript zufaellig.
return [
    'db' => __DIR__ . '/data/keys.sqlite',
    // 32 Zufallsbytes base64 – verschluesselt die gespeicherten Schluessel; geht er verloren, sind alle Einladungen ungueltig
    'master_key' => '',
    // Zufallswert fuer die Hashes der IP-Adressen (Anfragebegrenzung, max. 2 h gespeichert)
    'ip_salt' => '',
    // 'on' = Google Play Integrity verlangen (braucht service_account)
    'integrity' => 'off',
    // 'device' = echtes Geraet (Standard, fast alle Handys); 'strong' = Sicherheitschip + aktuelles Update (sperrt aeltere aus)
    'integrity_level' => 'device',
    // Projektnummer des Google-Cloud-Projekts, das in der Play Console mit der App verknuepft ist
    'cloud_project' => '',
    'package' => 'de.camperflower.homehyrax',
    'service_account' => __DIR__ . '/play-integrity.json',
    'max_invites_per_owner' => 200,
    'max_invites_total' => 100000,
];
