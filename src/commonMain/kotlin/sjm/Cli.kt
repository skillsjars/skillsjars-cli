package sjm

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import okio.FileSystem
import okio.Path.Companion.toPath

/** Where sjm talks to, overridable for tests and mirrors. */
data class Endpoints(val api: String, val mavenRepo: String) {
    companion object {
        fun fromEnv(): Endpoints = Endpoints(
            api = (env("SJM_API_URL") ?: "https://www.skillsjars.com").trimEnd('/'),
            mavenRepo = (env("SJM_MAVEN_REPO") ?: "https://repo1.maven.org/maven2").trimEnd('/'),
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

private fun <A> Either<String, A>.orUsageError(): A =
    when (this) {
        is Either.Left -> throw com.github.ajalt.clikt.core.BadParameterValue(value)
        is Either.Right -> value
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

    override fun help(context: Context): String = "SkillsJars Manager: install Agent Skills from skillsjars.com"

    override suspend fun run() = Unit
}

class InstallCommand : CommandBase(name = "install") {
    override fun help(context: Context): String =
        "Add SkillsJars to skillsjars.json (latest version unless one is given), then extract every SkillsJar in it"

    private val refs by argument(name = "SKILLSJAR", help = "artifactId, groupId:artifactId, or groupId:artifactId:version; append @version to pin")
        .convert { SkillsJarRef.parse(it).orUsageError() }
        .multiple()

    private val dirs by option("--dir", help = "Skills directory (repeatable). Default: the manifest's dirs, else .agents/skills")
        .convert { SkillsDir.parse(it).orUsageError() }
        .multiple()

    private val manifest by option("--manifest", help = "Manifest path").default(Manifest.FILE_NAME)

    private val noUpdateCheck by option("--no-update-check", help = "Don't check for a newer sjm").flag()

    private fun updateCheckEnabled(): Boolean =
        !noUpdateCheck && env("SJM_NO_UPDATE_CHECK") == null && env("CI") == null && stderrIsTty()

    private fun report(result: InstallResult) {
        result.added.forEach { echo("Added $it") }
        result.synced.forEach { s ->
            echo("${s.dir}: ${s.written.size} skill(s)")
            s.removed.forEach { echo("  removed $it") }
        }
        if (result.added.isNotEmpty()) {
            echo("Skills are instructions your agent will follow. Review them before use.")
        }
        if (result.manifestCreated) {
            echo("Created ${result.manifestPath}. Commit it, and add the skills dirs to .gitignore: they are rebuilt by `sjm install`.")
        }
    }

    override suspend fun run() {
        val fs = FileSystem.SYSTEM
        val endpoints = Endpoints.fromEnv()
        val cacheDir = userCacheDir() ?: run {
            echo("sjm: can't find a cache directory (set HOME, or LOCALAPPDATA on Windows)", err = true)
            throw ProgramResult(ExitCode.UserError.code)
        }
        val manifestPath = manifest.toPath().let { if (it.isAbsolute) it else fs.canonicalize(".".toPath()) / it }

        val (outcome, latest) = sjmHttpClient(followRedirects = true).use { http ->
            sjmHttpClient(followRedirects = false).use { noRedirects ->
                val releases = if (updateCheckEnabled()) GitHubReleaseSource(noRedirects, UpdateCheck.REPO) else null
                UpdateCheck.alongside(releases) {
                    Installer(
                        fs = fs,
                        index = HttpSkillsJarsIndex(http, endpoints.api),
                        jars = CachingJarRepository(http, fs, cacheDir, endpoints.mavenRepo),
                    ).install(manifestPath, refs, NonEmptyList.fromList(dirs))
                }
            }
        }

        UpdateCheck.notice(AppVersion.parse(SJM_VERSION), latest)?.let { echo(it, err = true) }

        when (outcome) {
            is Either.Right -> report(outcome.value)
            is Either.Left -> {
                echo("sjm: ${outcome.value.message}", err = true)
                throw ProgramResult(outcome.value.exitCode.code)
            }
        }
    }
}

fun sjmCli(): SjmCommand = SjmCommand().subcommands(InstallCommand())
