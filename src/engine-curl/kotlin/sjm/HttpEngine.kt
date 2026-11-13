package sjm

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.curl.Curl
import io.ktor.client.engine.curl.CurlClientEngineConfig
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Ktor's curl engine links a static libcurl + OpenSSL whose built-in CA paths are Debian-style, so HTTPS fails on
 * distros that keep the bundle elsewhere (Fedora, RHEL). Use `SSL_CERT_FILE` / `SSL_CERT_DIR`, else the first bundle
 * found (the list Go's crypto/x509 uses). On Windows curl uses Schannel and the Windows certificate store, so none
 * of these exist and curl's default is kept.
 */
private val caBundles = listOf(
    "/etc/ssl/certs/ca-certificates.crt",
    "/etc/pki/tls/certs/ca-bundle.crt",
    "/etc/ssl/ca-bundle.pem",
    "/etc/pki/tls/cacert.pem",
    "/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem",
    "/etc/ssl/cert.pem",
)

private fun CurlClientEngineConfig.configureCa() {
    val certFile = env("SSL_CERT_FILE")
    val certDir = env("SSL_CERT_DIR")
    when {
        certFile != null -> caInfo = certFile
        certDir != null -> caPath = certDir
        else -> caBundles.firstOrNull { FileSystem.SYSTEM.exists(it.toPath()) }?.let { caInfo = it }
    }
}

actual val httpEngineName: String = "curl"

actual fun httpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Curl) {
        config()
        engine { configureCa() }
    }
