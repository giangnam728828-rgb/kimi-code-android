#!/bin/bash
# Rebuilds the arm64 rootfs archive that gets baked into the APK.
#
# The archive is too large for git, so it is published as a release asset and
# this script is the only supported way to produce it. Run on an aarch64 Linux
# host (the CI runner is x86_64, so build the archive locally or on a device).
#
#   bash runtime/build-rootfs.sh <dist-dir> <out.tar.gz>
#
# <dist-dir> must contain the built server bundle: main.mjs, dist-web/, native/,
# node_modules/ and package.json — i.e. the `apps/kimi-code` dist output plus
# the production dependencies.
set -euo pipefail

DIST_DIR="${1:?usage: build-rootfs.sh <dist-dir> <out.tar.gz>}"
OUT="${2:?usage: build-rootfs.sh <dist-dir> <out.tar.gz>}"
HERE="$(cd "$(dirname "$0")" && pwd)"

NODE_VERSION="24.19.0"
NODE_TARBALL="node-v${NODE_VERSION}-linux-arm64.tar.xz"
NODE_URL="https://nodejs.org/dist/v${NODE_VERSION}/${NODE_TARBALL}"
RG_VERSION="14.1.1"
RG_URL="https://github.com/BurntSushi/ripgrep/releases/download/${RG_VERSION}/ripgrep-${RG_VERSION}-aarch64-unknown-linux-gnu.tar.gz"
UBUNTU_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
RF="$WORK/rootfs"

echo "==> ubuntu base"
curl -fsSL --retry 3 -o "$WORK/ubuntu.tgz" "$UBUNTU_URL"
mkdir -p "$RF"
tar -xzf "$WORK/ubuntu.tgz" -C "$RF"

echo "==> node ${NODE_VERSION}"
curl -fsSL --retry 3 -o "$WORK/node.tar.xz" "$NODE_URL"
mkdir -p "$RF/opt/node"
tar -xJf "$WORK/node.tar.xz" -C "$RF/opt/node" --strip-components=1
# npm and the bundled docs are not needed at runtime.
rm -rf "$RF/opt/node/include" "$RF/opt/node/share" "$RF/opt/node/lib/node_modules/npm" \
       "$RF/opt/node/bin/npm" "$RF/opt/node/bin/npx" "$RF/opt/node/CHANGELOG.md" \
       "$RF/opt/node/LICENSE" "$RF/opt/node/README.md"

echo "==> kimi code server"
mkdir -p "$RF/opt/kimi"
cp -a "$DIST_DIR"/. "$RF/opt/kimi/"

echo "==> runtime entrypoint"
install -m 0755 "$HERE/entrypoint.sh" "$RF/opt/kimi/entrypoint.sh"

echo "==> ripgrep ${RG_VERSION}"
curl -fsSL --retry 3 -o "$WORK/rg.tgz" "$RG_URL"
tar -xzf "$WORK/rg.tgz" -C "$WORK"
mkdir -p "$RF/root/.kimi-code/bin" "$RF/usr/local/bin"
RG_BIN="$(find "$WORK" -type f -name rg -perm -u+x | head -n1)"
install -m 0755 "$RG_BIN" "$RF/root/.kimi-code/bin/rg"
ln -sf /root/.kimi-code/bin/rg "$RF/usr/local/bin/rg"

echo "==> filesystem essentials"
# proot needs these mount points to exist and DNS to be writable at runtime.
mkdir -p "$RF"/{dev,proc,sys,tmp,root/workspace,run}
chmod 1777 "$RF/tmp"
cat > "$RF/etc/resolv.conf" <<'DNS'
nameserver 8.8.8.8
nameserver 1.1.1.1
DNS
# Shrink: apt lists and docs are dead weight in a container the user cannot apt-get into offline.
rm -rf "$RF"/var/lib/apt/lists/* "$RF"/var/cache/apt/*.bin "$RF"/usr/share/doc/* \
       "$RF"/usr/share/man/* "$RF"/usr/share/info/* 2>/dev/null || true

echo "==> archive"
tar --numeric-owner --hard-dereference --sort=name -czf "$OUT" -C "$RF" .
echo "archive: $(du -h "$OUT" | cut -f1)  ($(tar -tzf "$OUT" | wc -l) entries)"
