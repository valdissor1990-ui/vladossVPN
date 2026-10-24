#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE_VERSION="1.14.3-lx.21"
CRONET_GO_COMMIT="0d28acc44093df24b2526dea3d6ffefd6b0a54f0"
CRONET_LIB_COMMIT="c10c03c318db6d47bec8705c8290bad34bc32b64"
NAIVEPROXY_COMMIT="72a06c9fca0e2d228588c7f3074bf7efff3ff686"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
ARCHES="arm64 arm 386 amd64"

git clone --depth 1 --recurse-submodules --branch "v${CORE_VERSION}" \
  https://github.com/Leadaxe/sing-box-lx "$WORK/sing-box-lx"

mkdir -p "$WORK/bin" "$ROOT/app/src/main/assets/licenses" "$ROOT/app/build/reports/license-audit"
GOBIN="$WORK/bin" go install github.com/google/go-licenses/v2@v2.0.1
export PATH="$WORK/bin:$PATH"

# Match the Android libbox tag set from cmd/internal/build_libbox/main.go at the pinned tag.
export GOFLAGS="-tags=with_gvisor,with_quic,with_wireguard,with_utls,with_naive_outbound,badlinkname,tfogo_checklinkname0,with_xhttp,with_awg,with_lx_command,with_lx_chain,with_lx_idle_suspend,with_openvpn,with_openconnect,with_tailscale,ts_omit_logtail,ts_omit_ssh,ts_omit_drive,ts_omit_taildrop,ts_omit_webclient,ts_omit_doctor,ts_omit_capture,ts_omit_kube,ts_omit_aws,ts_omit_synology,ts_omit_bird"

cd "$WORK/sing-box-lx"
# Fail if the upstream core tag changes the Cronet wrapper/binary pins without a notice review.
grep -Fq "github.com/sagernet/cronet-go v0.0.0-20260912104727-0d28acc44093" go.mod
grep -Fq "github.com/sagernet/cronet-go/lib/android_arm64 v0.0.0-20260912104006-c10c03c318db" go.mod

# Resolve package graphs for all Android ABIs supported by gomobile. Do not use
# the Linux runner's default GOOS/GOARCH: it would report Linux-only packages.
for arch in $ARCHES; do
  echo "Resolving Android dependency graph for GOARCH=$arch"
  CGO_ENABLED=1 GOOS=android GOARCH="$arch" go-licenses report ./experimental/libbox \
    > "$WORK/raw-$arch.csv"
done

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

# Re-scan after adding canonical license text to temporary copies, so the final
# inventory uses classified license types rather than the short-form notices.
for arch in $ARCHES; do
  CGO_ENABLED=1 GOOS=android GOARCH="$arch" go-licenses report ./experimental/libbox \
    > "$WORK/report-$arch.csv"
done

# Build a union of license rows across Android ABIs, preserving different license
# options while removing duplicate rows. All source URLs are pinned to exact refs.
python3 - "$WORK" "$ROOT/app/src/main/assets/licenses/libbox-go-licenses.csv" "$CORE_VERSION" "$CRONET_LIB_COMMIT" <<'PY'
import csv
import os
import sys

work, output_path, version, cronet_lib_commit = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
release_root = f"https://github.com/Leadaxe/sing-box-lx/tree/v{version}"
replacements = {
    "github.com/metacubex/utls": f"{release_root}/submodules/utls",
    "github.com/sagernet/gvisor": f"{release_root}/submodules/gvisor",
    "github.com/sagernet/sing-tun": f"{release_root}/submodules/sing-tun",
    "github.com/sagernet/wireguard-go": f"{release_root}/submodules/wireguard-go",
}
rows = []
seen = set()
for arch in ("arm64", "arm", "386", "amd64"):
    with open(os.path.join(work, f"report-{arch}.csv"), newline="", encoding="utf-8") as source:
        for row in csv.reader(source):
            if len(row) < 3:
                continue
            package, url, license_name = row[:3]
            if license_name == "GPL-3.0":
                license_name = "GPL-3.0-or-later"
            if package.startswith("github.com/sagernet/cronet-go/lib/android_"):
                url = f"https://github.com/SagerNet/cronet-go/blob/{cronet_lib_commit}/LICENSE"
            elif url.startswith("https://github.com/sagernet/sing-box/blob/HEAD/"):
                url = url.replace(
                    "https://github.com/sagernet/sing-box/blob/HEAD/",
                    f"https://github.com/Leadaxe/sing-box-lx/blob/v{version}/",
                    1,
                )
            elif url == "Unknown":
                for prefix, source_url in replacements.items():
                    if package == prefix or package.startswith(prefix + "/"):
                        url = source_url
                        break
            row = [package, url, license_name]
            key = tuple(row)
            if key not in seen:
                seen.add(key)
                rows.append(row)

unknown_types = [row for row in rows if row[2] == "Unknown"]
unknown_urls = [row for row in rows if row[1] == "Unknown"]
if unknown_types or unknown_urls:
    print("Unresolved native license entries:")
    for row in unknown_types + unknown_urls:
        print(",".join(row))
    sys.exit(1)

