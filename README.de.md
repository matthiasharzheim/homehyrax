# HomeHyrax (Android)

[English](README.md) · **Deutsch**

Weboberflächen, Schalter und Kameras im Heimnetz (Smarthome, NAS, Router, Shelly, IP-Kamera …) wie eigene Apps
öffnen: **im Heim-WLAN direkt, unterwegs automatisch über einen eigenen Tunnel (WireGuard oder Tailscale)** –
in der App, ohne Android-VPN. Englisch und Deutsch.

**VPN nur dort, wo man es wirklich braucht.** Ein handyweit eingeschaltetes VPN leitet den gesamten Verkehr über
das Heimnetz und stört dabei oft anderes: Android Auto, Navigation und Kartendienste (langsam, falscher Standort),
Banking- oder Streaming-Apps. HomeHyrax bietet denselben Zugriff aufs Heimnetz mit einem Minimalansatz: kein
System-VPN, der Tunnel läuft nur in dieser App, nur für die Adressen der eingerichteten Kacheln und nur, solange
sie benutzt werden.

## Funktionen

- **Drei Kachel-Arten**
  - **Webseite**: Vollbild-WebView mit eigenem Symbol auf dem Startbildschirm, optional Desktop-Ansicht
  - **Schalter**: genau ein Befehl statt einer Seite, z. B. Shelly-Impuls fürs Garagentor
    (Gen1 `/relay/N?turn=on&timer=S`, Gen2 `/rpc/Switch.Set?id=N&on=true&toggle_after=S`), danach der echte
    Zustand („Eingeschaltet"/„Ausgeschaltet"); Shelly-Suche im Heim-WLAN (`GET /shelly`), Passwort per Basic
    oder Digest (SHA-256), erst nach Aufforderung des Geräts gesendet
  - **Kamera**: RTSP-Livebild (media3/ExoPlayer, RTP über TCP), große Kachel mit frischem Bild bei jedem
    App-Start; ein eingefügter Link `rtsp://user:pass@…` wird in Adresse und Zugangsdaten aufgeteilt
- **VPN je Kachel: Kein / Smart / Immer** – Smart = zuhause direkt, unterwegs Tunnel
- **Kein Android-VPN**: belegt keinen VPN-Slot, läuft parallel zu jedem anderen VPN; der Tunnel gilt nur für die
  Ziele der Kachel, nur in dieser App und nur, solange sie offen ist (+90 s)
- **WireGuard** (z. B. FRITZ!Box ab FRITZ!OS 7.50: QR-Code, Screenshot, `.conf`) und **Tailscale/Headscale**
  (für Anschlüsse ohne eigene IP, DS-Lite, CGNAT; Anmeldung im Browser, Subnetz-Router)
- WireGuard-Schlüssel und Passwörter AES-GCM-verschlüsselt mit Android-Keystore-Schlüssel; den
  Tailscale-Geräteschlüssel legt tsnet unverschlüsselt im App-Ordner ab, geschützt durch App-Sandbox und
  Android-Dateiverschlüsselung; kein Cloud-Backup
- Optionale Sperre (Fingerabdruck/Gesicht/PIN) je Kachel, Schalter standardmäßig gesperrt
- Fertige Symbole oder eigenes Bild; **Teilen** aller Einträge per QR-Code oder Datei
- Diagnose: „Verbindung testen" (bei Tailscale mit Route, Gerät, Relay, Ping, TCP), Absturzbericht beim nächsten
  Start (Kotlin + Go, nur lokal), Open-Source-Lizenzen in der App

## Installieren

