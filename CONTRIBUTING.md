# Contributing to HomeHyrax

Thanks for your interest! Issues and pull requests are welcome. For larger changes, please open an issue first.

## Build and check

Requirements: Go (version from `wgbridge/go.mod`), Python 3, JDK 17, Android SDK (platform 36) and NDK r28c
(`ANDROID_HOME`, `ANDROID_NDK_HOME`).

```bash
./build-go.sh                           # Go tests + gomobile → app/libs/wgbridge.aar (+ Go license list)
./gradlew lintRelease assembleRelease   # Android build incl. lint
python3 tools/check_strings.py          # translations complete and consistent
```

All three must pass before a pull request; CI runs the same steps. The Go tests start a real WireGuard peer and a
Tailscale test control server in-process, no network access to outside services is needed.

Generated files – do not edit by hand:
- `app/src/main/assets/licenses_go.txt` (from `tools/licenses.py`, run by `build-go.sh`)
- `app/src/main/res/drawable/ic_launcher_*.xml` and `Logo.kt` (from `tools/icon.py`)

## Conventions

- **UI texts only as string resources, in English and German**: English in `res/values/strings_*.xml`
  (default), German in `res/values-de/strings_*.xml`. `strings_main.xml` belongs to `MainActivity`,
  `strings_misc.xml` to everything else. Compose: `stringResource()`, otherwise `getString()`.
  `tools/check_strings.py` checks that both languages have the same keys and placeholders. Keep texts short.
- **Code comments** in Kotlin and Go are German, written without umlauts (`ae`, `oe`, `ue`, `ss`). Explain why,
  not the history of a change.
- **Go messages are English.** Messages that reach the user are translated in `Net.goMsg()` by their exact text;
  diagnostic texts from the Tailscale probe stay English and are matched in `Net.testTailscale()` – when changing
  such texts, update both sides and the tests.
- **Example addresses** in code, tests and docs: `192.168.178.x` (FRITZ!Box default), host names like
  `example.net`. No real addresses, keys or device names.
- Pinned versions (AGP, Kotlin, Compose BOM, NDK) are intentional; bump them only together and with a reason.

## License

By contributing you agree that your contributions are licensed under the Apache License 2.0 (see `LICENSE`).
