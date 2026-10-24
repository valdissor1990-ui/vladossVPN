#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE_VERSION="1.14.3-lx.21"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

git clone --depth 1 --recurse-submodules --branch "v${CORE_VERSION}" \
  https://github.com/Leadaxe/sing-box-lx "$WORK/sing-box-lx"

mkdir -p "$WORK/bin" "$ROOT/app/src/main/assets/licenses" "$ROOT/app/build/reports/license-audit"
GOBIN="$WORK/bin" go install github.com/google/go-licenses/v2@latest
export PATH="$WORK/bin:$PATH"

# Match the Android libbox tag set from cmd/internal/build_libbox/main.go at the pinned tag.
export GOFLAGS="-tags=with_gvisor,with_quic,with_wireguard,with_utls,with_naive_outbound,badlinkname,tfogo_checklinkname0,with_xhttp,with_awg,with_lx_command,with_lx_chain,with_lx_idle_suspend,with_openvpn,with_openconnect,with_tailscale,ts_omit_logtail,ts_omit_ssh,ts_omit_drive,ts_omit_taildrop,ts_omit_webclient,ts_omit_doctor,ts_omit_capture,ts_omit_kube,ts_omit_aws,ts_omit_synology,ts_omit_bird"

cd "$WORK/sing-box-lx"
go-licenses report ./experimental/libbox \
  > "$ROOT/app/src/main/assets/licenses/libbox-go-licenses.csv"
cp "$ROOT/app/src/main/assets/licenses/libbox-go-licenses.csv" \
  "$ROOT/app/build/reports/license-audit/libbox-go-licenses.csv"

# Collect license and notice files for native dependencies. go-licenses may also
# copy source trees for reciprocal licenses into this temporary directory; only
# license/notice documents are copied into the APK assets. The pinned source tag
# is publicly available and is identified in sing-box-lx-NOTICE.txt.
go-licenses save ./experimental/libbox --save_path="$WORK/go-licenses"
DEST="$ROOT/app/src/main/assets/licenses/libbox"
rm -rf "$DEST"
mkdir -p "$DEST"
(
  cd "$WORK/go-licenses"
  find . -type f \
    \( -iname 'LICENSE*' -o -iname 'COPYING*' -o -iname 'NOTICE*' -o -iname 'COPYRIGHT*' \) \
    -print0 |
  while IFS= read -r -d '' file; do
    target="$DEST/$file"
    mkdir -p "$(dirname "$target")"
    cp "$file" "$target"
  done
)

printf 'Audited sing-box-lx v%s Android libbox Go dependency graph. Report: %s\n' \
  "$CORE_VERSION" "$ROOT/app/src/main/assets/licenses/libbox-go-licenses.csv"
