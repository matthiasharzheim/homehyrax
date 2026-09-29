# HomeHyrax (Android)

**English** · [Deutsch](README.de.md)

Open web interfaces, switches and cameras on your home network (smart home, NAS, router, Shelly, IP camera …)
like separate apps: **directly on your home Wi-Fi, automatically through your own tunnel (WireGuard or Tailscale)
when you are away** – inside the app, without an Android VPN. English and German.

**VPN only where you actually need it.** A phone-wide VPN routes all traffic through your home network and often
gets in the way: Android Auto, navigation and map services (slow, wrong location), banking or streaming apps.
HomeHyrax gives you the same access to your home network with a minimal approach: no system VPN, the tunnel runs
only inside this app, only for the addresses of your tiles and only while they are in use.

## Features

- **Three kinds of tiles**
  - **Website**: full-screen WebView with its own home-screen icon, optional desktop mode
  - **Switch**: sends exactly one command instead of loading a page, e.g. a Shelly pulse for the garage door
    (Gen1 `/relay/N?turn=on&timer=S`, Gen2 `/rpc/Switch.Set?id=N&on=true&toggle_after=S`), then shows the real
    state (on/off); Shelly discovery on the home Wi-Fi (`GET /shelly`), password via Basic or Digest (SHA-256),
    sent only after the device asks for it
  - **Camera**: RTSP live view (media3/ExoPlayer, RTP over TCP), large tile with a fresh snapshot on every app
    start; a pasted link `rtsp://user:pass@…` is split into address and credentials
- **VPN per tile: None / Smart / Always** – Smart = direct at home, tunnel when away
- **No Android VPN**: does not occupy the VPN slot and runs alongside any other VPN; the tunnel only carries the
  targets of the tile, only inside this app and only while it is open (+90 s)
- **WireGuard** (e.g. FRITZ!Box from FRITZ!OS 7.50: QR code, screenshot, `.conf`) and **Tailscale/Headscale**
  (for connections without a public IP, DS-Lite, CGNAT; login in the browser, subnet router)
- WireGuard keys and passwords encrypted with AES-GCM using an Android Keystore key; the Tailscale device key is
  stored by tsnet unencrypted in the app folder, protected by the app sandbox and Android file encryption; no cloud backup
- Optional lock (fingerprint/face/PIN) per tile, switches locked by default
- Built-in symbols or your own image; **share** all entries via QR code or file
- Diagnostics: "Test connection" (for Tailscale with route, device, relay, ping, TCP), crash report on the next
  start (Kotlin + Go, local only), open-source licenses in the app

## Install

