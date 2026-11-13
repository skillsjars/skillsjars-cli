package sjm

import com.github.ajalt.clikt.core.BadParameterValue
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.findOrSetObject
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** Where sjm talks to, overridable for tests and mirrors. */
data class Endpoints(val api: String, val mavenRepo: String, val releases: String) {
    companion object {
        fun fromEnv(): Endpoints = Endpoints(
            api = (env("SJM_API_URL") ?: "https://www.skillsjars.com").trimEnd('/'),
            mavenRepo = (env("SJM_MAVEN_REPO") ?: "https://repo1.maven.org/maven2").trimEnd('/'),
            releases = (env("SJM_RELEASES_URL") ?: UpdateCheck.REPO_URL).trimEnd('/'),
        )
    }
}

fun sjmHttpClient(followRedirects: Boolean): HttpClient =
    httpClient {
        this.followRedirects = followRedirects
        expectSuccess = false
        install(UserAgent) { agent = "sjm/$SJM_VERSION" }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 120_000
        }
    }

fun currentInstallation(fs: FileSystem): Installation =
    Installation(
        version = AppVersion.parse(SJM_VERSION),
        assetName = releaseAssetName,
        executable = currentExecutablePath()?.let { p ->
            try {
                fs.canonicalize(p.toPath())
            } catch (_: Exception) {
                null
            }
        },
        windows = isWindows,
    )

private fun <A> Either<String, A>.orUsageError(): A =
    when (this) {
        is Either.Left -> throw BadParameterValue(value)
        is Either.Right -> value
    }

/** Options shared by every command, set by [SjmCommand]. */
data class Globals(
    val manifest: String,
    val dirs: List<SkillsDir>,
    val json: Boolean,
    val quiet: Boolean,
    val verbose: Boolean,
    val noUpdateCheck: Boolean,
)

/** What a command prints. Info is hidden by `--quiet`, Detail shown only with `--verbose`; warnings always go to stderr. */
enum class Level { Info, Detail, Warn }

data class Line(val level: Level, val text: String)

data class Output(val lines: List<Line>, val json: JsonElement? = null) {
    companion object {
        fun of(vararg lines: Line): Output = Output(lines.toList())
    }
}

fun info(text: String) = Line(Level.Info, text)
fun detail(text: String) = Line(Level.Detail, text)
fun warn(text: String) = Line(Level.Warn, text)

/** Everything a command needs to run, built once per run. */
class Env(
    val fs: FileSystem,
    val globals: Globals,
    val index: SkillsJarsIndex,
    val jars: JarRepository,
    val releases: ReleaseSource,
    val manifestPath: Path,
) {
    val project: Project
        get() = Project(fs, index, jars, manifestPath, NonEmptyList.fromList(globals.dirs))
}

class SjmCommand : CommandBase(name = "sjm") {
    init {
        // clikt-core's defaults never exit and echo errors to stdout.
        context {
            exitProcess = { exitNow(it) }
            echoMessage = { _, message, trailingNewline, err ->
                val text = "$message" + if (trailingNewline) "\n" else ""
                if (err) writeStderr(text) else print(text)
            }
        }
        versionOption(SJM_VERSION, message = { "sjm $it ($httpEngineName)" })
    }

    private val manifest by option("--manifest", help = "Manifest path").default(Manifest.FILE_NAME)
    private val dirs by option("--dir", help = "Skills directory (repeatable). Default: the manifest's dirs, else .agents/skills")
        .convert { SkillsDir.parse(it).orUsageError() }
        .multiple()
    private val json by option("--json", help = "JSON output (search, list, outdated)").flag()
    private val quiet by option("-q", "--quiet", help = "Print only warnings and errors").flag()
    private val verbose by option("-v", "--verbose", help = "Print more detail").flag()
    private val noUpdateCheck by option("--no-update-check", help = "Don't check for a newer sjm").flag()

    override fun help(context: Context): String = "SkillsJars Manager: install Agent Skills from skillsjars.com"

    override suspend fun run() {
        currentContext.findOrSetObject { Globals(manifest, dirs, json, quiet, verbose, noUpdateCheck) }
    }
}

