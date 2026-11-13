# AGENTS.md: skillsjars-cli

`sjm` (SkillsJars Manager): a native CLI that searches, installs, and updates SkillsJars for projects that don't
use Maven, Gradle, or sbt. See `SPEC.md`.

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

- Full validation: `./gradlew build`.
- No application code yet: the build only extracts Skills. The Kotlin Native spike (`SPEC.md`) adds the
  targets, tests, and CI matrix.

## Maintenance routine

`.factory/MAINTENANCE.md` (weekly), following the `zen-of-projects` Skill.