A Play Store release is planned. Until then: build it yourself (see [Building](#building)) or take the APK from a
CI run (Actions → Android App → artifact `HomeHyrax-apk`). Without signing secrets this APK is unsigned and has to
be signed with your own key before installing (`apksigner`).

## Setup

Detailed guides (German): **[WireGuard (FRITZ!Box and other routers)](docs/anleitung-wireguard.md)** ·
**[Tailscale (no public IP, e.g. in a camper van)](docs/anleitung-tailscale.md)**. In short:

**WireGuard (FRITZ!Box)**
1. `fritz.box` → Internet → Permit Access → VPN (WireGuard) → **Add connection** → **Connect a single device**
2. In the app: **Tunnel → + Tunnel → WireGuard → Scan QR code** (or screenshot/.conf)
3. **Home → + New** → name, address (e.g. `192.168.178.1`), VPN **Smart** → choose the tunnel

**Tailscale** (no public IP): **+ Tunnel → Tailscale → Connect** (login in the browser); at home, a Tailscale
device acts as subnet router (e.g. NAS, Raspberry Pi) – approve its route in the Tailscale admin console.

**Camera**: copy the RTSP link in the camera's app (e.g. Eufy: Settings → NAS (RTSP), recording set to
"always") and paste it in HomeHyrax under **+ New → Camera**. Battery cameras without a base station usually offer
no local stream.

## Architecture

```
WebView ──(proxy 127.0.0.1:<random>, password per app start)──▶ wgbridge (Go)
Player (RTSP) ──(forward 127.0.0.1:<random>)──────────────────▶   ├─ route/AllowedIPs → WireGuard netstack or tsnet
Switch ──(Wgbridge.request)───────────────────────────────────▶   └─ otherwise direct
```

| Part | File | Purpose |
|---|---|---|
| Go | `wgbridge/bridge.go` | tunnel (wireguard-go + gVisor netstack), routing, handshake state |
| Go | `wgbridge/proxy.go` | local HTTP/CONNECT proxy with authentication, SSE streaming |
| Go | `wgbridge/forward.go` | local port forwarding for RTSP cameras |
| Go | `wgbridge/config.go` | wg-quick parser (FRITZ!Box export), DynDNS resolution, UAPI |
| Go | `wgbridge/request.go` | single request for switches, Basic/Digest auth |
| Go | `wgbridge/tailscale*.go` | Tailscale (tsnet) in the app process, login, diagnostics |
| Kotlin | `Net.kt` | proxy override of the WebViews, home detection, tunnel lifetime |
| Kotlin | `WebAppActivity.kt` / `ActionActivity.kt` / `CameraActivity.kt` | website / switch / camera |
| Kotlin | `MainActivity.kt` | UI: tiles, editor, tunnels, sharing, info |
| Kotlin | `Store.kt` / `Shortcuts.kt` / `Share.kt` | storage (encrypted), home-screen icons, sharing |

**Home detection**: mobile data → tunnel; on Wi-Fi a TCP connection to `host:port` (800 ms), a successful Wi-Fi is
remembered per tile (without SSID/location permission); unknown Wi-Fi → tunnel right away; load error → tunnel.
`0.0.0.0/0` in AllowedIPs is ignored on purpose – otherwise every request would go through the tunnel.

**Proxy authentication**: the WebView authenticates via `onReceivedHttpAuthRequest` (realm `HomeHyrax`). If a
WebView version does not pass this on, the app switches to a fallback mode without password – only on a 407 from
its own proxy and only while a website is visible.

## Building

```bash
export ANDROID_HOME=/path/to/sdk ANDROID_NDK_HOME=$ANDROID_HOME/ndk/28.2.13676358
./build-go.sh                                   # Go tests + gomobile → app/libs/wgbridge.aar
./gradlew lintRelease assembleRelease           # APK (both ABIs)
./gradlew assembleRelease -Pabis=arm64-v8a      # 64-bit only, smaller
```

Signing (all optional, none of it is in the repository):
- **Your own key for self-built APKs**, so new versions install over the existing one: environment
  `SIDELOAD_KEYSTORE` (path), `SIDELOAD_KEYSTORE_PASSWORD`, `SIDELOAD_KEY_ALIAS`, `SIDELOAD_KEY_PASSWORD`, or in
  `local.properties` `sideload.keystore`, `sideload.storePassword`, `sideload.keyAlias`, `sideload.keyPassword`.
  Applies to release and debug.
- **Play Store upload key**: `UPLOAD_KEYSTORE`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`,
  `UPLOAD_KEY_PASSWORD` (takes precedence for release).
- Without any of these: unsigned release APK (`app-release-unsigned.apk`), debug with the default debug key.

`versionCode` = CI run number + offset (default 100; `-PversionOffset=…`, environment or CI repository variable
`VERSION_OFFSET`). The offset must be above every build already distributed, otherwise Android rejects the update.
In CI, the optional secrets `SIDELOAD_KEYSTORE_B64` (base64 of the `.jks`) + `SIDELOAD_KEYSTORE_PASSWORD` sign the
APK; `UPLOAD_KEYSTORE_B64` + passwords additionally build the store bundle (AAB).
Versions are pinned on purpose (AGP 8.13, Kotlin 2.2, Compose BOM 2025.10, compileSdk 36, minSdk 26, NDK r28c).

The Go tests build real counterparts in-process: a second WireGuard peer (handshake, GET, POST, SSE, CONNECT,
proxy authentication, forwarding, Shelly auth) and a Tailscale test control server with DERP (login, subnet route).

## Limitations

- Other apps cannot be redirected without an Android VPN – use a VPN app with split tunneling for that
- Cameras only with a local RTSP stream; cloud-only cameras are not supported
- **Home detection by probing**: a foreign Wi-Fi using the same default network (e.g. another FRITZ!Box with
  `192.168.178.x`) where something happens to answer at the target address is taken for home. Remedy: use your
  own address range at home instead of the router default (e.g. `10.20.30.0/24`), or set the tile to VPN
  **Always**

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) (building, translations, conventions).

## License

Apache-2.0, see `LICENSE` and `NOTICE`. The name and logo "HomeHyrax" are not covered by the license.
WireGuard is a trademark of Jason A. Donenfeld, Tailscale a trademark of Tailscale Inc.
