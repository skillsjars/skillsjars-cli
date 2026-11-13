package sjm

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

object UpdateCheck {
    const val REPO = "skillsjars/skillsjars-cli"
    val timeout: Duration = 2.seconds

    /** The one-line notice to print, or null when [current] is up to date (or a dev build). */
    fun notice(current: AppVersion?, latest: AppVersion?): String? =
        if (current != null && latest != null && latest > current) "sjm $latest is available (you have $current). Run: sjm update"
        else null

    /**
     * Runs [command] while checking for a newer release. The check is bounded by [timeout] and never fails the
     * command: any error means no notice.
     */
    suspend fun <A> alongside(source: ReleaseSource?, command: suspend () -> A): Pair<A, AppVersion?> =
        coroutineScope {
            val check = source?.let { async { withTimeoutOrNull(timeout) { it.latest() } } }
            val result = command()
            result to check?.await()
        }
}
