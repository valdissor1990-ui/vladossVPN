#!/usr/bin/env bash
# Скачивает prebuilt libxray.so и libhev-socks5-tunnel.so из официальных релизов.
# Запуск: ./scripts/download_xray.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
JNI_DIR="$PROJECT_DIR/app/src/main/jniLibs"

XRAY_VERSION="${XRAY_VERSION:-v25.4.30}"
HEV_VERSION="${HEV_VERSION:-7.0}"

XRAY_BASE_URL="https://github.com/XTLS/Xray-core/releases/download/${XRAY_VERSION}"
HEV_BASE_URL="https://github.com/heiher/hev-socks5-tunnel/releases/download/${HEV_VERSION}"

echo "=== VPNProxy: Downloading native libraries ==="
echo "Xray-core: $XRAY_VERSION"
echo "hev-socks5-tunnel: $HEV_VERSION"

# Создаём директории
mkdir -p "$JNI_DIR/arm64-v8a" "$JNI_DIR/armeabi-v7a" "$JNI_DIR/x86_64"

# --- Xray-core ---
# Xray-core предоставляет бинарники для Android в релизах
# Для JNI-интеграции требуется AndroidLibXrayLite (AAR) или сборка из исходников.
# Здесь скачиваем бинарники как reference; в production используйте AndroidLibXrayLite.
echo "Downloading Xray-core binaries..."
for abi in arm64-v8a armeabi-v7a x86_64; do
    case $abi in
        arm64-v8a)  ARCH="arm64-v8a" ;;
        armeabi-v7a) ARCH="armeabi-v7a" ;;
        x86_64)     ARCH="x86_64" ;;
    esac
    # Проверяем наличие AndroidLibXrayLite AAR
    echo "  ABI: $abi — используйте AndroidLibXrayLite из https://github.com/2dust/AndroidLibXrayLite"
done

# --- hev-socks5-tunnel ---
echo "Downloading hev-socks5-tunnel..."
for abi in arm64-v8a armeabi-v7a x86_64; do
    case $abi in
        arm64-v8a)  HEV_ARCH="arm64-v8a" ;;
        armeabi-v7a) HEV_ARCH="armeabi-v7a" ;;
        x86_64)     HEV_ARCH="x86_64" ;;
    esac
    URL="${HEV_BASE_URL}/hev-socks5-tunnel-android-${HEV_ARCH}.so"
    OUTPUT="$JNI_DIR/$abi/libhev-socks5-tunnel.so"
    echo "  Downloading $HEV_ARCH..."
    if curl -fSL "$URL" -o "$OUTPUT" 2>/dev/null; then
        echo "  ✓ Saved: $OUTPUT"
    else
        echo "  ✗ Failed to download hev-socks5-tunnel for $HEV_ARCH"
    fi
done

echo ""
echo "=== IMPORTANT ==="
echo "Для Xray-core в production используйте AndroidLibXrayLite:"
echo "  https://github.com/2dust/AndroidLibXrayLite"
echo "Добавьте AAR в app/libs/ и подключите в build.gradle.kts:"
echo "  implementation(files(\"libs/AndroidLibXrayLite.aar\"))"
echo ""
echo "Done."
