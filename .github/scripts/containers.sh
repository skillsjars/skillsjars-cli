#!/usr/bin/env bash
# Runs the Linux binary in clean distro containers.
# Usage: containers.sh <binary> <images...>
#
# Images with a CA store (Fedora, Arch) must install with no extra packages. Debian and Ubuntu images ship without
# CA certificates: there sjm must fail with exit 2 and say how to fix it, then work once ca-certificates is installed.
set -euo pipefail

binary=$(realpath "$1")
shift

for image in "$@"; do
  echo "::group::$image"
  run() { docker run --rm -e SJM_NO_UPDATE_CHECK=1 -v "$binary:/usr/local/bin/sjm:ro" -w /tmp "$image" sh -c "$1"; }
  case "$image" in
    debian*|ubuntu*)
      set +e
      out=$(run 'sjm install com.jamesward:skills' 2>&1)
      code=$?
      set -e
      echo "$out"
      [ "$code" -eq 2 ] && grep -q "ca-certificates" <<<"$out" || { echo "expected exit 2 with a ca-certificates hint, got $code"; exit 1; }
      run 'apt-get update -qq >/dev/null && apt-get install -y -qq ca-certificates >/dev/null && sjm install com.jamesward:skills && test -f .agents/skills/skillsjars__jamesward__skills__zen-of-james/SKILL.md'
      ;;
    *)
      run 'sjm install com.jamesward:skills && test -f .agents/skills/skillsjars__jamesward__skills__zen-of-james/SKILL.md'
      ;;
  esac
  echo "::endgroup::"
done