/** A subcommand: runs [execute] with the update check alongside, prints its output, and exits with its error's code. */
abstract class SjmSubcommand(name: String) : CommandBase(name = name) {
    private val globals by requireObject<Globals>()

    /** `update` replaces the binary itself, so it skips the notice. */
    protected open val checksForUpdates: Boolean = true

    protected abstract suspend fun execute(env: Env): Either<SjmError, Output>

    private fun updateCheckEnabled(): Boolean =
        checksForUpdates && !globals.noUpdateCheck && !globals.json && env("SJM_NO_UPDATE_CHECK") == null &&
            env("CI") == null && stderrIsTty()

    private fun print(output: Output) {
        val json = output.json
        if (globals.json && json != null) echo(prettyJson.encodeToString(JsonElement.serializer(), json))
        output.lines.forEach { line ->
            when (line.level) {
                Level.Warn -> echo("sjm: ${line.text}", err = true)
                Level.Info -> if (!globals.quiet && !(globals.json && json != null)) echo(line.text)
                Level.Detail -> if (globals.verbose && !globals.quiet && !(globals.json && json != null)) echo(line.text)
            }
        }
    }

    final override suspend fun run() {
        val fs = FileSystem.SYSTEM
        val endpoints = Endpoints.fromEnv()
        val cacheDir = userCacheDir() ?: run {
            echo("sjm: can't find a cache directory (set HOME, or LOCALAPPDATA on Windows)", err = true)
            throw ProgramResult(ExitCode.UserError.code)
        }
        val manifestPath = globals.manifest.toPath().let { if (it.isAbsolute) it else fs.canonicalize(".".toPath()) / it }

        val (outcome, latest) = sjmHttpClient(followRedirects = true).use { http ->
            sjmHttpClient(followRedirects = false).use { noRedirects ->
                val releases = GitHubReleases(http, noRedirects, endpoints.releases)
                val env = Env(
                    fs = fs,
                    globals = globals,
                    index = HttpSkillsJarsIndex(http, endpoints.api),
                    jars = CachingJarRepository(http, fs, cacheDir, endpoints.mavenRepo),
                    releases = releases,
                    manifestPath = manifestPath,
                )
                if (updateCheckEnabled()) UpdateCheck(fs, cacheDir, releases, ::nowEpochSeconds).alongside { execute(env) }
                else execute(env) to null
            }
        }

        when (outcome) {
            is Either.Right -> print(outcome.value)
            is Either.Left -> echo("sjm: ${outcome.value.message}", err = true)
        }
        UpdateCheck.notice(AppVersion.parse(SJM_VERSION), latest)?.let { echo(it, err = true) }
        if (outcome is Either.Left) throw ProgramResult(outcome.value.exitCode.code)
    }
}

private val prettyJson = Json { prettyPrint = true }

private fun terminalWidth(): Int = env("COLUMNS")?.toIntOrNull()?.takeIf { it >= 40 } ?: 100

private fun truncate(text: String, width: Int): String {
    val oneLine = text.replace(Regex("\\s+"), " ").trim()
    return if (oneLine.length <= width) oneLine else oneLine.take(width - 1).trimEnd() + "…"
}

private fun syncLines(synced: List<SyncResult>): List<Line> =
    synced.flatMap { s ->
        listOf(info("${s.dir}: ${s.written.size} skill(s)")) +
            s.removed.map { detail("  removed $it") } +
            s.written.map { detail("  $it") }
    }

private fun SkillsJarRef.withoutVersion(): Coordinates =
    if (version != null) throw BadParameterValue("$coordinates@$version: give the SkillsJar without a version") else coordinates

private fun skillsJarArgument(cmd: CommandBase) =
    cmd.argument(name = "SKILLSJAR", help = "artifactId, groupId:artifactId, or groupId:artifactId:version; append @version to pin")
        .convert { SkillsJarRef.parse(it).orUsageError() }

class SearchCommand : SjmSubcommand("search") {
    override fun help(context: Context): String = "Search skillsjars.com by name or description (all SkillsJars when no query)"

