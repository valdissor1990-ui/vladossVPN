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

# Some upstream modules carry a short GPL-3.0-or-later notice plus additional
# naming/association terms instead of the full license text. Preserve the notice
# and append the canonical text only in this temporary audit checkout/cache.
GOMODCACHE="$(go env GOMODCACHE)"
for root in "$WORK/sing-box-lx" "$GOMODCACHE/github.com/sagernet" "$GOMODCACHE/github.com/anytls"; do
  [ -d "$root" ] || continue
  while IFS= read -r -d '' license_file; do
    if grep -q "GNU General Public License" "$license_file" &&
       grep -qE "either version 3|version 3 of the License" "$license_file" &&
       ! grep -q "GNU GENERAL PUBLIC LICENSE" "$license_file"; then
      chmod u+w "$license_file"
      printf '\n\n' >> "$license_file"
      cat "$ROOT/app/src/main/assets/licenses/GPL-3.0-or-later.txt" >> "$license_file"
    fi
  done < <(find "$root" -type f -iname 'LICENSE*' -print0)
done

# go-nat-pmp has an Apache-2.0 notice in its pinned source but the scanner does
# not recognize that short form; append the canonical text to the temporary copy.
for module_dir in "$GOMODCACHE"/github.com/jackpal/go-nat-pmp@*; do
  [ -d "$module_dir" ] || continue
  for license_file in "$module_dir"/LICENSE*; do
    [ -f "$license_file" ] || continue
    if grep -q "Licensed under the Apache License, Version 2.0" "$license_file" &&
       ! grep -q "Version 2.0, January 2004" "$license_file"; then
      chmod u+w "$license_file"
      printf '\n\n' >> "$license_file"
      cat "$ROOT/app/src/main/assets/licenses/Apache-2.0.txt" >> "$license_file"
    fi
  done
done

go-licenses report ./experimental/libbox \
  > "$ROOT/app/src/main/assets/licenses/libbox-go-licenses.csv"

# Repair source URLs for local submodule replacements and pin them to this release tree.
python3 - "$ROOT/app/src/main/assets/licenses/libbox-go-licenses.csv" "$CORE_VERSION" <<'PY'
import csv
import sys

path, version = sys.argv[1], sys.argv[2]
release_root = f"https://github.com/Leadaxe/sing-box-lx/tree/v{version}"
replacements = {
    "github.com/metacubex/utls": f"{release_root}/submodules/utls",
    "github.com/sagernet/gvisor": f"{release_root}/submodules/gvisor",
    "github.com/sagernet/sing-tun": f"{release_root}/submodules/sing-tun",
    "github.com/sagernet/wireguard-go": f"{release_root}/submodules/wireguard-go",
}
with open(path, newline="", encoding="utf-8") as source:
    rows = list(csv.reader(source))
for row in rows:
    if len(row) < 3:
        continue
    package, url, _license = row[:3]
    if url.startswith("https://github.com/sagernet/sing-box/blob/HEAD/"):
        row[1] = url.replace(
            "https://github.com/sagernet/sing-box/blob/HEAD/",
            f"https://github.com/Leadaxe/sing-box-lx/blob/v{version}/",
            1,
        )
    elif url == "Unknown":
        for prefix, source_url in replacements.items():
            if package == prefix or package.startswith(prefix + "/"):
                row[1] = source_url
                break
with open(path, "w", newline="", encoding="utf-8") as output:
    csv.writer(output, lineterminator="\n").writerows(rows)
PY

if grep -q ',Unknown$' "$ROOT/app/src/main/assets/licenses/libbox-go-licenses.csv"; then
  echo "Native license audit still contains unknown license types:"
  grep ',Unknown$' "$ROOT/app/src/main/assets/licenses/libbox-go-licenses.csv"
  exit 1
fi

cp "$ROOT/app/src/main/assets/licenses/libbox-go-licenses.csv" \
  "$ROOT/app/build/reports/license-audit/libbox-go-licenses.csv"

# Save license and notice documents. go-licenses may also copy reciprocal
# source trees into this temporary directory; only license/notice documents
# are copied into the APK assets.
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
