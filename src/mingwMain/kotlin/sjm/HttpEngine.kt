package sjm

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.winhttp.WinHttp

/** WinHTTP: the OS TLS stack and the Windows certificate store. */
actual val httpEngineName: String = "winhttp"

actual fun httpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(WinHttp) { config() }