with open(output_path, "w", newline="", encoding="utf-8") as output:
    csv.writer(output, lineterminator="\n").writerows(rows)
PY

cp "$ROOT/app/src/main/assets/licenses/libbox-go-licenses.csv" \
  "$ROOT/app/build/reports/license-audit/libbox-go-licenses.csv"

# The with_naive_outbound tag also bundles prebuilt Chromium/NaiveProxy libcronet.a
# archives. Their binary licenses and third-party notices are separate from the
# cronet-go Go wrapper license, so publish a distinct inventory.
cat > "$ROOT/app/src/main/assets/licenses/cronet-native-licenses.csv" <<EOF
component,source,primary_license,third_party_notices
libcronet.a (android/arm64),https://github.com/SagerNet/naiveproxy/tree/$NAIVEPROXY_COMMIT,BSD-3-Clause,licenses/libbox/cronet-naiveproxy/
libcronet.a (android/arm),https://github.com/SagerNet/naiveproxy/tree/$NAIVEPROXY_COMMIT,BSD-3-Clause,licenses/libbox/cronet-naiveproxy/
libcronet.a (android/386),https://github.com/SagerNet/naiveproxy/tree/$NAIVEPROXY_COMMIT,BSD-3-Clause,licenses/libbox/cronet-naiveproxy/
libcronet.a (android/amd64),https://github.com/SagerNet/naiveproxy/tree/$NAIVEPROXY_COMMIT,BSD-3-Clause,licenses/libbox/cronet-naiveproxy/
EOF
cp "$ROOT/app/src/main/assets/licenses/cronet-native-licenses.csv" \
  "$ROOT/app/build/reports/license-audit/cronet-native-licenses.csv"

# Check and collect license/notice files for each target ABI. go-licenses may
# copy reciprocal source trees into temp; only license/notice documents are
# included in APK assets.
DEST="$ROOT/app/src/main/assets/licenses/libbox"
rm -rf "$DEST"
mkdir -p "$DEST"
for arch in $ARCHES; do
  echo "Checking Android license graph for GOARCH=$arch"
  CGO_ENABLED=1 GOOS=android GOARCH="$arch" go-licenses check ./experimental/libbox
  CGO_ENABLED=1 GOOS=android GOARCH="$arch" go-licenses save ./experimental/libbox --save_path="$WORK/go-licenses-$arch"
  (
    cd "$WORK/go-licenses-$arch"
    find . -type f \
      \( -iname 'LICENSE*' -o -iname 'COPYING*' -o -iname 'NOTICE*' -o -iname 'COPYRIGHT*' \) \
      -print0 |
    while IFS= read -r -d '' file; do
      target="$DEST/$file"
      mkdir -p "$(dirname "$target")"
      cp -f "$file" "$target"
      chmod u+w "$target"
    done
  )
done

# Collect Chromium/NaiveProxy's own and third-party notices for the exact
# submodule commit that produced the bundled libcronet.a archives. Sparse checkout
# avoids downloading the full Chromium source tree while preserving license paths.
NAIVE_WORK="$WORK/naiveproxy"
git init "$NAIVE_WORK"
git -C "$NAIVE_WORK" remote add origin https://github.com/SagerNet/naiveproxy.git
git -C "$NAIVE_WORK" sparse-checkout init --no-cone
cat > "$NAIVE_WORK/.git/info/sparse-checkout" <<'EOF'
/LICENSE
/src/LICENSE
/src/**/LICENSE*
/src/**/NOTICE*
/src/**/COPYING*
/src/**/COPYRIGHT*
/src/**/CREDITS*
/src/**/AUTHORS*
/src/**/PATENTS
/src/**/README.chromium*
EOF
git -C "$NAIVE_WORK" fetch --depth=1 --filter=blob:none origin "$NAIVEPROXY_COMMIT"
git -C "$NAIVE_WORK" checkout --detach FETCH_HEAD

CRONET_DEST="$DEST/cronet-naiveproxy"
mkdir -p "$CRONET_DEST"
find "$NAIVE_WORK" -type f \
  \( -iname 'LICENSE*' -o -iname 'NOTICE*' -o -iname 'COPYING*' -o -iname 'COPYRIGHT*' -o -iname 'CREDITS*' -o -iname 'AUTHORS*' -o -iname 'PATENTS' -o -iname 'README.chromium*' \) \
  ! -path "$NAIVE_WORK/.git/*" -print0 |
while IFS= read -r -d '' file; do
  rel="${file#"$NAIVE_WORK"/}"
  target="$CRONET_DEST/$rel"
  mkdir -p "$(dirname "$target")"
  cp -f "$file" "$target"
  chmod u+w "$target"
done
cp "$ROOT/app/src/main/assets/licenses/cronet-native-NOTICE.txt" \
  "$CRONET_DEST/CRONET-NATIVE-NOTICE.txt"

printf 'Audited sing-box-lx v%s for Android ABIs (%s), plus Cronet/NaiveProxy notices at %s. Report: %s\n' \
  "$CORE_VERSION" "$ARCHES" "$NAIVEPROXY_COMMIT" "$ROOT/app/src/main/assets/licenses/libbox-go-licenses.csv"
