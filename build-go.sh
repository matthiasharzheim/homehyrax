#!/usr/bin/env bash
# Baut die WireGuard-Bibliothek (Go -> Android-AAR) nach app/libs/wgbridge.aar.
# Voraussetzungen: Go in der Version aus wgbridge/go.mod (ab Go 1.21 wird sie automatisch geladen),
# Python 3, Android-SDK + NDK (ANDROID_HOME, ANDROID_NDK_HOME).
set -euo pipefail
cd "$(dirname "$0")/wgbridge"
export PATH="$PATH:$(go env GOPATH)/bin"
command -v gomobile >/dev/null || go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20260908204917-8b95e45f8d3e golang.org/x/mobile/cmd/gobind@v0.0.0-20260908204917-8b95e45f8d3e
# Tests teilen globalen Zustand (nicht parallel), Tailscale-Tests brauchen einige Minuten
go test -count=1 -timeout 15m ./...
mkdir -p ../app/libs
# GOMOBILE_TARGETS: z. B. zusaetzlich android/amd64 fuer den Emulator (die APK waehlt per -Pabis)
gomobile bind -target="${GOMOBILE_TARGETS:-android/arm64,android/arm}" -androidapi 26 \
  -javapkg=de.camperflower.homehyrax -ldflags="-s -w" -trimpath -o ../app/libs/wgbridge.aar .
python3 ../tools/licenses.py   # Lizenztexte aller einkompilierten Go-Module -> assets
echo "OK: app/libs/wgbridge.aar"