    private val query by argument(name = "QUERY").optional()

    override suspend fun execute(env: Env): Either<SjmError, Output> =
        env.index.search(query).map { entries ->
            val width = terminalWidth()
            val sorted = entries.sortedBy { it.coordinates }
            Output(
                lines = if (sorted.isEmpty()) listOf(info("No SkillsJars match \"$query\"."))
                else sorted.flatMap { e ->
                    val version = e.versions.firstOrNull()?.value ?: "-"
                    listOf(
                        info("${e.coordinates}  $version${if (e.securityScanned) "  [scanned]" else ""}"),
                        info("    " + truncate(e.description.ifEmpty { e.name }, width - 4)),
                    )
                },
                json = JsonArray(
                    sorted.map { e ->
                        JsonObject(
                            mapOf(
                                "groupId" to JsonPrimitive(e.coordinates.groupId.value),
                                "artifactId" to JsonPrimitive(e.coordinates.artifactId.value),
                                "name" to JsonPrimitive(e.name),
                                "description" to JsonPrimitive(e.description),
                                "latestVersion" to (e.versions.firstOrNull()?.let { JsonPrimitive(it.value) } ?: JsonNull),
                                "versions" to JsonArray(e.versions.map { JsonPrimitive(it.value) }),
                                "securityScanned" to JsonPrimitive(e.securityScanned),
                                "allowedTools" to JsonObject(e.allowedTools.mapValues { JsonPrimitive(it.value) }),
                            ),
                        )
                    },
                ),
            )
        }
}

class InstallCommand : SjmSubcommand("install") {
    override fun help(context: Context): String =
        "Add SkillsJars to skillsjars.json (latest version unless one is given), then extract every SkillsJar in it"

    private val refs by skillsJarArgument(this).multiple()

    private fun describe(added: Added, width: Int): List<Line> {
        val pom = added.pom
        return listOfNotNull(
            info("Added ${added.gav}"),
            pom?.description?.let { info("  " + truncate(it, width - 2)) },
            pom?.licenses?.takeIf { it.isNotEmpty() }?.let { info("  License: ${it.joinToString(", ")}") },
        ) + pom?.allowedTools.orEmpty().map { (skill, tools) -> info("  Allowed tools ($skill): $tools") }
    }

    override suspend fun execute(env: Env): Either<SjmError, Output> =
        env.project.install(refs).map { r ->
            val width = terminalWidth()
            Output(
                r.added.flatMap { describe(it, width) } +
                    r.missingDependencies.map { (gav, deps) ->
                        warn("$gav depends on ${deps.joinToString(", ")}, which sjm doesn't install for you. Add them with: sjm install ${deps.joinToString(" ")}")
                    } +
                    syncLines(r.synced) +
                    listOfNotNull(
                        r.added.takeIf { it.isNotEmpty() }?.let { info("Skills are instructions your agent will follow. Review them before use.") },
                        r.manifestCreated.takeIf { it }?.let {
                            info("Created ${r.manifestPath}. Commit it, and add the skills dirs to .gitignore: `sjm install` rebuilds them.")
                        },
                    ),
            )
        }
}

class RemoveCommand : SjmSubcommand("remove") {
    override fun help(context: Context): String = "Remove SkillsJars from skillsjars.json and delete their skills"

    private val refs by skillsJarArgument(this).multiple(required = true)

    override suspend fun execute(env: Env): Either<SjmError, Output> =
        env.project.remove(refs.map { it.withoutVersion() }).map { r ->
            Output(r.removed.map { info("Removed $it") } + syncLines(r.synced))
        }
}

class ListCommand : SjmSubcommand("list") {
    override fun help(context: Context): String = "List the SkillsJars in skillsjars.json"

