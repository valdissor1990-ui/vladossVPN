#!/usr/bin/env bash
# Download and verify the full sing-box-lx libbox AAR with XHTTP support.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIBS_DIR="$PROJECT_DIR/app/libs"
LIBBOX_VERSION="1.14.3-lx.21"
LIBBOX_SHA256="5a6c782b834d53aa8031e32a953eb9517e78b742bbc10d3474154fc05aa00e0e"
LIBBOX_URL="https://github.com/Leadaxe/sing-box-lx/releases/download/v${LIBBOX_VERSION}/libbox-${LIBBOX_VERSION}.aar"
LIBBOX_AAR="$LIBS_DIR/libbox.aar"

mkdir -p "$LIBS_DIR"
rm -f "$LIBS_DIR/libbox-stripped.aar" "$LIBS_DIR/libv2ray.aar" "$LIBS_DIR/hev-socks5-tunnel.aar"
curl -fSL --retry 3 "$LIBBOX_URL" -o "$LIBBOX_AAR"
test -s "$LIBBOX_AAR"
printf '%s  %s\n' "$LIBBOX_SHA256" "$LIBBOX_AAR" | sha256sum --check --status
unzip -t "$LIBBOX_AAR" >/dev/null

TMP_CLASSES="$(mktemp)"
trap 'rm -f "$TMP_CLASSES"' EXIT
unzip -p "$LIBBOX_AAR" classes.jar > "$TMP_CLASSES"
jar tf "$TMP_CLASSES" | grep -q '^io/nekohasekai/libbox/PlatformInterface.class$'
jar tf "$TMP_CLASSES" | grep -q '^go/Seq.class$'
unzip -l "$LIBBOX_AAR" | grep -q 'jni/arm64-v8a/libbox.so'

echo "sing-box-lx ${LIBBOX_VERSION}: full AAR verified (SHA-256, XHTTP release, PlatformInterface, Go bridge, libbox.so)"
