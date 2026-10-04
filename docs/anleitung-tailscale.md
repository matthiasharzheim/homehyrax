# Tailscale einrichten

Tailscale ist der Weg für Anschlüsse, die von außen **nicht erreichbar** sind: Kabel- und Glasfaseranschlüsse
mit DS-Lite oder CGNAT, LTE/5G-Router – und damit auch **der Camper**. Beide Seiten bauen die Verbindung von
innen nach außen auf; eine Portfreigabe ist nicht nötig. Tailscale läuft in HomeHyrax **in der App** – die
Tailscale-App und ein eingeschaltetes VPN brauchst du auf dem Handy nicht.

Du brauchst:
- ein kostenloses Konto bei [tailscale.com](https://tailscale.com) (Anmeldung mit Google, Microsoft, Apple oder
  GitHub) – oder einen eigenen [Headscale](https://headscale.net)-Server
- **ein Gerät, das zuhause (bzw. im Camper) dauerhaft läuft** und Tailscale kann: Raspberry Pi, NAS (Synology,
  QNAP, Unraid), Linux-PC, manche Router (z. B. GL.iNet, OpenWrt). Es wird zum **Subnetz-Router**: Es reicht die
  Anfragen der App an die anderen Geräte im Netz weiter.

## 1. Subnetz-Router zuhause einrichten

Im Beispiel ist das Heimnetz `192.168.178.0/24` – ersetze es durch dein eigenes (steht meist im Router unter
„Heimnetz" oder „LAN").

**Raspberry Pi / Linux**

```bash
curl -fsSL https://tailscale.com/install.sh | sh
# Weiterleiten erlauben (einmalig)
echo 'net.ipv4.ip_forward = 1' | sudo tee /etc/sysctl.d/99-tailscale.conf
sudo sysctl -p /etc/sysctl.d/99-tailscale.conf
# anmelden und das Heimnetz freigeben
sudo tailscale up --advertise-routes=192.168.178.0/24
```

Der letzte Befehl zeigt einen Link – im Browser öffnen und mit deinem Tailscale-Konto anmelden.

**Synology NAS (DSM 7)**
1. Paket-Zentrum → **Tailscale** installieren, öffnen, anmelden.
2. Per SSH am NAS: `sudo tailscale set --advertise-routes=192.168.178.0/24`

**Andere Geräte**: Tailscale installieren und in dessen Einstellungen „Subnet router" bzw.
`--advertise-routes` mit deinem Heimnetz setzen.

**Camper mit LTE-Router (z. B. Teltonika)**: Größere Teltonika-Modelle (RUTX, RUTM, TRB1/TRB5) haben Tailscale als
Paket (je nach Firmware: System → Package Manager, danach Services → VPN → Tailscale; siehe Teltonika-Wiki
„Tailscale Configuration Example"). Kleine Modelle wie der **RUT200** haben dafür
zu wenig Speicher – dann einen **Raspberry Pi Zero 2 W** (oder anderen Pi) per WLAN oder LAN an den Router hängen und
ihn wie oben als Subnetz-Router einrichten. Das Camper-Netz steht im Router, beim RUT200 standardmäßig
`192.168.1.0/24`.

## 2. Route in der Tailscale-Verwaltung freigeben

1. [login.tailscale.com/admin/machines](https://login.tailscale.com/admin/machines) öffnen.
2. Beim Subnetz-Router auf **⋯ → Edit route settings** und die Route (`192.168.178.0/24`) **anhaken**.
3. Empfehlung: beim selben Gerät **⋯ → Disable key expiry**, damit der Router nicht nach einigen Monaten neu
   angemeldet werden muss.

## 3. HomeHyrax verbinden

1. In HomeHyrax: **Tunnel → + Tunnel → Tailscale**. Bei Tailscale bleibt das Feld „Eigener Server" leer; bei
   Headscale dort die Adresse deines Servers eintragen.
2. **Verbinden** tippen. Der Browser öffnet die Tailscale-Anmeldung – mit **demselben Konto** wie beim
   Subnetz-Router anmelden, dann zurück zur App.
3. Die Karte zeigt „Verbunden" und das freigegebene Heimnetz. Steht dort noch kein Heimnetz, fehlt Schritt 2.
4. Kachel anlegen: **Home → + Neu**, Adresse im Heimnetz (z. B. `192.168.178.1`), bei **VPN** „Smart" und die
   Tailscale-Verbindung wählen.

Mit einem **Auth-Key** (Tailscale-Verwaltung → Settings → Keys) lässt sich die Anmeldung im Browser überspringen,
z. B. für ein Familien-Handy. Beim Teilen per QR-Code gibt HomeHyrax den Auth-Key bewusst nicht weiter.

## Wenn es nicht klappt

Auf der Tailscale-Karte **Verbindung testen** tippen. HomeHyrax zeigt für jede Kachel, ob die Adresse im
freigegebenen Netz liegt, über welches Gerät sie läuft (direkt oder über ein Relais), ob das Gerät antwortet und ob
die Verbindung zum Ziel klappt. Unter **Details** steht das Tailscale-Protokoll zum Kopieren.

| Ergebnis | Ursache und Lösung |
|---|---|
| Adresse liegt in keinem freigegebenen Netz | Route nicht angekündigt (`--advertise-routes`) oder nicht freigegeben (Schritt 2) |
| Router offline | Subnetz-Router ist aus oder abgemeldet (Schlüssel abgelaufen → Schritt 2, Punkt 3) |
| Tunnel zum Router OK, Verbindung fehlgeschlagen | Router leitet nicht ins Heimnetz weiter: unter Linux IP-Weiterleitung einschalten, Firewall des Zielgeräts prüfen |
| Anmeldung hängt | Im Browser angemeldet, aber mit einem anderen Konto als der Subnetz-Router – mit demselben Konto anmelden |

Bei der Ersteinrichtung und je nach Netz läuft die Verbindung manchmal über ein Tailscale-Relais (DERP) – das ist
langsamer, funktioniert aber. Der Inhalt bleibt Ende-zu-Ende verschlüsselt.
