# Confirm with device key – for page developers

**English** · [Deutsch](geraeteschluessel.md)

HomeHyrax can confirm actions on your own devices with a second factor: the page asks, the app shows **its own
dialog** (tile name, text, address – the page cannot fake or click it) and, after "Confirm", signs a fixed string with a
**per-tile ECDSA P-256 key from the Android Keystore** (not exportable, StrongBox if available). Your server checks
the signature with the public key you entered once. Typical uses: garage door, alarm system, server action, approval.

It works on the home network, through WireGuard/Tailscale, with IP addresses and self-signed certificates – there is
no WebAuthn relying-party ID and no cloud service involved.

## Setup in the app

Edit the tile → **Advanced** → **Confirm with device key**. The app creates the key and shows a key ID such as
`3F2A 9C1B 77D0 4E85`. **Copy key** copies the public key as PEM; enter it on your server. Optional:
**Fingerprint/PIN for every confirmation**. **New key** replaces the key (enter the new one on the server);
switching the option off and saving deletes the key.

Only for website tiles you created yourself (not for guest tiles), only on this phone (not shared).

## JavaScript interface

`window.HomeHyraxConfirm` exists only if the option is on, only on the **main page** whose origin (scheme, host, port)
equals the address of the tile, and only on Android System WebView versions that support it. Always check for it:

```js
if (window.HomeHyraxConfirm) {
  const res = await window.HomeHyraxConfirm.request({ text: "Open garage door?", challenge });
  if (res.ok) await fetch("/garage/open", { method: "POST", body: JSON.stringify({ challenge, ...res }) });
  else console.log("not confirmed:", res.error);
}
```

| Call | Returns (Promise) |
|---|---|
| `request({text, challenge})` | after the dialog: `{ok: true, alg, keyId, fingerprint, publicKey, origin, signature, signatureP1363}` or `{ok: false, error}` |
| `publicKey()` | `{ok: true, alg, keyId, fingerprint, publicKey}` or `{ok: false, error}` – no dialog |
| `version` | `1` |

- `text`: shown in the dialog and signed exactly as passed. 1–500 characters, line breaks only as `\n`, no other
  control characters and no bidirectional override characters (U+202A–202E, U+2066–2069, U+200E/200F, U+061C).
- `challenge`: from your server, single-use and short-lived. 16–512 characters from `A–Z a–z 0–9 _ - + / = . ~`
  (base64, base64url or hex).
- `alg`: `"ES256"` (ECDSA P-256 with SHA-256).
- `publicKey`: SubjectPublicKeyInfo (DER), base64 – the same as the PEM body.
- `keyId`: first 32 hex characters of SHA-256 over the DER public key. `fingerprint`: the first 16 of them, upper case,
  in groups of four – the "key ID" shown in the app.
- `signature`: DER-encoded ECDSA signature, base64 (OpenSSL, PHP, Python, Java, Go).
  `signatureP1363`: the same signature as `r‖s` (64 bytes), base64 (WebCrypto).
- `origin`: the origin that was signed, as in `location.origin`.
- `error`: `cancelled` (user cancelled or left the page), `busy` (a dialog is already open), `invalid` (text or
  challenge not allowed), `not_visible` (app in background or tile locked), `auth_failed` (fingerprint/PIN failed),
  `no_key`, `denied`, `internal`.

## What is signed

UTF-8 bytes of four lines joined with `\n` (no trailing line break):

```
HomeHyrax-Confirm-v1
<origin>
<challenge>
<text>
```

`<origin>` = `scheme://host[:port]` in lower case, default port omitted, IPv6 in brackets – exactly `location.origin`,
e.g. `https://192.168.178.20:8790`. The text is last and may contain line breaks.

## Checking on the server

1. Create a random challenge (e.g. 32 bytes, base64url), store it with the action text and an expiry (1–2 minutes).
2. The page passes text and challenge to `request()` and sends the result back.
3. The server rebuilds the string from **its own** origin, challenge and text (never from the request), checks the
   signature with the stored key (optionally `keyId` first, if several phones are registered) and deletes the
   challenge (single use).

Python (`cryptography`):

```python
import base64
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

key = serialization.load_pem_public_key(open("phone.pem", "rb").read())
msg = f"HomeHyrax-Confirm-v1\n{ORIGIN}\n{challenge}\n{text}".encode()
key.verify(base64.b64decode(res["signature"]), msg, ec.ECDSA(hashes.SHA256()))  # raises InvalidSignature
```

PHP: `openssl_verify($msg, base64_decode($sig), $pem, OPENSSL_ALGO_SHA256) === 1`
Node.js: `crypto.verify("sha256", Buffer.from(msg), pem, Buffer.from(sig, "base64"))`

**Registering via `publicKey()`** (instead of copying): fine, but compare the fingerprint shown by your server with
the key ID in the app before you trust the key.

## Limits

- The key proves that **this phone with this HomeHyrax tile** confirmed the text. With "Fingerprint/PIN for every
  confirmation" it additionally proves that someone unlocked it right then. The fingerprint check is done by the app
  before signing; the key itself is not bound to biometrics, so turning the option on or off keeps the same key.
- Reinstalling the app, clearing its data or deleting the tile deletes the key – enter the new one on the server.
- The dialog cannot be faked by the page, but it shows the page's text: your server decides what the text means.
