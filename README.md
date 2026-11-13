# sjm: SkillsJars Manager

`sjm` installs [Agent Skills](https://agentskills.io/) from [SkillsJars.com](https://www.skillsjars.com) into any
project, without a JVM or a build tool. Maven, Gradle, and sbt projects can use the SkillsJars build plugins instead.

## Install

Linux (x64, arm64; glibc) and macOS (Apple silicon):

```sh
curl -fsSL https://raw.githubusercontent.com/skillsjars/skillsjars-cli/main/install.sh | sh
```

Windows (x64), in PowerShell:

```powershell
irm https://raw.githubusercontent.com/skillsjars/skillsjars-cli/main/install.ps1 | iex
```

Both scripts check the download against the release's `SHA256SUMS`. You can also download a binary from
[Releases](https://github.com/skillsjars/skillsjars-cli/releases) and check it with
`gh attestation verify <file> --repo skillsjars/skillsjars-cli`.

## Use

```sh
sjm search pdf                          # find SkillsJars
sjm install anthropics__skills__pdf     # add one (latest version) and extract it
sjm install                             # extract everything in skillsjars.json (e.g. after a checkout)
sjm list                                # what's installed
sjm outdated                            # what has newer versions
sjm update                              # update sjm itself, then every SkillsJar
sjm remove anthropics__skills__pdf
```

A SkillsJar is named by its artifactId (the group defaults to `com.skillsjars`) or `groupId:artifactId`, with
`@version` to pin a version: `sjm install com.jamesward:skills@0.0.27`.

`sjm install` writes `skillsjars.json`, which pins exact versions. Commit it. Skills are extracted to
`.agents/skills/skillsjars__<org>__<repo>__<skill>/`, the folder agents that follow the Agent Skills conventions
read. sjm only touches `skillsjars__*` folders, so skills you keep there yourself are safe. Add the skills folder to
`.gitignore`: `sjm install` rebuilds it.

For an agent that reads skills from its own folder, add that folder:

```sh
sjm --dir .kiro/skills install anthropics__skills__pdf
```

The dirs are saved in `skillsjars.json` when it's created; edit its `dirs` to change them later.

Skills are instructions your agent will follow. Review them before you install them.

Options: `--json` (for `search`, `list`, `outdated`), `-q` / `--quiet`, `-v` / `--verbose`, `--manifest <path>`,
`--no-update-check`, and `sjm update --no-self-update`. Global options go before the command. Run `sjm --help` or
`sjm <command> --help` for more.

sjm prints a one-line notice when a newer sjm is released (checked at most once a day; never in CI or when output
isn't a terminal). `SJM_NO_UPDATE_CHECK=1` turns it off.

## Development

See `AGENTS.md` for the build and test commands and `SPEC.md` for the design.
