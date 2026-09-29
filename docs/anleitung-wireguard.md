# WireGuard einrichten

WireGuard verbindet das Handy **direkt mit deinem Router zuhause**, ohne einen Dienst dazwischen. Das ist der
schnellste und privateste Weg. Voraussetzung: Dein Internetanschluss ist von außen erreichbar, also mit einer
**eigenen öffentlichen IPv4-Adresse** (klassischer DSL-/Glasfaseranschluss) oder über **IPv6**.

> **Kein eigener Zugang von außen?** Bei vielen Kabel- und Glasfaseranschlüssen (DS-Lite, CGNAT) und bei
> Mobilfunk-Routern im Camper ist der Router von außen nicht über IPv4 erreichbar. Dann nimm
> [Tailscale](anleitung-tailscale.md) – das funktioniert ohne Portfreigabe.

## FRITZ!Box (ab FRITZ!OS 7.50)

1. Im Browser `http://fritz.box` öffnen und anmelden.
2. **Internet → Freigaben → VPN (WireGuard)** → **Verbindung hinzufügen**.
3. **Einzelgerät verbinden** wählen, Name z. B. „HomeHyrax Handy", weiter.
4. Die FRITZ!Box bittet um eine Bestätigung (Taste an der Box oder Telefon) – bestätigen.
5. Es erscheint ein **QR-Code**. Seite offen lassen.
6. In HomeHyrax: **Tunnel → + Tunnel → WireGuard → QR-Code scannen** und den Code abfotografieren.
   Alternativ: Screenshot machen und **QR-Code aus Bild** wählen, oder die Einstellungsdatei herunterladen und
   unter **… oder Konfiguration einfügen** einfügen.
7. Speichern. Über **Verbindung testen** siehst du sofort, ob die FRITZ!Box antwortet.

Tipps:
- **Eine Verbindung pro Handy.** Zwei Handys mit demselben Schlüssel stören sich, sobald beide gleichzeitig
  unterwegs sind. Für ein zweites Handy in der FRITZ!Box eine weitere Verbindung anlegen.
- Die FRITZ!Box nutzt ihre **MyFRITZ!-Adresse** (`…myfritz.net`) als Ziel. HomeHyrax löst sie bei jedem
  Verbindungsaufbau neu auf, eine wechselnde IP ist also kein Problem.
- Die FRITZ!Box trägt `0.0.0.0/0` (alles durch den Tunnel) in die Konfiguration ein. HomeHyrax ignoriert das
  bewusst: Nur die Adressen deiner Kacheln gehen durch den Tunnel, alles andere bleibt, wie es ist.

## Andere Router und Server

HomeHyrax liest die übliche WireGuard-Konfiguration (`wg-quick`-Format). Jeder Router, der eine solche Datei
oder einen QR-Code für ein Gerät („Peer", „Client") erzeugt, funktioniert:

| Gerät | Wo |
|---|---|
| OpenWrt | Paket `luci-proto-wireguard`, Netzwerk → Schnittstellen → WireGuard, Peer hinzufügen, „Generate configuration" |
| OPNsense / pfSense | VPN → WireGuard, Instanz + Peer anlegen, Client-Konfiguration exportieren |
| UniFi (Gateway) | Einstellungen → VPN → VPN-Server → WireGuard, Client hinzufügen, Datei herunterladen |
| Synology Router, ASUS, GL.iNet u. a. | VPN-Server → WireGuard → Client/Profil anlegen, Datei oder QR-Code |
| Linux / Raspberry Pi | eigener WireGuard-Server, z. B. mit PiVPN (`pivpn add`, `pivpn -qr`) |

Die Konfiguration für das Handy sieht so aus (Beispielwerte):

```ini
[Interface]
PrivateKey = <Schlüssel des Handys>
Address = 192.168.178.201/24

[Peer]
PublicKey = <Schlüssel des Routers>
AllowedIPs = 192.168.178.0/24
Endpoint = meinzuhause.example.net:51820
PersistentKeepalive = 25
```

Wichtig ist **`AllowedIPs`**: Darin muss dein Heimnetz stehen (hier `192.168.178.0/24`). Nur Adressen in
diesem Bereich schickt HomeHyrax durch den Tunnel. Beim Router muss der UDP-Port (hier `51820`) von außen
erreichbar sein; die meisten Router geben ihn beim Einrichten des WireGuard-Servers selbst frei.

## Kachel anlegen

1. **Home → + Neu** → Art wählen (Webseite, Schalter oder Kamera), Adresse im Heimnetz eintragen,
   z. B. `192.168.178.1`.
2. Bei **VPN** „Smart" wählen (zuhause direkt, unterwegs Tunnel) und die WireGuard-Verbindung auswählen.
3. Zum Ausprobieren das WLAN am Handy ausschalten und die Kachel öffnen – oben erscheint kurz
   „Unterwegs · über WireGuard".

## Wenn es nicht klappt

| Meldung / Verhalten | Ursache und Lösung |
|---|---|
| „Keine Antwort von zuhause" | Router von außen nicht erreichbar: Anschluss mit DS-Lite/CGNAT → [Tailscale](anleitung-tailscale.md). Sonst Port-Freigabe und die Adresse (`Endpoint`) prüfen |
| Name kann nicht aufgelöst werden | Die DynDNS-/MyFRITZ!-Adresse stimmt nicht oder ist beim Anbieter nicht aktiv |
| Tunnel steht, Seite lädt nicht | Die Adresse der Kachel liegt nicht in `AllowedIPs` – Heimnetz in der Konfiguration ergänzen |
| Klappt mit einem Handy, mit zwei nicht | Beide nutzen denselben Schlüssel – für jedes Handy eine eigene Verbindung anlegen |
