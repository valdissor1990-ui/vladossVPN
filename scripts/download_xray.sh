#!/usr/bin/env bash
# VPNProxy — подготовка нативных библиотек.
# Скачивает libv2ray.aar + hev-socks5-tunnel.aar + libbox.aar, удаляет дублирующийся Go runtime
# из hev, проверяет целостность. Падает с exit 1 при любой проблеме.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIBS_DIR="$PROJECT_DIR/app/libs"
ASSETS_DIR="$PROJECT_DIR/app/src/main/assets"

# Exact pinned release verified via GitHub Releases on 2026-10-16: v26.10.10.
LIBV2RAY_URL="https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.10.10/libv2ray.aar"
# Maven Central (verified 200 OK). GitHub release URL returned 404.
HEV_URL="https://repo1.maven.org/maven2/com/zaneschepke/hevtunnel/1.0.1/hevtunnel-1.0.1.aar"
# Official stable libbox release. Its Go bridge classes duplicate those in libv2ray.aar.
LIBBOX_VERSION="1.14.3"
LIBBOX_URL="https://github.com/singbox-android/libbox/releases/download/${LIBBOX_VERSION}/libbox.aar"

mkdir -p "$LIBS_DIR" "$ASSETS_DIR"

echo "=== [1/5] Downloading pinned libv2ray.aar ==="
# Never reuse a stale local/CI cache when changing or validating the native core.
rm -f "$LIBS_DIR/libv2ray.aar"
curl -fSL --retry 3 "$LIBV2RAY_URL" -o "$LIBS_DIR/libv2ray.aar"

echo "=== [2/5] Downloading hev-socks5-tunnel.aar ==="
if [ ! -s "$LIBS_DIR/hev-socks5-tunnel.aar" ]; then
  echo "   URL: $HEV_URL"
  HTTP_CODE=$(curl -sL -w "%{http_code}" -o "$LIBS_DIR/hev-socks5-tunnel.aar" "$HEV_URL")
  if [ "$HTTP_CODE" != "200" ]; then
    echo "❌ Download failed with HTTP $HTTP_CODE"
    echo "   Verify manually: curl -I $HEV_URL"
    rm -f "$LIBS_DIR/hev-socks5-tunnel.aar"
    exit 1
  fi
  echo "   ✅ Downloaded ($(du -h "$LIBS_DIR/hev-socks5-tunnel.aar" | cut -f1))"
fi

echo "=== [3/5] Stripping duplicate Go runtime from hev-socks5-tunnel.aar ==="
# libv2ray.aar и hev-socks5-tunnel.aar оба содержат go.Seq / go.Universe
# → checkDuplicateClasses падает. Удаляем go.* из hev (libv2ray первый в classpath).
HEV_AAR="$LIBS_DIR/hev-socks5-tunnel.aar"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR" "${TMP_LIBBOX_DIR:-}"' EXIT

# AAR — это zip
unzip -q "$HEV_AAR" -d "$TMP_DIR"

if [ -f "$TMP_DIR/classes.jar" ]; then
  mkdir -p "$TMP_DIR/classes_extracted"
  ( cd "$TMP_DIR/classes_extracted" && unzip -q ../classes.jar )

  # Удаляем весь Go runtime
  rm -rf "$TMP_DIR/classes_extracted/go"

  # Перепаковываем classes.jar
  ( cd "$TMP_DIR/classes_extracted" && \
    jar cf ../classes-new.jar . && \
    mv ../classes-new.jar ../classes.jar )

  rm -rf "$TMP_DIR/classes_extracted"

  # Перепаковываем AAR
  ( cd "$TMP_DIR" && zip -qr "$HEV_AAR" . -x 'classes_extracted/*' )

  echo "   ✅ go.* stripped from hev-socks5-tunnel.aar"
else
  echo "   ⚠️  classes.jar not found in hev AAR — skip (expected for Maven Central AAR)"
fi

echo "=== [4/5] Sanity checks ==="
FAIL=0

check_file() {
  local f="$1"
  local name="$2"
  if [ ! -s "$f" ]; then
    echo "❌ Missing or empty: $name ($f)"
    FAIL=1
  else
    echo "   ✅ $name ($(du -h "$f" | cut -f1))"
  fi
}

check_file "$LIBS_DIR/libv2ray.aar" "libv2ray.aar"
check_file "$LIBS_DIR/hev-socks5-tunnel.aar" "hev-socks5-tunnel.aar"

# Проверка, что libv2ray.aar содержит .so под 3 ABI
for abi in arm64-v8a armeabi-v7a x86_64; do
  if unzip -l "$LIBS_DIR/libv2ray.aar" | grep -q "jni/$abi/libv2ray.so"; then
    echo "   ✅ libv2ray.so for $abi"
  else
    echo "   ⚠️  libv2ray.so for $abi NOT found in AAR"
  fi
done

if [ "$FAIL" -eq 1 ]; then
  echo "❌ Sanity checks failed — aborting"
  exit 1
fi

echo "=== [5/5] Downloading and preparing official libbox ${LIBBOX_VERSION} ==="
LIBBOX_AAR="$LIBS_DIR/libbox.aar"
LIBBOX_STRIPPED_AAR="$LIBS_DIR/libbox-stripped.aar"
rm -f "$LIBBOX_AAR" "$LIBBOX_STRIPPED_AAR"
curl -fSL --retry 3 "$LIBBOX_URL" -o "$LIBBOX_AAR"

TMP_LIBBOX_DIR="$(mktemp -d)"
unzip -q "$LIBBOX_AAR" -d "$TMP_LIBBOX_DIR"
if [ ! -f "$TMP_LIBBOX_DIR/classes.jar" ]; then
  echo "❌ libbox AAR does not contain classes.jar"
  exit 1
fi

mkdir -p "$TMP_LIBBOX_DIR/classes_extracted"
( cd "$TMP_LIBBOX_DIR/classes_extracted" && unzip -q ../classes.jar )
# Reuse the Go bridge shipped by libv2ray. Duplicate go.Seq/go.Universe classes
# make AGP fail duplicate-class checks; keep libbox native .so files intact.
rm -rf "$TMP_LIBBOX_DIR/classes_extracted/go"
( cd "$TMP_LIBBOX_DIR/classes_extracted" && jar cf ../classes-new.jar . && mv ../classes-new.jar ../classes.jar )
rm -rf "$TMP_LIBBOX_DIR/classes_extracted"
( cd "$TMP_LIBBOX_DIR" && zip -qr "$LIBBOX_STRIPPED_AAR" . )
rm -f "$LIBBOX_AAR"

if [ ! -s "$LIBBOX_STRIPPED_AAR" ]; then
  echo "❌ Failed to prepare libbox-stripped.aar"
  exit 1
fi
unzip -p "$LIBBOX_STRIPPED_AAR" classes.jar > "$TMP_LIBBOX_DIR/libbox-classes.jar"
if jar tf "$TMP_LIBBOX_DIR/libbox-classes.jar" | grep -q '^go/Seq.class$'; then
  echo "❌ Duplicate Go bridge classes remain in libbox AAR"
  exit 1
fi
echo "   ✅ libbox ${LIBBOX_VERSION} prepared with duplicate Go bridge classes removed"
echo "   ✅ All native libraries ready"
