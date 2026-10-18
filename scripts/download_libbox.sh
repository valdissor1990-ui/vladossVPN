#!/usr/bin/env bash
# Prepare the full official libbox AAR for the Android app.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIBS_DIR="$PROJECT_DIR/app/libs"
LIBBOX_VERSION="1.14.3"
LIBBOX_URL="https://github.com/singbox-android/libbox/releases/download/${LIBBOX_VERSION}/libbox.aar"
LIBBOX_AAR="$LIBS_DIR/libbox.aar"

mkdir -p "$LIBS_DIR"
rm -f "$LIBS_DIR/libbox-stripped.aar" "$LIBS_DIR/libv2ray.aar" "$LIBS_DIR/hev-socks5-tunnel.aar"
curl -fSL --retry 3 "$LIBBOX_URL" -o "$LIBBOX_AAR"
test -s "$LIBBOX_AAR"
unzip -t "$LIBBOX_AAR" >/dev/null
TMP_CLASSES="$(mktemp)"
trap 'rm -f "$TMP_CLASSES"' EXIT
unzip -p "$LIBBOX_AAR" classes.jar > "$TMP_CLASSES"
jar tf "$TMP_CLASSES" | grep -q '^io/nekohasekai/libbox/PlatformInterface.class$'
jar tf "$TMP_CLASSES" | grep -q '^go/Seq.class$'
echo "libbox ${LIBBOX_VERSION}: full AAR verified (PlatformInterface + Go bridge present)"
