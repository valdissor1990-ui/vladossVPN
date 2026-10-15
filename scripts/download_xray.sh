#!/usr/bin/env bash
# Downloads production native AARs used by VPNProxy:
#   - AndroidLibXrayLite (libv2ray.aar) — Xray-core via gomobile
#   - hev-socks5-tunnel.aar — tun2socks JNI bridge
# Run from CI or locally: ./scripts/download_xray.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
LIBS_DIR="$PROJECT_DIR/app/libs"
JNI_DIR="$PROJECT_DIR/app/src/main/jniLibs"

XRAY_VERSION="${XRAY_VERSION:-v26.10.10}"
HEV_VERSION="${HEV_VERSION:-2.19.0}"

XRAY_AAR_URL="https://github.com/2dust/AndroidLibXrayLite/releases/download/${XRAY_VERSION}/libv2ray.aar"
HEV_AAR_URL="https://github.com/heiher/hev-socks5-tunnel/releases/download/${HEV_VERSION}/hev-socks5-tunnel.aar"

echo "=== VPNProxy: Downloading native libraries ==="
echo "AndroidLibXrayLite: $XRAY_VERSION"
echo "hev-socks5-tunnel:  $HEV_VERSION"

mkdir -p "$LIBS_DIR"
mkdir -p "$JNI_DIR/arm64-v8a" "$JNI_DIR/armeabi-v7a" "$JNI_DIR/x86_64" "$JNI_DIR/x86"

download() {
  local url="$1"
  local out="$2"
  echo "  → $out"
  if curl -fSL --retry 3 --retry-delay 2 "$url" -o "$out"; then
    local size
    size=$(wc -c < "$out" | tr -d ' ')
    if [ "$size" -lt 1000 ]; then
      echo "  ✗ File too small ($size bytes) — bad download"
      rm -f "$out"
      return 1
    fi
    echo "  ✓ $(basename "$out") ($size bytes)"
    return 0
  fi
  echo "  ✗ Failed: $url"
  return 1
}

# --- Xray (AAR contains jni/<abi>/libgojni.so + Java bindings) ---
echo "Downloading libv2ray.aar..."
download "$XRAY_AAR_URL" "$LIBS_DIR/libv2ray.aar"

# --- hev-socks5-tunnel AAR (preferred: includes TProxyService + .so for all ABIs) ---
echo "Downloading hev-socks5-tunnel.aar..."
if ! download "$HEV_AAR_URL" "$LIBS_DIR/hev-socks5-tunnel.aar"; then
  echo "AAR unavailable — falling back to per-ABI shared libraries"
  for abi in arm64-v8a armeabi-v7a x86_64 x86; do
    url="https://github.com/heiher/hev-socks5-tunnel/releases/download/${HEV_VERSION}/hev-socks5-tunnel-android-${abi}"
    out="$JNI_DIR/$abi/libhev-socks5-tunnel.so"
    download "$url" "$out" || true
  done
fi

echo ""
echo "=== Done ==="
ls -lh "$LIBS_DIR" || true