Eine Veröffentlichung im Play Store ist geplant. Bis dahin: selbst bauen (siehe [Bauen](#bauen)) oder die APK aus
einem Lauf der CI (Actions → Android App → Artefakt `HomeHyrax-apk`). Ohne Signatur-Secrets ist diese APK
unsigniert und muss vor der Installation mit einem eigenen Schlüssel signiert werden (`apksigner`).

## Einrichten

Ausführliche Anleitungen: **[WireGuard (FRITZ!Box und andere Router)](docs/anleitung-wireguard.md)** ·
**[Tailscale (ohne eigene IP, z. B. im Camper)](docs/anleitung-tailscale.md)**. Kurzfassung:

**WireGuard (FRITZ!Box)**
1. `fritz.box` → Internet → Freigaben → VPN (WireGuard) → **Verbindung hinzufügen** → **Einzelgerät**
2. In der App: **Tunnel → + Tunnel → WireGuard → QR-Code scannen** (oder Screenshot/.conf)
3. **Home → + Neu** → Name, Adresse (z. B. `192.168.178.1`), VPN **Smart** → Tunnel wählen

**Tailscale** (ohne eigene IP): **+ Tunnel → Tailscale → Verbinden** (Anmeldung im Browser); zuhause ein
Tailscale-Gerät als Subnetz-Router (z. B. NAS, Raspberry Pi) und die Route in der Tailscale-Verwaltung freigeben.

**Kamera**: in der Kamera-App den RTSP-Link kopieren (z. B. Eufy: Einstellungen → NAS (RTSP), Aufzeichnung auf
„immer") und in HomeHyrax unter **+ Neu → Kamera** einfügen. Akku-Kameras ohne Basisstation bieten meist keinen
lokalen Stream.

## Architektur

```
WebView ──(Proxy 127.0.0.1:<zufall>, Passwort pro App-Start)──▶ wgbridge (Go)
Player (RTSP) ──(Weiterleitung 127.0.0.1:<zufall>)────────────▶   ├─ Route/AllowedIPs → WireGuard-netstack bzw. tsnet
Schalter ──(Wgbridge.request)─────────────────────────────────▶   └─ sonst direkt
```

| Teil | Datei | Aufgabe |
|---|---|---|
| Go | `wgbridge/bridge.go` | Tunnel (wireguard-go + gVisor-netstack), Routing, Handshake-Status |
| Go | `wgbridge/proxy.go` | lokaler HTTP/CONNECT-Proxy mit Anmeldung, SSE-Streaming |
| Go | `wgbridge/forward.go` | lokale Portweiterleitung für RTSP-Kameras |
| Go | `wgbridge/config.go` | wg-quick-Parser (FRITZ!Box-Export), DynDNS-Auflösung, UAPI |
| Go | `wgbridge/request.go` | Einzelanfrage für Schalter, Basic/Digest-Auth |
| Go | `wgbridge/tailscale*.go` | Tailscale (tsnet) im App-Prozess, Anmeldung, Diagnose |
| Kotlin | `Net.kt` | Proxy-Override der WebViews, Zuhause-Erkennung, Tunnel-Lebensdauer |
| Kotlin | `WebAppActivity.kt` / `ActionActivity.kt` / `CameraActivity.kt` | Webseite / Schalter / Kamera |
| Kotlin | `MainActivity.kt` | Oberfläche: Kacheln, Editor, Tunnel, Teilen, Info |
| Kotlin | `Store.kt` / `Shortcuts.kt` / `Share.kt` | Ablage (verschlüsselt), Startbildschirm-Symbole, Teilen |

**Zuhause-Erkennung**: mobile Daten → Tunnel; im WLAN TCP-Verbindung zu `host:port` (800 ms), ein erfolgreiches WLAN
wird je Kachel gemerkt (ohne SSID/Standort-Berechtigung); fremdes WLAN → sofort Tunnel; Ladefehler → Tunnel.
`0.0.0.0/0` in den AllowedIPs wird bewusst ignoriert – sonst liefe jeder Aufruf durch den Tunnel.

**Proxy-Anmeldung**: Die WebView meldet sich per `onReceivedHttpAuthRequest` (Realm `HomeHyrax`) an. Reicht eine
WebView-Version das nicht weiter, schaltet die App in einen Ausweichmodus ohne Passwort – nur bei einer 407 vom
eigenen Proxy und nur, solange eine Webseite sichtbar ist.

## Bauen

```bash
export ANDROID_HOME=/pfad/zum/sdk ANDROID_NDK_HOME=$ANDROID_HOME/ndk/28.2.13676358
./build-go.sh                                   # Go-Tests + gomobile → app/libs/wgbridge.aar
./gradlew lintRelease assembleRelease           # APK (beide ABIs)
./gradlew assembleRelease -Pabis=arm64-v8a      # nur 64-Bit, kleiner
```

Signieren (alles optional, nichts davon liegt im Repo):
- **Eigener Schlüssel für selbst gebaute APKs**, damit neue Versionen sich über die installierte legen: Umgebung
  `SIDELOAD_KEYSTORE` (Pfad), `SIDELOAD_KEYSTORE_PASSWORD`, `SIDELOAD_KEY_ALIAS`, `SIDELOAD_KEY_PASSWORD` oder in
  `local.properties` `sideload.keystore`, `sideload.storePassword`, `sideload.keyAlias`, `sideload.keyPassword`.
  Gilt für Release und Debug.
- **Play-Store-Upload-Schlüssel**: `UPLOAD_KEYSTORE`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`,
  `UPLOAD_KEY_PASSWORD` (hat für Release Vorrang).
- Ohne Angaben: Release-APK unsigniert (`app-release-unsigned.apk`), Debug mit dem Standard-Debug-Schlüssel.

`versionCode` = CI-Laufnummer + Versatz (Standard 100; `-PversionOffset=…`, Umgebung bzw. CI-Repository-Variable
`VERSION_OFFSET`). Der Versatz muss über allen bereits verteilten Builds liegen, sonst lehnt Android das Update ab.
In der CI signieren optional die Secrets `SIDELOAD_KEYSTORE_B64` (base64 der `.jks`) + `SIDELOAD_KEYSTORE_PASSWORD`
die APK, `UPLOAD_KEYSTORE_B64` + Passwörter erzeugen zusätzlich das Store-Bundle (AAB).
Versionen sind bewusst gepinnt (AGP 8.13, Kotlin 2.2, Compose-BOM 2025.10, compileSdk 36, minSdk 26, NDK r28c).

Die Go-Tests bauen echte Gegenstellen im Prozess: einen zweiten WireGuard-Peer (Handshake, GET, POST, SSE, CONNECT,
Proxy-Anmeldung, Weiterleitung, Shelly-Auth) und einen Tailscale-Testserver mit DERP (Anmeldung, Subnetz-Route).

## Grenzen

- Andere Apps lassen sich ohne Android-VPN nicht umleiten – dafür eine VPN-App mit Split-Tunneling nutzen
- Kameras nur mit lokalem RTSP-Stream; reine Cloud-Kameras werden nicht unterstützt
- **Zuhause-Erkennung per Anklopfen**: Ein fremdes WLAN mit demselben Standardnetz (z. B. eine andere FRITZ!Box
  mit `192.168.178.x`), in dem unter der Zieladresse zufällig etwas antwortet, hält die App für zuhause. Abhilfe:
  zuhause einen eigenen Adressbereich statt des Router-Standards verwenden (z. B. `10.20.30.0/24`) oder die
  Kachel auf VPN **Immer** stellen

## Mitwirken

Siehe [CONTRIBUTING.md](CONTRIBUTING.md) (Bauen, Übersetzungen, Konventionen).

## Lizenz

Apache-2.0, siehe `LICENSE` und `NOTICE`. Name und Logo „HomeHyrax“ sind nicht mitlizenziert.
WireGuard ist eine Marke von Jason A. Donenfeld, Tailscale eine Marke von Tailscale Inc.
