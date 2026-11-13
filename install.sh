#!/bin/sh
# Installs sjm (SkillsJars Manager) on Linux or macOS:
#
#   curl -fsSL https://raw.githubusercontent.com/skillsjars/skillsjars-cli/main/install.sh | sh
#
# Downloads the latest release for this OS and architecture, checks it against the release's SHA256SUMS, and
# installs it to $SJM_INSTALL_DIR (default ~/.local/bin). Set SJM_VERSION (e.g. 0.1.0) to install a specific release.
set -eu

repo_url=${SJM_RELEASES_URL:-https://github.com/skillsjars/skillsjars-cli}
install_dir=${SJM_INSTALL_DIR:-$HOME/.local/bin}

fail() { echo "sjm install: $*" >&2; exit 1; }

case "$(uname -s)/$(uname -m)" in
  Linux/x86_64 | Linux/amd64) asset=sjm-linux-x64 ;;
  Linux/aarch64 | Linux/arm64) asset=sjm-linux-arm64 ;;
  Darwin/arm64) asset=sjm-macos-arm64 ;;
  *) fail "no sjm release for $(uname -s) $(uname -m)" ;;
esac

if [ "$(uname -s)" = Linux ] && ! ldd --version 2>&1 | grep -qi -e glibc -e "gnu libc"; then
  fail "sjm needs glibc; musl (e.g. Alpine) isn't supported yet"
fi

command -v curl >/dev/null || fail "curl is required"

version=${SJM_VERSION:-}
if [ -z "$version" ]; then
  # The latest release, from the redirect of /releases/latest (no GitHub API rate limit).
  location=$(curl -fsS -o /dev/null -w '%{redirect_url}' "$repo_url/releases/latest")
  version=${location##*/tag/v}
  [ -n "$location" ] && [ "$version" != "$location" ] || fail "can't find the latest release at $repo_url/releases"
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT INT TERM

base="$repo_url/releases/download/v$version"
curl -fsSL -o "$tmp/$asset" "$base/$asset" || fail "can't download $base/$asset"
curl -fsSL -o "$tmp/SHA256SUMS" "$base/SHA256SUMS" || fail "can't download $base/SHA256SUMS"

expected=$(awk -v a="$asset" '{ n = $2; sub(/^\*/, "", n); if (n == a) print $1 }' "$tmp/SHA256SUMS")
[ -n "$expected" ] || fail "SHA256SUMS has no $asset"
if command -v sha256sum >/dev/null; then
  actual=$(sha256sum "$tmp/$asset" | awk '{ print $1 }')
else
  actual=$(shasum -a 256 "$tmp/$asset" | awk '{ print $1 }')
fi
[ "$actual" = "$expected" ] || fail "$asset checksum is $actual, expected $expected"

mkdir -p "$install_dir"
chmod 755 "$tmp/$asset"
mv "$tmp/$asset" "$install_dir/sjm"
echo "Installed sjm $version to $install_dir/sjm"

case ":$PATH:" in
  *":$install_dir:"*) ;;
  *) echo "Add $install_dir to your PATH to run sjm." ;;
esac
