package sjm

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.curl.Curl
import io.ktor.client.engine.curl.CurlClientEngineConfig
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Ktor's curl engine links a static libcurl + OpenSSL whose built-in CA paths are Debian-style, so HTTPS fails on
 * distros that keep the bundle elsewhere (Fedora, RHEL). The first bundle found wins (the list Go's crypto/x509
 * uses).
 */
private val systemCaBundles = listOf(
    "/etc/ssl/certs/ca-certificates.crt",
    "/etc/pki/tls/certs/ca-bundle.crt",
    "/etc/ssl/ca-bundle.pem",
    "/etc/pki/tls/cacert.pem",
    "/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem",
    "/etc/ssl/cert.pem",
).map { it.toPath() }

/** Where the CA certificates come from, in order of preference. */
sealed interface CaSource {
    data class File(val path: String) : CaSource
    data class Dir(val path: String) : CaSource
    data object None : CaSource
}

/**
 * `SSL_CERT_FILE` / `SSL_CERT_DIR`, else the system bundle, else Mozilla's bundle compiled into sjm, written once to
 * the cache dir (curl reads CA certificates from a file). [None] keeps curl's default.
 */
fun caSource(
    fs: FileSystem,
    certFile: String?,
    certDir: String?,
    cacheDir: Path?,
    systemBundles: List<Path> = systemCaBundles,
): CaSource =
    when {
        certFile != null -> CaSource.File(certFile)
        certDir != null -> CaSource.Dir(certDir)
        else -> systemBundles.firstOrNull { fs.exists(it) }?.let { CaSource.File(it.toString()) }
            ?: cacheDir?.let { embeddedBundle(fs, it) }
            ?: CaSource.None
    }

private fun embeddedBundle(fs: FileSystem, cacheDir: Path): CaSource? {
    val dir = cacheDir / "ca"
    val bundle = dir / "mozilla-$EMBEDDED_CA_BUNDLE_SHA256.pem"
    return try {
        if (!fs.exists(bundle)) {
            fs.createDirectories(dir)
            val temp = dir / "${bundle.name}.tmp"
            fs.write(temp) { writeUtf8(embeddedCaBundle) }
            fs.atomicMove(temp, bundle)
        }
        CaSource.File(bundle.toString())
    } catch (_: Exception) {
        null
    }
}

private fun CurlClientEngineConfig.configureCa() {
    when (val source = caSource(FileSystem.SYSTEM, env("SSL_CERT_FILE"), env("SSL_CERT_DIR"), userCacheDir())) {
        is CaSource.File -> caInfo = source.path
        is CaSource.Dir -> caPath = source.path
        CaSource.None -> Unit
    }
}

actual val httpEngineName: String = "curl"

actual fun httpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Curl) {
        config()
        engine { configureCa() }
    }
