# sjm (SkillsJars Manager) Spec

Repository: https://github.com/skillsjars/skillsjars-cli (releases are published there)

## Purpose

SkillsJars are [Agent Skills](https://agentskills.io/) packaged as JARs on Maven Central (https://www.skillsjars.com).
Projects built with Maven, Gradle, or sbt use the SkillsJars build plugins to pull skills in and extract them
where AI agents read them. `sjm` gives every other project (Python, Node, Go, Rust, docs repos, dotfiles, ...)
the same capability as a single native binary with no JVM or build tool required.

`sjm` can:
- search the SkillsJars catalog
- install SkillsJars into a project and extract them for agents
- update itself and the installed SkillsJars with one command (`sjm update`)
- notify the user of new `sjm` releases

Non-goals for v1:
- packaging or publishing SkillsJars (publishing happens on skillsjars.com from a GitHub repo)
- full Maven dependency resolution (see [Transitive dependencies](#transitive-dependencies))
- replacing the build plugins in Maven/Gradle/sbt projects

## What the build plugins do (parity baseline)

Researched from `skillsjars-maven-plugin` (0.0.8), `skillsjars-gradle-plugin`, `skillsjars-sbt-plugin`, and the
`skillsjars` webapp.

### Extraction (the part sjm replicates)

| Behavior | Maven | Gradle | sbt | sjm |
|---|---|---|---|---|
| Where deps are declared | plugin `<dependencies>` or project deps | `skill` configuration | `% Skills` configuration | `skillsjars.json` manifest |
| Any groupId accepted | yes | yes | yes | yes |
| Transitive deps | yes (classpath) | yes | yes | no in v1, warn |
| Skill content prefixes | `META-INF/skills/`, `META-INF/resources/skills/` | same | same | same |
| Skill root | any dir containing `SKILL.md` | same | same | same |
| Files outside a skill root | ignored | ignored | ignored (warns) | ignored |
| Output dir name | `skillsjars__<path with / → __>` | same | same | same |
| Output dir param | `-Ddir` (required) | `outputDir` / `-PoutputDir` / `-Pdir` | `skillsJarsOutputDir` / task arg | manifest `dirs` / `--dir` |
| Cleanup before writing | deletes each extracted skill dir | replaces all `skillsjars__*` dirs, preserves others | deletes the whole output dir | replaces all `skillsjars__*` dirs, preserves others |
| On failure | partial | output dir left unchanged | partial | output dir left unchanged |
| Path collision across jars | error | error | error | error |
| Same artifact, two versions | n/a (Maven picks one) | Gradle picks one | highest wins, warns | impossible (manifest keys are unique) |
| `useSkillsNameAsDirectory` (dir = last path segment) | yes | no | no | no in v1 |

Example: `META-INF/skills/anthropics/skills/pdf/SKILL.md` in
`com.skillsjars:anthropics__skills__pdf:2026_02_06-1ed29a0` extracts to `<dir>/skillsjars__anthropics__skills__pdf/SKILL.md`.

`sjm` follows the Gradle plugin's semantics because they are the safest: only `skillsjars__*` folders are owned
by the tool, locally maintained skills in the same directory are never touched, and a failed run changes nothing.

### Packaging (not in sjm v1)

The plugins also package a project's local `skills/<name>/SKILL.md` directories into
`META-INF/skills/<org>/<repo>/<name>/` and validate that any `allowed-tools` frontmatter matches a
`skillsjars.skill.<name>.allowed-tools` POM property. sjm does not need this: non-JVM projects publish through
skillsjars.com.

### SkillsJar coordinates

- groupId `com.skillsjars` for skills deployed through skillsjars.com. The catalog also lists other groups
  (currently `com.jamesward:skills` and `org.springaicommunity:spring-testing-skills`), so sjm accepts any groupId.
- artifactId `<org>__<repo>__<path...>` (e.g. `anthropics__skills__pdf`).
- version `YYYY_MM_DD-<short sha>` for `com.skillsjars` (sorts lexically); other groups use their own schemes.
- The POM carries `name`, `description`, licenses, and optional `skillsjars.skill.<name>.allowed-tools` properties.
- Generated `com.skillsjars` POMs declare no dependencies.

## CLI

Binary name: `sjm` (`sjm.exe` on Windows).

### Coordinates on the command line

A SkillsJar can be given as:
- `artifactId`: groupId defaults to `com.skillsjars` (e.g. `anthropics__skills__pdf`)
- `groupId:artifactId` (e.g. `com.jamesward:skills`)
- either form with `:version` or `@version` appended to pin a version; otherwise the latest release is used

### Commands

```
sjm search <query>                 Search the catalog (name/description, case-insensitive)
sjm install [<skillsjar>...]       Add SkillsJars to the manifest, then sync
sjm install                        No args: sync the project to the manifest (like `npm ci`)
sjm remove <skillsjar>...          Remove SkillsJars from the manifest, then sync
sjm list                           Show installed SkillsJars and their versions
sjm outdated                       Show installed SkillsJars with newer versions available
sjm update [<skillsjar>...]        Update sjm itself, then bump SkillsJars to their latest versions
                                   (all if none given) and sync
sjm --version | --help
```

Global options:
- `--dir <path>`: extraction directory. Repeatable. Overrides the manifest for this run; on the first
  `install` it is written to the manifest. Default: `.agents/skills`.
- `--manifest <path>`: default `./skillsjars.json`
- `--json`: machine-readable output for `search`, `list`, `outdated` (agents are a primary user)
- `--quiet`, `--verbose`
- `--no-update-check`: skip the new-version notification
- `--no-self-update`: on `sjm update`, update only the SkillsJars

Exit codes: `0` success, `1` user error (bad args, unknown SkillsJar), `2` network/repository error,
`3` extraction error (collision, unsafe path, IO).

### Sync

"Sync" makes every manifest `dir` match the manifest exactly:

1. Resolve every entry to a JAR (cache, else download from the Maven repository).
2. Verify each download against the repository checksum (`.sha256` when published, else `.sha1`).
3. Read each JAR, find skill roots, compute target paths, and fail on collisions or unsafe paths before
   writing anything.
4. For each `dir`, build the full `skillsjars__*` set in a staging directory next to it (same filesystem), then
   delete the existing `skillsjars__*` folders and move the staged folders in. Folders not starting with
   `skillsjars__` are left alone. Skills removed from the manifest disappear because they are not restaged.
5. Print what was added, updated, and removed.

If no `dir` is known (no manifest `dirs`, no `--dir`), sjm uses `.agents/skills`, the cross-client location that
the Agent Skills client guide asks agents to scan
([agentskills.io](https://agentskills.io/client-implementation/adding-skills-support)). It writes that choice to
the manifest. Users of agents that only read their own folder (e.g. `.kiro/skills`, `.claude/skills`) add it
with `--dir`.

On `install <skillsjar>`, sjm prints the skill's name, description, license, and any `allowed-tools` from the
POM so the user sees what the skill can pre-approve. It also prints a one-line reminder that skills are code
and should be reviewed, matching the warning in the skillsjars.com setup guide.

### Manifest: `skillsjars.json`

Committed to the project. Versions are exact pins, which keeps installs reproducible without a lock file.

```json
{
  "dirs": [".agents/skills"],
  "skillsjars": {
    "com.skillsjars:anthropics__skills__pdf": "2026_02_06-1ed29a0",
    "com.jamesward:skills": "0.0.27"
  }
}
```

- Keys are `groupId:artifactId`; values are versions.
- sjm writes keys sorted with 2-space indentation so diffs stay small.
- Unknown top-level fields are preserved on rewrite.
- `dirs` entries are relative to the manifest's directory.

sjm should tell the user (once, when it first creates the manifest) to add the `dirs` to `.gitignore`, since
they are regenerated by `sjm install`. It does not edit `.gitignore` itself.

### Transitive dependencies

The build plugins extract transitive dependencies. sjm v1 does not resolve them. When a POM declares
`compile` or `runtime` dependencies, sjm warns and names them so the user can install them explicitly.
No current `com.skillsjars` artifact has dependencies, so this affects only third-party groups.

### Security requirements

- HTTPS only, certificate verification on.
- Verify checksums for every JAR (see Sync).
- Reject JAR entries whose path is absolute, contains `..` segments, contains a drive letter or backslash, or
  resolves outside the target skill folder (zip-slip). The plugins do not check this today.
- Never follow symlinks when deleting `skillsjars__*` folders.
- Cap JAR size (e.g. 50 MB) and total uncompressed size (e.g. 200 MB) to stop decompression bombs.

## Network services

### Maven repository (install, update, outdated)

Install and update talk to Maven Central directly so they keep working if skillsjars.com is down:

- versions: `GET https://repo1.maven.org/maven2/<group path>/<artifactId>/maven-metadata.xml`, use `<release>`
- POM: `.../<version>/<artifactId>-<version>.pom`
- JAR: `.../<version>/<artifactId>-<version>.jar` plus `.sha256` / `.sha1`

The repository base URL can be overridden with `SJM_MAVEN_REPO` (for corporate mirrors). Proxies come from the
standard `HTTPS_PROXY` / `NO_PROXY` environment variables.

Downloaded JARs are cached by GAV in the user cache dir (released Maven artifacts are immutable):
- Linux: `$XDG_CACHE_HOME/sjm` or `~/.cache/sjm`
- macOS: `~/Library/Caches/sjm`
- Windows: `%LOCALAPPDATA%\sjm\cache`

### skillsjars.com API (search)

Documented in the webapp's README (`skillsjars/README.md`). sjm uses search:

```
GET https://www.skillsjars.com/?q=<query>
Accept: application/json

200 OK
{
  "query": "interface",
  "skillsJars": [
    {
      "groupId": "com.skillsjars",
      "artifactId": "addyosmani__agent-skills__api-and-interface-design",
      "name": "api-and-interface-design",
      "description": "Guides stable API and interface design. ...",
      "versions": ["2026_04_03-a806c63"],
      "securityScanned": true,
      "allowedTools": {}
    }
  ]
}
```

- Without `q` (or with an empty `q`), the full catalog is returned and `query` is `null`. Matching is a
  case-insensitive substring match on name and description.
- `versions` is newest first, so the latest version is `versions[0]`.
- `allowedTools` maps each skill name in the jar to its space-separated `allowed-tools` value.
- sjm always sends `Accept: application/json`. The same URL serves HTML and markdown for other `Accept` values.
- Errors are `{"error": "<message>"}`: `400` for an invalid request, `502` when the site can't fetch from Maven
  Central. sjm prints the message and exits `2` (`1` for a `400`).
- sjm ignores unknown JSON fields, so the site can add fields without breaking older binaries.

The site also has `GET /{groupId}/{artifactId}/latest` → `{"groupId", "artifactId", "version"}` (`404` if the site
doesn't list the SkillsJar). sjm does not use it for install, update, or outdated: it lists only the SkillsJars
the site shows, and its list is cached for up to an hour. `maven-metadata.xml` from the Maven repository covers
any groupId and has new versions right away.

`sjm search` shows `groupId:artifactId`, latest version, a "scanned" marker when `securityScanned` is true, and
the description truncated to the terminal width.

The base URL can be overridden with `SJM_API_URL` for testing against a local webapp.

## New-version notification

- At most once per 24 hours, sjm checks the latest release by requesting
  `https://github.com/skillsjars/skillsjars-cli/releases/latest` without following the redirect and reading
  the tag from the `Location` header. This avoids the GitHub REST API's 60 requests/hour unauthenticated limit.
- The check runs in parallel with the command, with a 2 second timeout. It never delays or fails a command.
- The result and timestamp are stored in the user cache dir (`update-check.json`).
- If a newer version exists, sjm prints one line to stderr after the command output:
  `sjm 0.3.0 is available (you have 0.2.1). Run: sjm update`
- The check is skipped when `--no-update-check` is passed, `SJM_NO_UPDATE_CHECK=1` is set, `CI` is set, stderr is
  not a TTY, or `--json` is used.

## `sjm update`

`sjm update` updates the binary first, then the SkillsJars:

1. Self-update (steps below).
2. If a manifest exists, bump the given SkillsJars (all if none given) to their latest release, rewrite the
   manifest, and sync. With no manifest, only the self-update runs.

A self-update failure (no network to GitHub, unwritable install dir, package-manager install) prints a warning
and the SkillsJars update still runs. The command's exit code reflects the SkillsJars update. After replacing
itself, the running process finishes the SkillsJars step with the code it started with; the new binary takes
effect on the next run.

The self-update can be skipped with `--no-self-update` (or `SJM_NO_SELF_UPDATE=1`), for CI images and machines
where the binary is pinned.

### Self-update steps

1. Find the latest tag (same method as the notification).
2. If the current version is the same or newer, say so and move on to the SkillsJars step.
3. Download `sjm-<os>-<arch>[.exe]` and `SHA256SUMS` from
   `https://github.com/skillsjars/skillsjars-cli/releases/download/<tag>/`. Verify the SHA-256.
4. Find the running executable's real path (`/proc/self/exe` on Linux, `_NSGetExecutablePath` on macOS,
   `GetModuleFileNameW` on Windows) and resolve symlinks.
5. Write the new binary to a temp file in the same directory, mark it executable, then:
   - Linux/macOS: atomic `rename` over the current binary.
   - Windows: rename the running `sjm.exe` to `sjm.exe.old`, move the new file to `sjm.exe`. On the next start,
     sjm deletes a leftover `sjm.exe.old`.
6. If the directory is not writable, warn with the path and suggest rerunning with the right permissions. sjm
   does not elevate itself.
7. If the binary lives under a package manager's prefix (Homebrew `Cellar`, Scoop `apps`), skip the self-update
   and print the package manager's upgrade command instead. This applies only once those channels exist.

## Platforms

| OS | Arch | Asset name | GitHub Actions runner |
|---|---|---|---|
| Linux | x64 | `sjm-linux-x64` | `ubuntu-22.04` |
| Linux | arm64 | `sjm-linux-arm64` | `ubuntu-22.04-arm` |
| macOS | arm64 | `sjm-macos-arm64` | `macos-15` |
| Windows | x64 | `sjm-windows-x64.exe` | `windows-2025` |

Not in v1: macOS x64, Windows arm64, Linux musl (Alpine). Alpine comes later.

Notes:
- Linux binaries link against glibc. Building on Ubuntu 22.04 sets the floor at glibc 2.35.
- Third-party native libraries (TLS/HTTP, zlib) must be statically linked or come from the OS, so the binary
  runs on a clean machine. Each release job runs the built binary on its runner as a smoke test
  (`sjm --version`, `sjm search pdf`, an install into a temp dir).

## Technology: spikes

The stack is chosen by building the same small program with candidate stacks and comparing the results.
Binary size is a primary criterion. sjm is a basic CLI (arguments in, text or JSON out). It has no TUI, no
prompts, and no colors beyond what the CLI library gives for free.

### Spike program

Each spike implements the same minimal slice of sjm:
1. Parse `sjm install <groupId:artifactId:version> [--dir <dir>]` and `sjm --version`.
2. HTTPS GET `maven-metadata.xml` and the JAR from Maven Central, verifying the `.sha1`.
3. Read the JAR (ZIP), find `SKILL.md` roots under `META-INF/skills/`, and extract to
   `<dir>/skillsjars__...` (default `.agents/skills`).
4. Read and write a `skillsjars.json` manifest.
5. Run the update check (a GET without following redirects, read `Location`) in parallel, with a 2 second
   timeout.

### Order

1. **Kotlin Native + Ktor**, first. All required targets are supported (details below).
2. Only if Kotlin Native misses the size or portability bar, try the others in this order: Scala Native plain,
   Scala Native + Kyo, then GraalVM Native Image variations (Scala 3 plain, Scala 3 + Kyo, Kotlin JVM + Ktor).

### Spike 1: Kotlin Native + Ktor

Versions at the time of writing: Kotlin 2.4.21 (2.5.0-RC available), Ktor 3.6.1, kotlinx-coroutines 1.11.0,
kotlinx-serialization-json 1.13.0, okio 3.18.2, clikt 5.1.0.

Kotlin targets: `linuxX64`, `linuxArm64`, `macosArm64`, `mingwX64`.

| Concern | Library | Notes |
|---|---|---|
| HTTP/TLS | Ktor client | Engine per OS, see below |
| ZIP | okio `FileSystem.openZip` | Available on all native targets, uses Kotlin/Native's `platform.zlib` |
| JSON | kotlinx-serialization-json | Manifest and API responses |
| Files | okio `FileSystem.SYSTEM` | Atomic moves for staging and self-update |
| CLI parsing | see below | |
| Concurrency | kotlinx-coroutines | Parallel downloads, update check with `withTimeoutOrNull` |

Self-update needs the running executable's path, which okio does not provide. It needs small cinterop calls:
`readlink("/proc/self/exe")` (Linux), `_NSGetExecutablePath` (macOS), `GetModuleFileNameW` (Windows).

#### Ktor engines

| Target | Engines with HTTPS | TLS and certificates |
|---|---|---|
| `linuxX64`, `linuxArm64` | `curl` only | Bundled static OpenSSL; CA bundle read from the filesystem (see below) |
| `macosArm64` | `darwin` (NSURLSession), `curl` | `darwin` uses the OS TLS stack and Keychain trust |
| `mingwX64` | `winhttp`, `curl` | `winhttp` uses the OS stack; `curl` is built with Schannel. Both use the Windows certificate store |

The `cio` engine compiles for native but cannot do HTTPS there: Ktor's non-JVM TLS throws "TLS sessions are not
supported on Native platform." (`ktor-network-tls/nonJvm/.../TLSClientSession.nonJvm.kt`). So `curl` is the only
HTTPS engine on Linux.

The spike builds macOS and Windows with both engines and compares size. The OS engines (`darwin`, `winhttp`)
should be smaller because they don't bundle libcurl and OpenSSL.

#### Linux without libcurl installed

The binary does not need libcurl on the machine. Ktor's `ktor-client-curl` cinterop klib for Linux contains
static `libcurl.a`, `libnghttp2.a`, `libssl.a`, and `libcrypto.a` (built with vcpkg) and links them into the
executable. Its manifest has `staticLibraries.linux=libcurl.a libnghttp2.a libssl.a libcrypto.a` and
`linkerOpts.linux=-lz`.

What the binary still needs from the system:
- **glibc**, at or above the build machine's version (Ubuntu 22.04 runners give glibc 2.35).
- **zlib** (`libz.so.1`), linked dynamically. It is present on essentially every glibc distro, but not in
  `scratch` or distroless images. The spike checks whether it can be linked statically.
- **CA certificates.** The bundled curl and OpenSSL look in Debian-style paths: `/etc/ssl/certs/ca-certificates.crt`,
  `/etc/ssl/certs`, and `/etc/ssl/cert.pem`. These exist on Debian, Ubuntu, Arch, and Alpine. Fedora and RHEL keep
  the bundle at `/etc/pki/tls/certs/ca-bundle.crt`, so HTTPS would fail there by default. sjm handles this by:
  1. using `SSL_CERT_FILE` / `SSL_CERT_DIR` if set,
  2. otherwise probing the known bundle locations (the same list Go's `crypto/x509` uses:
     `/etc/ssl/certs/ca-certificates.crt`, `/etc/pki/tls/certs/ca-bundle.crt`, `/etc/ssl/ca-bundle.pem`,
     `/etc/pki/tls/cacert.pem`, `/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem`, `/etc/ssl/cert.pem`),
  3. passing the first one found to the curl engine (`caInfo`),
  4. failing with a message naming the paths it tried and `SSL_CERT_FILE` if none exists.

The spike's clean-machine test runs the Linux binary in `ubuntu:22.04`, `debian:stable-slim`, and
`fedora:latest` containers with no extra packages installed, to prove all of the above.

#### CLI parsing

| Option | Notes |
|---|---|
| `clikt-core` 5.1.0 | Clikt without Mordant (Mordant is its terminal styling/rendering library). Subcommands, options, help, and errors. The likely pick for size. |
| `clikt` 5.1.0 | Adds Mordant for colored help and terminal detection. Compare size against `clikt-core`. |
| `kotlinx-cli` 0.3.6 | JetBrains' parser. No release since September 2023; avoid unless it is much smaller. |
| Hand-rolled | About eight subcommands and a handful of flags. A small parser is feasible if both clikt variants add too much size. |

The spike builds with `clikt-core` and `clikt` and records the size difference.

#### Build

- Gradle Kotlin Multiplatform with the `./gradlew` wrapper (as in `skillsjars-gradle-plugin`). The layout
  follows [gibberish-ktor](https://github.com/jamesward/gibberish-ktor): per-target `binaries { executable { entryPoint = "main" } }`,
  shared code in `commonMain`, and the Ktor engine added per target source set (`linuxMain` gets
  `ktor-client-curl`). That project pins Kotlin 1.6.21, Ktor 2.0.0, and the `-native-mt` coroutines; none of
  those pins carry over. The memory-model flag in its `gradle.properties` is obsolete too.
- Follows the `zen-of-projects` Gradle rules: Kotlin DSL, `allWarningsAsErrors`, and the foojay toolchain
  resolver in `settings.gradle.kts`.
- Release binaries: `linkReleaseExecutable<Target>`, then `strip` (Linux, macOS).
- `~/.konan` (the Kotlin/Native toolchain and dependencies) is cached in CI.
- Kotlin/Native can cross-compile some targets, but each target is built and tested on its own runner so the
  test results are real.

#### Code style

Kotlin idioms that match the Scala projects' rules: `val` and read-only collections only, sealed interfaces
for ADTs, `@JvmInline value class` (inline classes) for `GroupId`, `ArtifactId`, `Version`, and sealed error
types returned as values for errors the user must act on. Exceptions only for bugs.

### What is known about the other candidates

- **Scala Native 0.5.12** (May 2026) supports all four targets. Its javalib includes `java.util.zip`, which links
  zlib (`@link("z")`), so zlib has to be supplied on Windows (e.g. vcpkg, static). HTTP via sttp client4's curl
  backend, which needs libcurl linked statically.
- **Kyo 1.0.0-RC7** publishes Scala Native artifacts, but its CI does not build Native on Windows, and
  `kyo-http`'s native transport (`kyo_tcp.c`) implements only epoll and kqueue, so it has no Windows path.
  `kyo-http` native TLS links system OpenSSL (`-lssl -lcrypto`).
- **GraalVM Native Image** supports all four targets. It needs the GraalVM toolchain on each runner and
  reflection/resource configuration for libraries that use them. `-Os` targets size.

### UPX

| Platform | UPX | Notes |
|---|---|---|
| Linux x64/arm64 | yes | Adds a small decompression cost at startup; measure it. |
| Windows x64 | yes | UPX-packed executables draw more antivirus false positives; test with Windows Defender and VirusTotal. Pack before signing once Windows signing is added. |
| macOS arm64 | no | UPX refuses macOS by default ("try --force-macos"), packed binaries have crashed on recent macOS releases ([upx#612](https://github.com/upx/upx/issues/612)), and packing conflicts with code signing and notarization. |

### Evaluation criteria

Recorded per runner in the CI job summary and in a results table in the repo:

1. **Binary size**: raw, stripped, and UPX-packed (`upx --best` and `upx --lzma`) where UPX applies. Primary
   criterion.
2. **Works on a clean machine**: dynamic dependencies (`ldd` / `otool -L` / `objdump -p`) are only OS libraries,
   and the Linux binary passes in the clean containers listed above.
3. **Startup time**: `sjm --version`, cold and warm (hyperfine).
4. **Install time**: the spike program end to end against Maven Central.
5. **Build**: CI time per target and the amount of toolchain setup and linking workarounds.
6. **Code quality**: typed errors, resource safety, and the parallel update check.

### Decision

If Kotlin Native works on all four targets with an acceptable size, use it. "Acceptable" is set after seeing
the first numbers; other candidates are spiked only if it fails. If several candidates get spiked, pick the
smallest that works everywhere, unless another is within about 20% in size and clearly better on build or code
quality.

## CI: test matrix (GitHub Actions)

`.github/workflows/test.yml` runs on every push and pull request. It is the main tool for proving the stack
works on every platform, so it runs the full matrix from the start, including during the spike.

```yaml
strategy:
  fail-fast: false
  matrix:
    include:
      - { os: ubuntu-22.04,     target: linuxX64,   asset: sjm-linux-x64 }
      - { os: ubuntu-22.04-arm, target: linuxArm64, asset: sjm-linux-arm64 }
      - { os: macos-15,         target: macosArm64, asset: sjm-macos-arm64 }
      - { os: windows-2025,     target: mingwX64,   asset: sjm-windows-x64.exe }
```

Each matrix job:
1. Checks out, sets up a JDK for Gradle, and restores the Gradle and `~/.konan` caches.
2. Runs the unit tests for its target (`./gradlew <target>Test`).
3. Builds the release executable (`./gradlew linkReleaseExecutable<Target>`), strips it (Linux, macOS), and
   copies it to the asset name.
4. Runs UPX on a copy (Linux, Windows) and checks that the packed binary still runs.
5. Smoke-tests the binary on the runner: `--version`, then `install` of a real SkillsJar into a temp dir,
   checking that `SKILL.md` lands under `.agents/skills/skillsjars__.../`.
6. Lists dynamic dependencies (`ldd`, `otool -L`, `objdump -p`) and fails if anything outside the expected OS
   libraries appears.
7. Linux only: runs the smoke test inside `ubuntu:22.04`, `debian:stable-slim`, and `fedora:latest` containers
   with no extra packages.
8. Writes raw, stripped, and UPX sizes plus startup time to `$GITHUB_STEP_SUMMARY`, and uploads the binaries as
   workflow artifacts (useful for manual testing).

During the spike, extra matrix dimensions can cover the engine and CLI-library variants
(`engine: [curl, darwin]` on macOS, `engine: [curl, winhttp]` on Windows, `cli: [clikt-core, clikt]`), so the
size comparisons come from the same workflow.

## Code signing

- **macOS**: required for v1. Sign with a Developer ID Application certificate (hardened runtime) and notarize
  with `xcrun notarytool`. A bare executable cannot be stapled, so Gatekeeper checks the notarization ticket
  online the first time the binary runs. Needs an Apple Developer Program membership.
- **Windows**: skipped for now. Unsigned binaries downloaded in a browser trigger SmartScreen; the install
  script and self-update download with PowerShell/HTTP, which avoids the browser's Mark of the Web prompt.
  Authenticode signing can be added later (after UPX packing).
- **Linux**: no OS signing. Publish `SHA256SUMS` and GitHub build provenance attestations
  (`actions/attest-build-provenance`), verifiable with `gh attestation verify`.
- Signing credentials live in GitHub Actions secrets in a protected `release` environment that only tag builds
  can use.
- Self-update and the install scripts verify `SHA256SUMS`. On macOS they also run `codesign --verify` on the new
  binary before swapping it in.

## Testing

- Unit tests for coordinate parsing, manifest read/write round-trip (including unknown-field preservation),
  skill-root detection, flattening, collision detection, and unsafe-path rejection. Reuse the test JARs in
  `skillsjars-maven-plugin/src/test/resources/`.
- Extraction tests against a temp dir: preserves non-`skillsjars__` folders, prunes removed skills, and leaves
  the dir unchanged when a later JAR fails.
- HTTP tests against a local fake server (Maven repo layout + the search endpoint `/?q=`) via `SJM_MAVEN_REPO` and
  `SJM_API_URL`.
- Self-update test with a fake release server: the binary replaces itself and runs at the new version.
  Includes the Windows rename path.
- The test matrix smoke-tests each binary on its own runner (see CI: test matrix).

## Releases (GitHub Actions)

`.github/workflows/release.yml`, triggered by pushing a `v*` tag:

1. Reuses the test matrix (as a reusable workflow) so every target is tested and smoke-tested first.
2. Per target: build the release binary, strip it, apply UPX where supported (Linux, Windows), sign and
   notarize on macOS, and rename it to its asset name.
3. A final job collects the four binaries, writes `SHA256SUMS`, generates build provenance attestations, and
   creates the GitHub Release with the binaries and `SHA256SUMS`.
4. The version embedded in the binary comes from the tag, so `sjm --version` matches the release.

### Installation

- `install.sh` (Linux/macOS): detects OS/arch, downloads the matching asset and `SHA256SUMS` from the latest
  release, verifies, installs to `~/.local/bin` (or `$SJM_INSTALL_DIR`), and says if that dir is not on `PATH`.
- `install.ps1` (Windows): same, installing to `%LOCALAPPDATA%\Programs\sjm` and adding it to the user `PATH`.
- Both scripts live in this repo and are served from the raw GitHub URL. Serving them from skillsjars.com
  (`https://www.skillsjars.com/install.sh`) is optional.
- Homebrew tap and Scoop/winget manifests come later.

## Related changes outside this repo

- **skillsjars webapp** (www.skillsjars.com): the JSON search API is done. Still to add: an "Other projects (no
  Maven/Gradle/sbt)" section to the `/setup` markdown and the web UI's build-tool selector showing
  `sjm install <artifactId>`.

## Open questions

1. **Size bar**: what binary size is acceptable? To be set after the first Kotlin Native numbers.
