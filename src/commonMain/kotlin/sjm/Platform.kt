package sjm

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import okio.Path

/** An environment variable, or null when unset or empty. */
expect fun env(name: String): String?

expect fun stderrIsTty(): Boolean

/** The per-user cache dir for sjm, or null when the OS doesn't say where it is. */
expect fun userCacheDir(): Path?

/** The Ktor engine this build uses (a spike variant). */
expect val httpEngineName: String

expect fun httpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient

expect fun writeStderr(text: String)
