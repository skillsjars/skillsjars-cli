#!/usr/bin/env bash
# Runs the Linux binary in clean distro containers, with no extra packages installed.
# Usage: containers.sh <binary> <images...>
#
# Each image must install a SkillsJar. Bare Debian and Ubuntu images have no CA store, so this also covers the CA
# bundle compiled into sjm. A bad SSL_CERT_FILE must fail with exit 2 and a hint.
set -euo pipefail

binary=$(realpath "$1")
shift

for image in "$@"; do
  echo "::group::$image"
  run() { docker run --rm -e SJM_NO_UPDATE_CHECK=1 -v "$binary:/usr/local/bin/sjm:ro" -w /tmp "$image" sh -c "$1"; }
  run 'sjm install com.jamesward:skills && test -f .agents/skills/skillsjars__jamesward__skills__zen-of-james/SKILL.md'
  set +e
  out=$(run 'SSL_CERT_FILE=/nonexistent.pem sjm install com.jamesward:skills' 2>&1)
  code=$?
  set -e
  echo "$out"
  [ "$code" -eq 2 ] && grep -q "SSL_CERT_FILE" <<<"$out" || { echo "expected exit 2 with an SSL_CERT_FILE hint, got $code"; exit 1; }
  echo "::endgroup::"
done
