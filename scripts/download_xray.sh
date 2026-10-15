#!/usr/bin/env bash
# Downloads production AARs for VPNProxy.
# geoip.dat / geosite.dat are bundled inside libv2ray.aar assets — no separate download.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
LIBS_DIR="$PROJECT_DIR/app/libs"

XRAY_VERSION="${XRAY_VERSION:-v26.10.10}"
HEV_VERSION="${HEV_VERSION:-2.19.0}"

XRAY_AAR_URL="https://github.com/2dust/AndroidLibXrayLite/releases/download/${XRAY_VERSION}/libv2ray.aar"
HEV_AAR_URL="https://github.com/heiher/hev-socks5-tunnel/releases/download/${HEV_VERSION}/hev-socks5-tunnel.aar"

echo "=== VPNProxy native AARs ==="
echo "libv2ray:           $XRAY_VERSION"
echo "hev-socks5-tunnel:  $HEV_VERSION"

mkdir -p "$LIBS_DIR"

download() {
  local url="$1"
  local out="$2"
  echo "  → $(basename "$out")"
  curl -fSL --retry 3 --retry-delay 2 "$url" -o "$out"
  local size
  size=$(wc -c < "$out" | tr -d ' ')
  if [ "$size" -lt 1000 ]; then
    echo "  ✗ too small ($size bytes): $out"
    rm -f "$out"
    return 1
  fi
  echo "  ✓ $(basename "$out") ($size bytes)"
}

download "$XRAY_AAR_URL" "$LIBS_DIR/libv2ray.aar"
download "$HEV_AAR_URL" "$LIBS_DIR/hev-socks5-tunnel.aar"

ls -lh "$LIBS_DIR"
echo "✅ Native AARs ready"
