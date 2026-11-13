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
  (currently `com.jamesward:skills` and `org.springaicommunity:spring-testing-skills`).
- sjm supports only SkillsJars in the skillsjars.com index, whatever their groupId. Anything else is rejected
  as unknown (see [The skillsjars.com index](#the-skillsjarscom-index)).
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
- `groupId:artifactId:version`, or either form above with `@version`, to pin a version; otherwise the latest
  version from skillsjars.com is used. `remove` and `update` take no version.

### Commands

```
sjm search [<query>]               Search the catalog (name/description, case-insensitive); all without a query
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
- `-q`/`--quiet` (only warnings and errors), `-v`/`--verbose` (also the skill folders written and removed)
- `--no-update-check`: skip the new-version notification
- `--no-self-update` (an `update` option): update only the SkillsJars

Global options go before the command (`sjm --json search pdf`).

Environment: `SJM_API_URL`, `SJM_MAVEN_REPO`, and `SJM_RELEASES_URL` override skillsjars.com, Maven Central, and
the GitHub releases (for tests and mirrors); `SJM_NO_UPDATE_CHECK` and `SJM_NO_SELF_UPDATE` turn those off.

Exit codes: `0` success, `1` user error (bad args, SkillsJar not in the skillsjars.com index), `2` network/repository error,
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

On `install <skillsjar>`, sjm prints the SkillsJar's description, license, and any `allowed-tools` from the
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
No current `com.skillsjars` artifact has dependencies, so this affects only third-party groups. A dependency
that is not in the skillsjars.com index can't be installed, so sjm only names it.

### Security requirements

- HTTPS only, certificate verification on.
- Verify checksums for every JAR (see Sync).
- Reject JAR entries whose path is absolute, contains `..` segments, contains a drive letter or backslash, or
  resolves outside the target skill folder (zip-slip). The plugins do not check this today.
- Never follow symlinks when deleting `skillsjars__*` folders.
- Cap JAR size (e.g. 50 MB) and total uncompressed size (e.g. 200 MB) to stop decompression bombs.

## Network services

### The skillsjars.com index

skillsjars.com is the source of truth for which SkillsJars exist and what their latest version is. Maven Central
only serves the files.

| Command | skillsjars.com | Maven Central |
|---|---|---|
| `search` | `/?q=` | - |
| `install <skillsjar>` (no version) | `/latest`; `404` → not in the index, exit `1` | POM, JAR |
| `install <skillsjar>@<version>` | `/latest` (index membership only) | JAR; `404` → no such version, exit `1` |
| `update`, `outdated` | `/latest` for each manifest entry, in parallel | POM, JAR (`update`) |
| `install` (no args, sync) | - | JAR (cache first) |

- Sync of an existing manifest needs no skillsjars.com call, so checking out a project and running
  `sjm install` works while the site is down.
- The site caches its index for up to an hour, so a version published in the last hour may not be offered yet.
  sjm doesn't work around that.
- If a manifest entry is no longer in the index (`/latest` returns `404`), `update` and `outdated` warn and leave
  it pinned; sync still installs it.

### Maven repository (files)

- POM: `https://repo1.maven.org/maven2/<group path>/<artifactId>/<version>/<artifactId>-<version>.pom`
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

Latest version, used by `install`, `update`, and `outdated`:

```
GET https://www.skillsjars.com/{groupId}/{artifactId}/latest

200 OK
{"groupId":"com.skillsjars","artifactId":"addyosmani__agent-skills__api-and-interface-design","version":"2026_04_03-a806c63"}
```

`404` means the SkillsJar is not in the index.

`sjm search` shows `groupId:artifactId`, latest version, a "scanned" marker when `securityScanned` is true, and
the description truncated to the terminal width.

The base URL can be overridden with `SJM_API_URL` for testing against a local webapp.

## New-version notification

- At most once per 24 hours, sjm checks the latest release by requesting
  `https://github.com/skillsjars/skillsjars-cli/releases/latest` without following the redirect and reading
  the tag from the `Location` header. This avoids the GitHub REST API's 60 requests/hour unauthenticated limit.
- The result and timestamp are stored in the user cache dir (`update-check.json`). Within 24 hours of the last
  check, sjm uses the stored result and makes no network call.
- Otherwise the check runs in parallel with the command, with a 2 second timeout. It never fails a command, and
  delays it only when the command finishes first, by at most the rest of the 2 seconds, once a day. A check
  that times out isn't stored, so the next run tries again.
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
4. Find the running executable's real path (`/proc/self/exe` on Linux, `NSBundle.mainBundle.executablePath` on
   macOS, `GetModuleFileNameW` on Windows) and resolve symlinks. Development builds (versions that aren't
   `MAJOR.MINOR.PATCH`) don't self-update.
5. Write the new binary to a temp file in the same directory, mark it executable, then:
   - Linux/macOS: atomic `rename` over the current binary.
   - Windows: rename the running `sjm.exe` to `sjm.exe.old`, move the new file to `sjm.exe`. On the next start,
     sjm deletes a leftover `sjm.exe.old`.
6. If the directory is not writable, warn with the path and suggest rerunning with the right permissions. sjm
   does not elevate itself.
7. If the binary lives under a package manager's prefix (Homebrew `Cellar`, Scoop `apps`), skip the self-update
   and print the package manager's upgrade command instead. This applies only once those channels exist.
8. Once macOS releases are signed, also run `codesign --verify` on the new binary before moving it into place.

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

## Technology

**Kotlin/Native with Ktor**, chosen by a spike (see [How the stack was chosen](#how-the-stack-was-chosen)). sjm is
a basic CLI (arguments in, text or JSON out): no TUI, no prompts.

Versions at the time of writing: Kotlin 2.4.21, Ktor 3.6.1, kotlinx-coroutines 1.11.0, kotlinx-serialization-json
1.13.0, okio 3.18.2, clikt 5.1.0.

Kotlin targets: `linuxX64`, `linuxArm64`, `macosArm64`, `mingwX64`.

| Concern | Library | Notes |
|---|---|---|
| HTTP/TLS | Ktor client | `curl` on Linux, `darwin` (NSURLSession) on macOS, `winhttp` on Windows |
| ZIP | okio `FileSystem.openZip` | Uses Kotlin/Native's `platform.zlib` |
| JSON | kotlinx-serialization-json | Manifest, API responses, `--json` output |
| Files | okio `FileSystem.SYSTEM` | Atomic moves for staging and self-update |
| CLI parsing | `clikt-core` | Clikt without Mordant |
| Concurrency | kotlinx-coroutines | Parallel downloads, the update check with a timeout |

The running executable's path, needed for self-update, comes from `readlink("/proc/self/exe")` on Linux,
`NSBundle.mainBundle.executablePath` on macOS (`_NSGetExecutablePath` isn't in Kotlin/Native's macOS platform
libraries), and `GetModuleFileNameW` on Windows, then symlinks are resolved.

### HTTP engines

| Target | Engine | TLS and certificates |
|---|---|---|
| `linuxX64`, `linuxArm64` | `curl` | Static libcurl + OpenSSL linked into the binary; CA certificates as below |
| `macosArm64` | `darwin` | The OS TLS stack and Keychain trust |
| `mingwX64` | `winhttp` | The OS TLS stack and the Windows certificate store |

On Linux, `curl` is the only HTTPS engine: `cio` compiles for native, but Ktor's non-JVM TLS throws "TLS sessions are
not supported on Native platform." On macOS and Windows the OS engines were smaller than `curl` (macOS 3.6 MB vs
8.9 MB; Windows 6.3 MB vs 7.2 MB before UPX) and started faster.

### Linux runtime requirements

The binary doesn't need libcurl installed: Ktor's `ktor-client-curl` cinterop klib contains static `libcurl.a`,
`libnghttp2.a`, `libssl.a`, and `libcrypto.a` and links them in. From the system it needs:
- **glibc.** The floor comes from Kotlin/Native's own sysroot, not the build host. musl (Alpine) isn't supported yet.
- **zlib** (`libz.so.1`), present on essentially every glibc distro (not in `scratch` or distroless images).
- **Nothing else.** Kotlin/Native's `platform.posix` def adds `-lresolv -lutil -lcrypt -lrt` to every binary that
  uses it (okio does), and `libcrypt.so.1` is missing from default NixOS, Fedora, and Arch installs. Linux binaries
  link with `--as-needed`, which drops the unused ones: they need only `libc`, `libm`, `libpthread`, `librt`,
  `libdl`, `libgcc_s`, `libz`, and the dynamic loader. CI fails if anything else appears.

CA certificates: the bundled curl looks only in Debian-style paths, which Fedora and RHEL don't use, and bare
`ubuntu` and `debian-slim` images have no CA store at all. sjm uses, in order:
1. `SSL_CERT_FILE` / `SSL_CERT_DIR` if set,
2. the first system bundle found (the list Go's `crypto/x509` uses: `/etc/ssl/certs/ca-certificates.crt`,
   `/etc/pki/tls/certs/ca-bundle.crt`, `/etc/ssl/ca-bundle.pem`, `/etc/pki/tls/cacert.pem`,
   `/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem`, `/etc/ssl/cert.pem`),
3. Mozilla's CA bundle compiled into the binary (from `certs/cacert.pem`, [curl.se/ca](https://curl.se/ca/cacert.pem),
   about 180 KB of PEM data), written once to the cache dir as `ca/mozilla-<sha256>.pem` because curl reads CA
   certificates from a file.

If verification still fails, sjm says to check `SSL_CERT_FILE` / `SSL_CERT_DIR` or install `ca-certificates`
(exit `2`). The maintenance routine refreshes `certs/cacert.pem` (see `AGENTS.md`).

### Process exit

sjm flushes stdout and stderr and ends with `_exit`, skipping exit handlers. Ktor's curl engine frees its OpenSSL
state on a background thread after the client closes, with no way to wait for it, and OpenSSL's own exit handler
(`OPENSSL_cleanup`) racing it crashed about 1 Linux run in 40 with SIGSEGV after the work was done. With `_exit`:
0 crashes in 300 runs. Every file sjm writes is closed before it exits, so nothing needs the handlers. The smoke
test repeats `sjm install` 10 times per target to catch a regression.

### UPX

| Platform | UPX | Notes |
|---|---|---|
| Linux x64/arm64 | `--best` | 11.3 → 3.8 MB (x64), about 30 ms more startup |
| Windows x64 | `--best` | 6.3 → 3.2 MB, about 35 ms more startup. UPX-packed executables draw more antivirus false positives; pack before signing once Windows signing is added. |
| macOS arm64 | no | UPX refuses macOS by default, packed binaries have crashed on recent macOS ([upx#612](https://github.com/upx/upx/issues/612)), and packing conflicts with code signing and notarization. |

`--lzma` saves another 0.3 to 0.7 MB but adds 120 to 170 ms to every run.

### Build

- Gradle Kotlin Multiplatform with the `./gradlew` wrapper. Platform code lives in the default source sets
  (`linuxMain`, `macosMain`, `mingwMain`), shared code in `commonMain`, as in
  [gibberish-ktor](https://github.com/jamesward/gibberish-ktor) (whose Kotlin 1.6 / Ktor 2.0 versions don't carry over).
- Follows the `zen-of-projects` Gradle rules: Kotlin DSL, `allWarningsAsErrors`, and the foojay toolchain resolver.
- The Kotlin/Native compiler doesn't run on Linux arm64, so `linuxArm64` is cross-compiled on x64 (see CI).
- clikt-core's defaults are overridden in `SjmCommand`: by default it never exits the process and echoes errors to
  stdout.

### Code style

Kotlin idioms that match the Scala projects' rules: `val` and read-only collections only, sealed interfaces for
ADTs, value classes for `GroupId`, `ArtifactId`, `Version`, and errors the user must act on returned as values
(`Either<SjmError, A>`). Exceptions only for bugs. Side effects go through interfaces (`SkillsJarsIndex`,
`JarRepository`, `ReleaseSource`, okio's `FileSystem`) so tests use fakes.

### How the stack was chosen

The requirements: one native binary per target with no runtime to install, HTTPS, ZIP, JSON, and files on all four
targets, fast startup, and binary size as a primary criterion (UPX allowed where it works).

Candidates, in the order they would have been tried: Kotlin/Native + Ktor; Scala Native; Scala Native + Kyo; and
GraalVM Native Image with Scala 3, Scala 3 + Kyo, or Kotlin JVM + Ktor. What was known about the others going in:
- **Scala Native 0.5.12** supports all four targets; its `java.util.zip` links zlib, which Windows lacks; HTTP via
  sttp client4's curl backend needs libcurl linked statically.
- **Kyo 1.0.0-RC7** publishes Scala Native artifacts, but its CI doesn't build Native on Windows, and `kyo-http`'s
  native transport (`kyo_tcp.c`) implements only epoll and kqueue, so it has no Windows path.
- **GraalVM Native Image** supports all four targets but needs the GraalVM toolchain and reflection configuration
  on each runner.

Kotlin/Native was spiked first with a slice of sjm (install from skillsjars.com and Maven Central, extraction, the
manifest, the update check) and met every requirement, so the others weren't spiked. The spike also compared:

| Binary (stripped) | Engine | Raw | UPX `--best` | UPX `--lzma` |
|---|---|---:|---:|---:|
| `sjm-linux-x64` | curl | 11.3 MB, 2 ms | 3.8 MB, 30 ms | 3.3 MB, 169 ms |
| `sjm-linux-arm64` | curl | 10.2 MB, 3 ms | 3.9 MB, 37 ms | 3.2 MB, 150 ms |
| `sjm-macos-arm64` | darwin | 3.6 MB, 8 ms | | |
| `sjm-macos-arm64` | curl | 8.9 MB, 13 ms | | |
| `sjm-windows-x64.exe` | winhttp | 6.3 MB, 9 ms | 3.2 MB, 45 ms | 2.8 MB, 124 ms |
| `sjm-windows-x64.exe` | curl | 7.2 MB, 11 ms | 3.6 MB, 56 ms | 3.3 MB, 166 ms |

(Startup is the mean of 20 `sjm --version` runs on the CI runner.) `clikt` with Mordant instead of `clikt-core` made
the Linux x64 binary 12.2 MB, and its debug test binary failed to link against Kotlin/Native's library caches
(clikt 5.1.0's `clikt` and `clikt-core` both define `Context.selfAndAncestors`).

## CI: test matrix (GitHub Actions)

`.github/workflows/test.yaml` runs on every push to `main` and every pull request, and `release.yaml` reuses it.

The Kotlin/Native compiler runs only on Linux x64, macOS, and Windows x64 hosts, not on Linux arm64 ("Your platform
is not on this list, so the Kotlin/Native compiler cannot be executed"), so the workflow has two stages:

| Target | `build` runs on | `verify` runs on |
|---|---|---|
| `linuxX64` | `ubuntu-22.04` | `ubuntu-22.04` |
| `linuxArm64` | `ubuntu-22.04` (cross-compiled) | `ubuntu-22.04-arm` |
| `macosArm64` | `macos-15` | `macos-15` |
| `mingwX64` | `windows-2025` | `windows-2025` |

**build**, per target: sets up Temurin 25 for Gradle, restores the Gradle (`gradle/actions/setup-gradle`) and
`~/.konan` caches, links the debug test binary and the release executable, builds the test fixture JARs, and
uploads them as an artifact.

**verify**, per target, on the target's own OS and architecture:
1. Runs the test binary.
2. In a release, checks that `sjm --version` reports the release version.
3. Strips the release binary (Linux, macOS).
4. Smoke-tests it (`.github/scripts/smoke.py`): `--version`, then `install` of a real SkillsJar into a temp dir
   (checking the `SKILL.md` files and `skillsjars.json`), then `install` 10 more times.
5. Lists dynamic dependencies (`ldd`, `otool -L`, Git for Windows' `ldd`) and fails if anything outside the OS's own
   libraries appears.
6. Packs copies with UPX `--best` and `--lzma` (Linux, Windows), smoke-tests them, and writes sizes and startup times
   to the job summary.
7. Packages the release asset (UPX `--best` on Linux and Windows), smoke-tests it, and records its SHA-256.
8. Installs the asset with `install.sh` / `install.ps1` from a fake release server (`.github/scripts/fake_release.py`)
   and runs it.
9. Linux only: runs the asset in clean `ubuntu:22.04`, `debian:stable-slim`, `fedora:latest`, and
   `archlinux:latest` (x64 only) containers with no extra packages. Each must install a SkillsJar (the bare Debian
   and Ubuntu images use the compiled-in CA bundle), and a bad `SSL_CERT_FILE` must fail with exit `2` and a hint
   (`.github/scripts/containers.sh`).
10. Uploads the asset and its `.sha256` as `release-<asset>`.

Any failed job cancels the whole run (`gh run cancel`): one failed target means the run can't pass, and a release
can't publish, so the other runners stop early.

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
- Self-update and the install scripts verify `SHA256SUMS`. Once macOS releases are signed, self-update also runs
  `codesign --verify` on the new binary before swapping it in.

## Testing

The test onion, from fast to integrated:
- Unit tests for coordinate parsing, the manifest (round trip, unknown-field preservation, validation), the
  SkillsJar layout (skill roots, flattening, collisions, unsafe paths, size limits), POM reading, checksum files,
  and the update notice.
- `Project` tests with a fake index and repository on the real filesystem, with real JARs built by Gradle from
  `src/commonTest/fixtures/`: install, sync, remove, outdated, update, dependency warnings, and that a failure leaves
  the skills dirs and manifest unchanged.
- HTTP tests against Ktor's `MockEngine`: the index, search, Maven downloads with SHA-256/SHA-1 checks and caching,
  and GitHub release files.
- Self-update tests with a fake release source and a real file standing in for the binary, including the Windows
  rename path, a checksum mismatch, and package-manager installs. Update-check tests cover the 24-hour cache and
  timeouts. Linux tests cover the CA source order and the compiled-in bundle.
- CI runs every binary on its own OS and architecture against the real services (see CI).

## Releases (GitHub Actions)

Push a tag `vMAJOR.MINOR.PATCH` (e.g. `v0.1.0`). `.github/workflows/release.yaml`:

1. Calls `test.yaml` as a reusable workflow with the version. In release mode the matrix has only the four release
   targets, and nothing is built twice: `test.yaml`'s `verify` job already produces each release asset.
   For each target, `verify`:
   - runs the tests on the target OS and architecture,
   - checks that `sjm --version` reports the tag's version,
   - strips the binary, packs it with UPX `--best` (Linux, Windows; see UPX), and smoke-tests the packed binary,
     and on Linux runs it in the clean containers,
   - computes its SHA-256 on that runner and uploads the binary and its `.sha256` as a `release-<asset>` artifact.
   (macOS signing and notarization will slot in before packaging the asset.)
2. `publish` runs only if every `test.yaml` job succeeded. A failed target, or one whose runner never arrived (as
   happened with a macOS runner during the spike), fails the release, so an incomplete set is never published.
   `publish` then:
   - checks that exactly the four assets and their `.sha256` files are present,
   - checks each binary against the `.sha256` computed where it was verified, so nothing changed in transit,
   - writes `SHA256SUMS`,
   - generates build provenance attestations (`actions/attest-build-provenance`), verifiable with
     `gh attestation verify <file> --repo skillsjars/skillsjars-cli`,
   - creates the GitHub Release with the four binaries and `SHA256SUMS`.

It runs in the `release` environment, which can be protected (required reviewers, tag-only) and will hold the
signing secrets.

The version comes from git: the `com.palantir.git-version` Gradle plugin runs `git describe`, so tag `v0.1.0`
builds `0.1.0`. Other commits build e.g. `0.1.0-3-gabc1234` (or a bare commit hash before the first tag), plus
`.dirty` with uncommitted changes. Only exact `MAJOR.MINOR.PATCH` versions parse as releases, so dev builds never
show the update notice. CI checks out with full history (`fetch-depth: 0`) so the tags are there.

The UPX mode for release assets is `--best` (see UPX).

### Installation

```sh
curl -fsSL https://raw.githubusercontent.com/skillsjars/skillsjars-cli/main/install.sh | sh
```

```powershell
irm https://raw.githubusercontent.com/skillsjars/skillsjars-cli/main/install.ps1 | iex
```

- `install.sh` (Linux/macOS): finds the latest release from the `/releases/latest` redirect, downloads the asset for
  this OS and architecture and `SHA256SUMS`, verifies, and installs to `$SJM_INSTALL_DIR` (default `~/.local/bin`),
  saying if that dir isn't on `PATH`. It refuses musl systems.
- `install.ps1` (Windows x64): the same, installing to `$env:SJM_INSTALL_DIR` (default
  `%LOCALAPPDATA%\Programs\sjm`) and adding it to the user `PATH`.
- Both honor `SJM_VERSION` (a specific release) and `SJM_RELEASES_URL` (for testing), and CI runs both against a
  fake release server.
- Serving them from skillsjars.com (`https://www.skillsjars.com/install.sh`) is optional. Homebrew and Scoop/winget
  come later.

## Related changes outside this repo

- **skillsjars webapp** (www.skillsjars.com): the JSON search API is done. Still to add: an "Other projects (no
  Maven/Gradle/sbt)" section to the `/setup` markdown and the web UI's build-tool selector showing
  `sjm install <artifactId>`.

## Later

- Windows Authenticode signing.
- Homebrew tap, Scoop/winget manifests.
- Alpine (musl) binaries.
- macOS x64 and Windows arm64, if needed.
