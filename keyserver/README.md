# HomeHyrax key service

Small PHP + SQLite service behind the HomeHyrax **guest access** feature: an owner shares entries as an
encrypted file; the key to that file lives only here. The guest's app fetches it on every tap, and the owner can
block the guest at any time. The service never sees VPN configs, passwords or home-network addresses — only random
keys that mean nothing on their own.

The app finds the service through a signpost on the website, so the service can move without an app update:

```
https://homehyrax.com/keys.json  →  {"v": 1, "keys": "https://camperflower.de/keys/v1/"}
```

The app only accepts service addresses on hosts it knows, so a tampered signpost cannot redirect it.

## Protection

| Layer | What it does |
|---|---|
| Encrypted file | Each invite has its own random 256-bit key; the file alone is useless |
| Server release | The key is only returned while the invite is active and not expired |
| Device binding | The first device to open the invite registers an EC P-256 key (Android Keystore); every later request must be signed with it. Once the guest has opened it, a forwarded file does not work on another phone. Whoever opens the file first wins – owner and guest can compare the short device fingerprint (`device`) |
| Play Integrity (optional) | Google confirms the request comes from the unmodified Play Store app on a genuine device; bound to the exact request via `requestHash` |
| Replay protection | Every signed request carries a timestamp (±5 min) and a single-use nonce |

Stored keys are encrypted with a master key (`sodium_crypto_secretbox`). Owners are identified only by the
SHA-256 of a random 32-byte token held by their app. IP addresses are kept only as salted hashes for rate
limiting (IPv6 per /64; kept until the end of the hour or day window). Expired invites are deleted 30 days after
expiry; invites that were never opened after 30 days (with a later expiry date: after 180 days at most). Clean-up runs at most every 10 minutes, triggered by the next request.

Blocking stops future key requests. A key that a modified app has already read out cannot be taken back – that is
what Play Integrity is for; for full certainty the owner also removes the guest's WireGuard peer in the router.

## API

All requests are `POST` with a JSON body; responses are JSON. Binary values are base64url without padding.
Errors: `{"error": "<code>"}` with an HTTP status.

Signed requests: `sig` = ECDSA-SHA256 (DER) over `HH1|<action>|<id>|<ts>|<nonce>` with the device key.
With Play Integrity on, `integrity` carries a standard-request token whose `requestHash` is
`base64url(SHA-256("HH1|<action>|<id-or-owner-hash>|<ts>|<nonce>"))`; after a successful check the device needs no
token for 24 h per invite (the server answers `401 integrity_required` when it does). Calls to Google are limited
per address, and malformed tokens are rejected without a call.

| Endpoint | Caller | Body | Answer |
|---|---|---|---|
| `GET health` | anyone | – | `{"ok": true, "api": 1, "integrity": false}` |
| `create` | owner | `owner`, `ts`, `nonce`, `expires` (Unix time or null), [`integrity`] | `id`, `key`, `bind`, `expires` |
| `bind` | guest, once | `id`, `bind`, `pub` (SubjectPublicKeyInfo DER), `ts`, `nonce`, `sig`, [`integrity`] | `ok`, `device`, `expires` |
| `key` | guest, every tap | `id`, `ts`, `nonce`, `sig`, [`integrity`] | `key`, `expires` |
| `manage` | owner | `owner`, `id`, `action` = `block` / `activate` / `expires` (+`expires`) / `delete` | `ok` |
| `info` | owner | `owner` | `invites`: `id`, `status` (active/blocked/expired), `bound`, `device`, `created`, `expires`, `bound_at`, `last_used`; `now` |

Error codes include `unknown` (404, also for another owner's invite), `blocked` / `expired` / `not_bound` /
`denied` / `bad_signature` (403), `already_bound` (another device) / `bound_here` (this device) / `replay` (409),
`clock` (400, with server time `now`),
`rate_limit` / `owner_limit` (429).

For `create`, `<id-or-owner-hash>` is the lowercase hex SHA-256 of the raw owner token.

## Running it

Any PHP ≥ 8.1 host with `pdo_sqlite`, `sodium`, `openssl` (and `curl` for Play Integrity):

```
<home>/hh-keys/src/          server.php, integrity.php
<home>/hh-keys/config.php    from config.example.php, random master_key + ip_salt, mode 600
<home>/hh-keys/data/         SQLite database, mode 700
<webroot>/keys/              public/index.php + public/.htaccess (everything is routed to index.php)
```

`index.php` looks for `../../hh-keys` relative to itself, or `HH_KEYS_HOME`. Only `index.php` is reachable from
the web. Back up `config.php` together with the database: without the master key the stored keys cannot be read.

Tests: `php keyserver/tests/run.php` (in-memory database, stubbed Play Integrity) and, against a running server
with Play Integrity off, `python keyserver/tests/e2e.py https://<host>/keys/v1/`.
