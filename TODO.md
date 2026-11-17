# TODO

Open work after the 0.0.1 release. `SPEC.md` has the design for each item.

## Before announcing a release

- [ ] **macOS signing and notarization.** Add a Developer ID Application certificate and notarization credentials
  (`xcrun notarytool`) as secrets in the `release` environment, and sign + notarize in `test.yaml`'s verify job
  before packaging the asset. Until then, a browser download of `sjm-macos-arm64` is blocked by Gatekeeper
  (`install.sh` is fine: curl downloads aren't quarantined).
- [ ] **`codesign --verify` in self-update** on macOS, once releases are signed (`SelfUpdater.replace`).
- [ ] **Protect the `release` environment** (GitHub created it on first use): only `v*` tags, optionally a
  required reviewer.
- [ ] **Test a real self-update** by releasing 0.0.2 and running `sjm update` from 0.0.1 on Linux, macOS, and
  Windows (CI covers it only against a fake release server).
- [ ] **Run the install scripts against the real release** on macOS and Windows (only tested against the fake
  release server there).

## skillsjars.com

- [ ] Add an "Other projects (no Maven/Gradle/sbt)" section to the `/setup` markdown, using `sjm`.
- [ ] Add `sjm` to the web UI's build-tool selector, showing `sjm install <artifactId>`.
- [ ] Optionally serve the install scripts from `https://www.skillsjars.com/install.sh` and `/install.ps1`.

## CLI

- [ ] Print skills dirs relative to the current directory instead of as absolute paths.
- [ ] Decide whether `sjm outdated` should exit non-zero when something is outdated (like `npm outdated`), for
  scripts and CI.
- [ ] `sjm update` with no manifest prints nothing about SkillsJars unless `-v`; consider a short hint.
- [ ] Transitive dependencies: sjm only warns about POM dependencies. Resolve and install them if third-party
  SkillsJars start using them.
- [ ] An update-check failure (no network) is cached as "no release" for 24 hours; cache only real answers.

## Distribution

- [ ] Windows Authenticode signing (after UPX packing) to avoid SmartScreen warnings on browser downloads.
- [ ] Homebrew tap and Scoop/winget manifests (self-update already defers to them).
- [ ] Alpine (musl) binaries.
- [ ] macOS x64 and Windows arm64, if needed. GitHub's last x64 macOS runner (`macos-15-intel`) retires in
  August 2027, so macOS x64 would need cross-compiling.

## Maintenance

- [ ] Refresh `certs/cacert.pem` regularly (steps in `AGENTS.md`).
- [ ] Recheck the Ktor curl engine's shutdown race on Ktor upgrades; sjm exits with `_exit` to avoid it.
- [ ] Drop the `gradle/gradle-daemon-jvm.properties` pin if NixOS's OpenJDK stops crashing the Kotlin/Native
  compiler.
