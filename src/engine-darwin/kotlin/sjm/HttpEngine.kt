package sjm

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin

/** NSURLSession: the OS TLS stack and Keychain trust. */
actual val httpEngineName: String = "darwin"

actual fun httpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(Darwin) { config() }