    override suspend fun execute(env: Env): Either<SjmError, Output> =
        env.project.load().flatMap { m ->
            m?.let { Either.Right(it) } ?: Either.Left(SjmError.NoManifest(env.manifestPath.toString()))
        }.map { m ->
            Output(
                lines = m.gavs.map { info("${it.coordinates}  ${it.version}") } + m.dirs.all.map { detail("dir: $it") },
                json = JsonArray(
                    m.gavs.map {
                        JsonObject(
                            mapOf(
                                "groupId" to JsonPrimitive(it.coordinates.groupId.value),
                                "artifactId" to JsonPrimitive(it.coordinates.artifactId.value),
                                "version" to JsonPrimitive(it.version.value),
                            ),
                        )
                    },
                ),
            )
        }
}

class OutdatedCommand : SjmSubcommand("outdated") {
    override fun help(context: Context): String = "Show installed SkillsJars that have newer versions"

    override suspend fun execute(env: Env): Either<SjmError, Output> =
        env.project.status().map { statuses ->
            val outdated = statuses.filter { it.outdated }
            Output(
                lines = outdated.map { info("${it.installed.coordinates}  ${it.installed.version} -> ${it.latest}") } +
                    statuses.filter { it.latest == null }.map { warn("${it.installed.coordinates} is no longer in the skillsjars.com index") } +
                    listOfNotNull(outdated.isEmpty().takeIf { it }?.let { info("All SkillsJars are up to date.") }),
                json = JsonArray(
                    statuses.map {
                        JsonObject(
                            mapOf(
                                "groupId" to JsonPrimitive(it.installed.coordinates.groupId.value),
                                "artifactId" to JsonPrimitive(it.installed.coordinates.artifactId.value),
                                "installed" to JsonPrimitive(it.installed.version.value),
                                "latest" to (it.latest?.let { v -> JsonPrimitive(v.value) } ?: JsonNull),
                                "outdated" to JsonPrimitive(it.outdated),
                            ),
                        )
                    },
                ),
            )
        }
}

class UpdateCommand : SjmSubcommand("update") {
    override fun help(context: Context): String =
        "Update sjm itself, then update SkillsJars (all if none given) to their latest versions"

    override val checksForUpdates: Boolean = false

    private val refs by skillsJarArgument(this).multiple()
    private val noSelfUpdate by option("--no-self-update", help = "Update only the SkillsJars").flag()

    private suspend fun selfUpdate(env: Env): List<Line> {
        if (noSelfUpdate || env("SJM_NO_SELF_UPDATE") != null) return emptyList()
        val updater = SelfUpdater(env.fs, env.releases, currentInstallation(env.fs), ::makeExecutable)
        return when (val r = updater.update()) {
            is Either.Left -> listOf(warn("couldn't update sjm: ${r.value.message}"))
            is Either.Right -> when (val o = r.value) {
                is SelfUpdateOutcome.Updated -> listOf(info("Updated sjm ${o.from} -> ${o.to} (${o.path})"))
                is SelfUpdateOutcome.UpToDate -> listOf(info("sjm ${o.version} is up to date"))
                is SelfUpdateOutcome.Skipped -> listOf(info("Not updating sjm: ${o.reason}"))
            }
        }
    }

    // A self-update failure is only a warning; the exit code reflects the SkillsJars update.
    override suspend fun execute(env: Env): Either<SjmError, Output> {
        val selfLines = selfUpdate(env)
        return env.project.update(refs.map { it.withoutVersion() }).map { r ->
            Output(
                selfLines + when (r) {
                    null -> listOf(detail("No ${env.manifestPath.name} here, so no SkillsJars to update."))
                    else -> r.updated.map { (gav, v) -> info("Updated ${gav.coordinates}  ${gav.version} -> $v") } +
                        r.notInIndex.map { warn("${it.coordinates} is no longer in the skillsjars.com index; leaving it at ${it.version}") } +
                        listOfNotNull(r.updated.isEmpty().takeIf { it }?.let { info("All SkillsJars are up to date.") }) +
                        syncLines(r.synced)
                },
            )
        }
    }
}

fun sjmCli(): SjmCommand =
    SjmCommand().subcommands(
        SearchCommand(), InstallCommand(), RemoveCommand(), ListCommand(), OutdatedCommand(), UpdateCommand(),
    )
