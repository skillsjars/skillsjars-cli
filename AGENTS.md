# AGENTS.md: skillsjars-cli

`sjm` (SkillsJars Manager): a native CLI that searches, installs, and updates SkillsJars for projects that don't
use Maven, Gradle, or sbt. See `SPEC.md`. The code is currently the Kotlin Native spike (`SPEC.md`, "Spike 1").

## Skills

Follow the `zen-of-projects` Skill; this file records only project-specific facts and exceptions.
For design, domain modeling, effects and testing strategy, follow `zen-of-james`.

The Skills are declared in `build.gradle.kts` (`skill("com.jamesward:skills:<version>")`). Extract them before
working, then read the relevant `SKILL.md` files under `.kiro/skills/`:

    ./gradlew extractSkillsJars

## MCP

`javadocs` (https://www.javadocs.dev/mcp), configured in `.mcp.json` / `.kiro/settings/mcp.json` and approved in
`.claude/settings.json`. Use its `get_latest_version` for version lookups and its source/doc tools for API
questions. In Claude Code its tools are deferred: load them with ToolSearch (search `javadocs`).

## Build & test

- Full validation: `./gradlew build` (tests the host's target; macOS and Windows are tested on their CI runners).
- Tests for one target: `./gradlew linuxX64Test` (or `linuxArm64Test`, `macosArm64Test`, `mingwX64Test`).
- Release binary: `./gradlew linkReleaseExecutableLinuxX64`, output in `build/bin/linuxX64/releaseExecutable/`.
- Spike variant: `-Psjm.engine=native|curl` (Ktor engine on macOS/Windows).
- Version: from git tags via `com.palantir.git-version` (tag `v0.1.0` → `0.1.0`).
- CI: `.github/workflows/test.yaml` builds, tests, packs, and verifies the four targets (`.github/scripts/smoke.py`,
  `.github/scripts/containers.sh`).
- Release: push a `vMAJOR.MINOR.PATCH` tag. `.github/workflows/release.yaml` reuses `test.yaml` and publishes its
  verified assets with `SHA256SUMS` and provenance attestations, only when all four targets passed.

## Maintenance routine

`.factory/MAINTENANCE.md` (weekly), following the `zen-of-projects` Skill.

## Exceptions to zen-of-projects

- **No Java toolchain:** the project has only Kotlin/Native targets, so nothing compiles for the JVM. Gradle's own
  JVM is pinned to Temurin 25 in `gradle/gradle-daemon-jvm.properties`: Kotlin/Native's compiler crashed
  (SIGSEGV in `ffiFreeClosure0`) under NixOS's OpenJDK 25 build.
