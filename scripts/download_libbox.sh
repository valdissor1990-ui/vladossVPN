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
TMP_CLASS_LIST="$(mktemp)"
TMP_AAR_LIST="$(mktemp)"
trap 'rm -f "$TMP_CLASSES" "$TMP_CLASS_LIST" "$TMP_AAR_LIST"' EXIT
unzip -p "$LIBBOX_AAR" classes.jar > "$TMP_CLASSES"
jar tf "$TMP_CLASSES" > "$TMP_CLASS_LIST"
grep -F -x 'io/nekohasekai/libbox/PlatformInterface.class' "$TMP_CLASS_LIST" >/dev/null
grep -F -x 'go/Seq.class' "$TMP_CLASS_LIST" >/dev/null
unzip -Z1 "$LIBBOX_AAR" > "$TMP_AAR_LIST"
grep -F -x 'jni/arm64-v8a/libbox.so' "$TMP_AAR_LIST" >/dev/null

echo "sing-box-lx ${LIBBOX_VERSION}: full AAR verified (SHA-256, XHTTP release, PlatformInterface, Go bridge, libbox.so)"
