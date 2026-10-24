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
# First pass resolves the exact build-tagged dependency graph and populates the module cache.
go-licenses report ./experimental/libbox > "$WORK/libbox-licenses-raw.csv"

# Some sing-box-lx-family modules carry a short GPL notice plus an additional
# naming/association condition rather than the full GPL text. Preserve that
# original notice and append the canonical GPL text in the temporary audit copy
# so the scanner can classify the license correctly. This does not modify the
# repository or upstream sources.
GOMODCACHE="$(go env GOMODCACHE)"
for root in "$WORK/sing-box-lx" "$GOMODCACHE/github.com/sagernet" "$GOMODCACHE/github.com/anytls"; do
  [ -d "$root" ] || continue
  while IFS= read -r -d '' license_file; do
    if grep -q "GNU General Public License" "$license_file" &&
       grep -qE "either version 3|version 3 of the License" "$license_file" &&
       ! grep -q "GNU GENERAL PUBLIC LICENSE" "$license_file"; then
      printf '\n\n' >> "$license_file"
      cat "$ROOT/app/src/main/assets/licenses/GPL-3.0-or-later.txt" >> "$license_file"
    fi
  done < <(find "$root" -type f -iname 'LICENSE*' -print0)
done

# go-nat-pmp has an Apache-2.0 notice in its pinned source but the scanner does
# not recognize that short form. Add the canonical text only to the temporary
# module-cache copy used by the audit.
for module_dir in "$GOMODCACHE"/github.com/jackpal/go-nat-pmp@*; do
  [ -d "$module_dir" ] || continue
  cp "$ROOT/app/src/main/assets/licenses/Apache-2.0.txt" "$module_dir/LICENSE-APACHE.txt"
done

go-licenses report ./experimental/libbox \
  > "$ROOT/app/src/main/assets/licenses/libbox-go-licenses.csv"
cp "$ROOT/app/src/main/assets/licenses/libbox-go-licenses.csv" \
  "$ROOT/app/build/reports/license-audit/libbox-go-licenses.csv"

# Save the license documents and notices required by the classified dependencies.
# go-licenses may copy source trees for reciprocal licenses into this temporary
# directory; only license/notice documents are copied into APK assets.
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
