#!/usr/bin/env python3
"""Smoke-test and measure an sjm binary on the current runner.

Usage: smoke.py <binary> <label> [--upx <upx>] [--smoke-only]

- runs `--version` and a real `install` into a temp dir, checking the SKILL.md files land in .agents/skills
- fails if the binary links anything outside the OS's own libraries
- with --smoke-only, only the smoke test (for the packed release asset, whose libraries ldd can't read)
- records raw size, UPX sizes (when --upx is given) and the startup time of each, and appends a markdown
  table to $GITHUB_STEP_SUMMARY (or prints it)

Uses only the standard library so it runs on every runner's system Python.
"""

import os
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import time

SKILLSJAR = "com.jamesward:skills"


def run(cmd, **kw):
    print("+", " ".join(cmd), flush=True)
    result = subprocess.run(cmd, text=True, capture_output=True, **kw)
    if result.returncode != 0:
        sys.exit(f"exit {result.returncode}\nstdout:\n{result.stdout}\nstderr:\n{result.stderr}")
    return result


def startup_ms(binary, runs=20):
    run([binary, "--version"])  # warm the page cache
    start = time.perf_counter()
    for _ in range(runs):
        subprocess.run([binary, "--version"], check=True, capture_output=True)
    return (time.perf_counter() - start) * 1000 / runs


def dynamic_libs(binary):
    system = platform.system()
    if system == "Linux":
        out = run(["ldd", binary]).stdout
        return [line.split()[0] for line in out.splitlines() if line.strip()]
    if system == "Darwin":
        out = run(["otool", "-L", binary]).stdout
        return [line.split()[0] for line in out.splitlines()[1:] if line.strip()]
    out = run(["ldd", binary]).stdout  # Git for Windows' ldd
    return [line.split("=>")[1].split("(")[0].strip() for line in out.splitlines() if "=>" in line]


# glibc, libgcc_s, and zlib: present on every glibc distro.
LINUX_ALLOWED = re.compile(r"^(linux-vdso\.so\.1|ld-linux.*\.so\.\d+|lib(c|m|dl|rt|pthread|gcc_s|z)\.so\.\d+)$")


def check_libs(binary):
    libs = dynamic_libs(binary)
    system = platform.system()
    if system == "Linux":
        bad = [lib for lib in libs if not LINUX_ALLOWED.match(os.path.basename(lib))]
    elif system == "Darwin":
        bad = [lib for lib in libs if not (lib.startswith("/usr/lib/") or lib.startswith("/System/Library/"))]
    else:
        bad = [lib for lib in libs if not lib.lower().startswith("/c/windows/")]
    print("dynamic libraries:", *libs, sep="\n  ")
    if bad:
        sys.exit(f"{binary} links non-OS libraries: {bad}")


def smoke(binary):
    print(run([binary, "--version"]).stdout.strip())
    with tempfile.TemporaryDirectory() as project:
        env = dict(os.environ, SJM_NO_UPDATE_CHECK="1")
        print(run([binary, "install", SKILLSJAR], cwd=project, env=env).stdout)
        skills = os.path.join(project, ".agents", "skills")
        found = [d for d in os.listdir(skills) if os.path.isfile(os.path.join(skills, d, "SKILL.md"))]
        if not found or not all(d.startswith("skillsjars__") for d in found):
            sys.exit(f"install didn't extract skills: {os.listdir(skills)}")
        if not os.path.isfile(os.path.join(project, "skillsjars.json")):
            sys.exit("install didn't write skillsjars.json")
        # Repeat the sync: a crash at process exit (seen once, in OpenSSL's exit handler) shows up only sometimes.
        for _ in range(10):
            run([binary, "install"], cwd=project, env=env)


def main():
    binary, label = os.path.abspath(sys.argv[1]), sys.argv[2]
    upx = sys.argv[sys.argv.index("--upx") + 1] if "--upx" in sys.argv else None

    smoke(binary)
    if "--smoke-only" in sys.argv:
        return
    check_libs(binary)

    rows = [("raw", os.path.getsize(binary), startup_ms(binary))]
    if upx:
        for mode in ("--best", "--lzma"):
            packed = binary + mode.replace("--", ".upx-")
            shutil.copy(binary, packed)
            run([upx, "-q", mode, packed])
            smoke(packed)
            rows.append((f"upx {mode}", os.path.getsize(packed), startup_ms(packed)))

    table = [f"### {label}", "", "| build | bytes | MB | `--version` ms |", "|---|---:|---:|---:|"]
    table += [f"| {name} | {size} | {size / 1e6:.2f} | {ms:.1f} |" for name, size, ms in rows]
    text = "\n".join(table) + "\n\n"
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write(text)


if __name__ == "__main__":
    main()
