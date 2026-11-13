package sjm

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** The last update check, stored in the cache dir so the network is asked at most once per [UpdateCheck.interval]. */
@Serializable
private data class LastCheck(val checkedAtEpochSeconds: Long, val latest: String?)

/**
 * The new-version notice. A fresh cached result is used without any network call. Otherwise the check runs alongside
 * the command, bounded by [timeout], and never fails it: any error means no notice. A check that times out isn't
 * cached, so the next run tries again.
 */
class UpdateCheck(
    private val fs: FileSystem,
    private val cacheDir: Path,
    private val releases: ReleaseSource,
    private val nowEpochSeconds: () -> Long,
) {
    companion object {
        const val REPO_URL = "https://github.com/skillsjars/skillsjars-cli"
        val timeout: Duration = 2.seconds
        val interval: Duration = 24.hours

        /** The one-line notice to print, or null when [current] is up to date (or a dev build). */
        fun notice(current: AppVersion?, latest: AppVersion?): String? =
            if (current != null && latest != null && latest > current) "sjm $latest is available (you have $current). Run: sjm update"
            else null
    }

    private val file: Path get() = cacheDir / "update-check.json"
    private val json = Json { ignoreUnknownKeys = true }

    private fun cached(): LastCheck? =
        try {
            json.decodeFromString(LastCheck.serializer(), fs.read(file) { readUtf8() })
                .takeIf { nowEpochSeconds() - it.checkedAtEpochSeconds in 0 until interval.inWholeSeconds }
        } catch (_: Exception) {
            null
        }

    private fun store(latest: AppVersion?) {
        try {
            fs.createDirectories(cacheDir)
            val temp = cacheDir / "update-check.json.tmp"
            fs.write(temp) { writeUtf8(json.encodeToString(LastCheck.serializer(), LastCheck(nowEpochSeconds(), latest?.toString()))) }
            fs.atomicMove(temp, file)
        } catch (_: Exception) {
            // Not caching only means checking again next time.
        }
    }

    /** Runs [command] and returns its result with the latest release, when known. */
    suspend fun <A> alongside(command: suspend () -> A): Pair<A, AppVersion?> {
        cached()?.let { return command() to it.latest?.let(AppVersion::parse) }
        return coroutineScope {
            // Wrapped so a timeout (null) is distinguishable from "no release" (Checked(null)).
            val check = async { withTimeoutOrNull(timeout) { Checked(releases.latest()) } }
            val result = command()
            val checked = check.await()
            checked?.let { store(it.latest) }
            result to checked?.latest
        }
    }

    private data class Checked(val latest: AppVersion?)
}
