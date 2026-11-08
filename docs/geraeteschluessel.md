# Bestätigen mit Geräteschlüssel – für Seitenbetreiber

[English](device-key.md) · **Deutsch**

HomeHyrax kann Aktionen an eigenen Geräten mit einem zweiten Faktor bestätigen: Die Seite fragt an, die App zeigt
**einen eigenen Dialog** (Name der Kachel, Text, Adresse – die Seite kann ihn weder fälschen noch selbst antippen) und
unterschreibt nach „Bestätigen“ eine feste Zeichenfolge mit einem **ECDSA-P-256-Schlüssel je Kachel aus dem
Android-Keystore** (nicht exportierbar, wenn vorhanden in StrongBox). Der Server prüft die Unterschrift mit dem
öffentlichen Schlüssel, den du dort einmal einträgst. Beispiele: Garagentor, Alarmanlage, Server-Aktion, Freigabe.

Das geht im Heimnetz, über WireGuard/Tailscale, mit IP-Adressen und selbstsignierten Zertifikaten – ohne
WebAuthn-RP-ID und ohne Cloud-Dienst.

## Einrichten in der App

Kachel bearbeiten → **Erweitert** → **Bestätigen mit Geräteschlüssel**. Die App erzeugt den Schlüssel und zeigt eine
Schlüssel-ID wie `3F2A 9C1B 77D0 4E85`. **Schlüssel kopieren** kopiert den öffentlichen Schlüssel als PEM – auf dem
Server eintragen. Optional: **Fingerabdruck/PIN bei jeder Bestätigung**. **Neuer Schlüssel** ersetzt ihn (neuen auf
dem Server eintragen); Funktion ausschalten und speichern löscht den Schlüssel.

Nur für selbst angelegte Webseiten-Kacheln (nicht für Gast-Kacheln), nur auf diesem Handy (wird nicht geteilt).

## JavaScript-Schnittstelle

`window.HomeHyraxConfirm` gibt es nur bei eingeschalteter Funktion, nur auf der **Hauptseite**, deren Ursprung (Schema,
Host, Port) der Adresse der Kachel entspricht, und nur mit einer Android System WebView, die das unterstützt. Immer
prüfen:

```js
if (window.HomeHyraxConfirm) {
  const res = await window.HomeHyraxConfirm.request({ text: "Garagentor öffnen?", challenge });
  if (res.ok) await fetch("/garage/auf", { method: "POST", body: JSON.stringify({ challenge, ...res }) });
  else console.log("nicht bestätigt:", res.error);
}
```

| Aufruf | Ergebnis (Promise) |
|---|---|
| `request({text, challenge})` | nach dem Dialog: `{ok: true, alg, keyId, fingerprint, publicKey, origin, signature, signatureP1363}` oder `{ok: false, error}` |
| `publicKey()` | `{ok: true, alg, keyId, fingerprint, publicKey}` oder `{ok: false, error}` – ohne Dialog |
| `version` | `1` |

- `text`: steht im Dialog und wird genau so unterschrieben. 1–500 Zeichen, Zeilenumbruch nur als `\n`, keine anderen
  Steuerzeichen, keine Zeichen, die die Schreibrichtung umdrehen (U+202A–202E, U+2066–2069, U+200E/200F, U+061C).
- `challenge`: vom Server, nur einmal gültig und nur kurz. 16–512 Zeichen aus `A–Z a–z 0–9 _ - + / = . ~`
  (base64, base64url oder hex).
- `alg`: `"ES256"` (ECDSA P-256 mit SHA-256).
- `publicKey`: SubjectPublicKeyInfo (DER), base64 – derselbe Inhalt wie im PEM.
- `keyId`: die ersten 32 Hex-Zeichen von SHA-256 über den öffentlichen Schlüssel (DER). `fingerprint`: die ersten 16
  davon, groß, in Vierergruppen – die „Schlüssel-ID“ in der App.
- `signature`: ECDSA-Unterschrift DER-kodiert, base64 (OpenSSL, PHP, Python, Java, Go).
  `signatureP1363`: dieselbe Unterschrift als `r‖s` (64 Byte), base64 (WebCrypto).
- `origin`: der unterschriebene Ursprung, wie `location.origin`.
- `error`: `cancelled` (abgebrochen oder Seite verlassen), `busy` (Dialog schon offen), `invalid` (Text oder Challenge
  nicht erlaubt), `not_visible` (App im Hintergrund oder Kachel gesperrt), `auth_failed` (Fingerabdruck/PIN gescheitert),
  `no_key`, `denied`, `internal`.

## Was unterschrieben wird

UTF-8 von vier Zeilen, verbunden mit `\n` (ohne Umbruch am Ende):

```
HomeHyrax-Confirm-v1
<origin>
<challenge>
<text>
```

`<origin>` = `schema://host[:port]` klein geschrieben, Standard-Port weggelassen, IPv6 in eckigen Klammern – genau
`location.origin`, z. B. `https://192.168.178.20:8790`. Der Text steht zuletzt und darf Zeilenumbrüche enthalten.

## Prüfen auf dem Server

1. Zufällige Challenge erzeugen (z. B. 32 Byte, base64url), mit Aktionstext und Ablauf (1–2 Minuten) speichern.
2. Die Seite gibt Text und Challenge an `request()` und schickt das Ergebnis zurück.
3. Der Server baut die Zeichenfolge aus **eigenem** Ursprung, eigener Challenge und eigenem Text (nie aus der Anfrage),
   prüft die Unterschrift mit dem gespeicherten Schlüssel (bei mehreren Handys vorher `keyId`) und löscht die
   Challenge (nur einmal gültig).

Python (`cryptography`):

```python
import base64
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

key = serialization.load_pem_public_key(open("handy.pem", "rb").read())
msg = f"HomeHyrax-Confirm-v1\n{ORIGIN}\n{challenge}\n{text}".encode()
key.verify(base64.b64decode(res["signature"]), msg, ec.ECDSA(hashes.SHA256()))  # wirft InvalidSignature
```

PHP: `openssl_verify($msg, base64_decode($sig), $pem, OPENSSL_ALGO_SHA256) === 1`
Node.js: `crypto.verify("sha256", Buffer.from(msg), pem, Buffer.from(sig, "base64"))`

**Anmelden über `publicKey()`** (statt Kopieren): möglich, aber vorher den Fingerabdruck, den dein Server anzeigt, mit
der Schlüssel-ID in der App vergleichen.

## Grenzen

- Der Schlüssel belegt, dass **dieses Handy mit dieser HomeHyrax-Kachel** den Text bestätigt hat. Mit
  „Fingerabdruck/PIN bei jeder Bestätigung“ zusätzlich, dass es gerade entsperrt wurde. Diese Prüfung macht die App
  vor dem Unterschreiben; der Schlüssel selbst ist nicht an die Biometrie gebunden – Ein- und Ausschalten behält
  denselben Schlüssel.
- Neu installieren, App-Daten löschen oder Kachel löschen löscht den Schlüssel – dann den neuen eintragen.
- Den Dialog kann die Seite nicht fälschen, er zeigt aber ihren Text: Was der Text bedeutet, entscheidet dein Server.
