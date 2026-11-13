package sjm

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path

/** A SkillsJar in the skillsjars.com index. [versions] is newest first. */
data class IndexEntry(
    val coordinates: Coordinates,
    val name: String,
    val description: String,
    val versions: List<Version>,
    val securityScanned: Boolean,
    val allowedTools: Map<String, String>,
)

/** The skillsjars.com index: which SkillsJars sjm supports, and their latest versions. */
interface SkillsJarsIndex {
    /** The latest version, or [SjmError.NotInIndex]. */
    suspend fun latest(coordinates: Coordinates): Either<SjmError, Version>

    /** SkillsJars whose name or description contains [query] (case-insensitive); all of them when null. */
    suspend fun search(query: String?): Either<SjmError, List<IndexEntry>>
}

/** Where SkillsJar files come from, verified against the repository's checksums. */
interface JarRepository {
    /** A local path to the JAR, or [SjmError.UnknownVersion]. */
    suspend fun jar(gav: Gav): Either<SjmError, Path>

    suspend fun pom(gav: Gav): Either<SjmError, PomInfo>
}

/** sjm's own releases. */
interface ReleaseSource {
    /** The latest release, or null when there is none or it can't be determined. */
    suspend fun latest(): AppVersion?

    /** A file attached to [version]'s release. */
    suspend fun file(version: AppVersion, name: String): Either<SjmError, ByteArray>
}

private val lenientJson = Json { ignoreUnknownKeys = true }

/** Runs a request, turning transport failures into [SjmError.Http]. Cancellation is never swallowed. */
private suspend fun <A> request(url: String, block: suspend () -> Either<SjmError, A>): Either<SjmError, A> =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Either.Left(SjmError.Http(url, describe(e)))
    }

/** Transport errors as a user should read them. Ktor's curl engine wraps curl's reason in request details. */
private fun describe(e: Exception): String {
    val raw = e.message ?: e.toString()
    return when {
        "CURLE_SSL_CACERT" in raw ->
            "no usable CA certificates. Check SSL_CERT_FILE and SSL_CERT_DIR, or install your OS's ca-certificates package"
        // Also what curl reports when there is no CA store at all (seen on Linux arm64).
        "CURLE_PEER_FAILED_VERIFICATION" in raw ->
            "TLS certificate verification failed. If this system has no CA certificates, install your OS's " +
                "ca-certificates package, or set SSL_CERT_FILE"
        "Reason: " in raw -> raw.substringAfter("Reason: ")
        else -> raw
    }
}

/** Like [runCatching] returning null on failure, but never swallows cancellation. */
private inline fun <A> orNull(block: () -> A): A? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

private suspend fun HttpResponse.errorReason(): String =
    orNull { lenientJson.decodeFromString(ApiError.serializer(), bodyAsText()).error }
        ?.let { "HTTP ${status.value}: $it" }
        ?: "HTTP ${status.value}"

@Serializable
private data class ApiError(val error: String)

@Serializable
private data class LatestResponse(val version: String)

@Serializable
private data class SearchEntry(
    val groupId: String,
    val artifactId: String,
    val name: String = "",
    val description: String = "",
    val versions: List<String> = emptyList(),
    val securityScanned: Boolean = false,
    val allowedTools: Map<String, String> = emptyMap(),
)

@Serializable
private data class SearchResponse(val skillsJars: List<SearchEntry>)

class HttpSkillsJarsIndex(private val http: HttpClient, private val baseUrl: String) : SkillsJarsIndex {

    override suspend fun latest(coordinates: Coordinates): Either<SjmError, Version> {
        val url = "$baseUrl/${coordinates.groupId}/${coordinates.artifactId}/latest"
        return request(url) {
            val response = http.get(url) { header(HttpHeaders.Accept, "application/json") }
            when (response.status) {
                HttpStatusCode.OK ->
                    Version.parse(lenientJson.decodeFromString(LatestResponse.serializer(), response.bodyAsText()).version)
                        .mapLeft { SjmError.Http(url, "invalid response: $it") }
                HttpStatusCode.NotFound -> Either.Left(SjmError.NotInIndex(coordinates))
                else -> Either.Left(SjmError.Http(url, response.errorReason()))
            }
        }
    }

    override suspend fun search(query: String?): Either<SjmError, List<IndexEntry>> {
        val url = query?.takeIf { it.isNotBlank() }?.let { "$baseUrl/?q=${it.encodeURLParameter()}" } ?: "$baseUrl/"
        return request(url) {
            val response = http.get(url) { header(HttpHeaders.Accept, "application/json") }
            if (response.status != HttpStatusCode.OK) return@request Either.Left(SjmError.Http(url, response.errorReason()))
            val entries = lenientJson.decodeFromString(SearchResponse.serializer(), response.bodyAsText()).skillsJars
            // Entries sjm can't represent (unexpected characters) are left out rather than failing the search.
            Either.Right(
                entries.mapNotNull { e ->
                    val c = Coordinates.parse("${e.groupId}:${e.artifactId}") as? Either.Right ?: return@mapNotNull null
                    IndexEntry(
                        coordinates = c.value,
                        name = e.name,
                        description = e.description,
                        versions = e.versions.mapNotNull { (Version.parse(it) as? Either.Right)?.value },
                        securityScanned = e.securityScanned,
                        allowedTools = e.allowedTools,
                    )
                },
            )
        }
    }
}

/**
 * Downloads JARs and POMs from a Maven repository into a cache, verifying each against the repository's `.sha256`
 * (when published) or `.sha1`. Released artifacts never change, so cached files are used as they are.
 */
class CachingJarRepository(
    private val http: HttpClient,
    private val fs: FileSystem,
    private val cacheDir: Path,
    private val baseUrl: String,
) : JarRepository {

    companion object {
        const val MAX_JAR_BYTES: Long = 50L * 1024 * 1024
    }

    private class Checksum(val algorithm: String, val expected: String, val digest: (ByteArray) -> String)

    private suspend fun expectedChecksum(url: String): Either<SjmError, Checksum> {
        val sha256 = http.get("$url.sha256")
        if (sha256.status == HttpStatusCode.OK) return Either.Right(Checksum("SHA-256", firstToken(sha256.bodyAsText())) { it.toByteString().sha256().hex() })
        val sha1 = http.get("$url.sha1")
        if (sha1.status == HttpStatusCode.OK) return Either.Right(Checksum("SHA-1", firstToken(sha1.bodyAsText())) { it.toByteString().sha1().hex() })
        return Either.Left(SjmError.Http("$url.sha1", "no checksum (HTTP ${sha1.status.value})"))
    }

    private fun firstToken(text: String): String = text.trim().split(Regex("\\s+")).first().lowercase()

    /** The verified, cached file for [gav] with [extension] (`jar` or `pom`). */
    private suspend fun file(gav: Gav, extension: String): Either<SjmError, Path> {
        val (c, v) = gav
        val fileName = "${c.artifactId}-$v.$extension"
        val dir = cacheDir / "jars" / c.groupId.value / c.artifactId.value / v.value
        val cached = dir / fileName
        if (fs.exists(cached)) return Either.Right(cached)

        val url = "$baseUrl/${c.groupId.path}/${c.artifactId}/$v/$fileName"
        return request(url) {
            val response = http.get(url)
            if (response.status == HttpStatusCode.NotFound) return@request Either.Left(SjmError.UnknownVersion(c, v))
            if (response.status != HttpStatusCode.OK) return@request Either.Left(SjmError.Http(url, "HTTP ${response.status.value}"))
            val bytes = response.bodyAsBytes()
            if (bytes.size > MAX_JAR_BYTES) return@request Either.Left(SjmError.TooLarge(gav, bytes.size.toLong(), MAX_JAR_BYTES))

            expectedChecksum(url).flatMap { checksum ->
                val actual = checksum.digest(bytes)
                if (checksum.expected != actual) Either.Left(SjmError.ChecksumMismatch(url, checksum.expected, actual, checksum.algorithm))
                else attempt({ SjmError.Io("can't write $cached: ${it.message}") }) {
                    fs.createDirectories(dir)
                    val temp = dir / "$fileName.tmp"
                    fs.write(temp) { write(bytes) }
                    fs.atomicMove(temp, cached)
                    cached
                }
            }
        }
    }

    override suspend fun jar(gav: Gav): Either<SjmError, Path> = file(gav, "jar")

    override suspend fun pom(gav: Gav): Either<SjmError, PomInfo> =
        file(gav, "pom").flatMap { path ->
            attempt({ SjmError.Io("can't read $path: ${it.message}") }) { PomInfo.parse(fs.read(path) { readUtf8() }) }
        }
}

/**
 * Releases on GitHub. The latest tag comes from the redirect of `<repo>/releases/latest`, which avoids the GitHub
 * API's rate limit; [noRedirects] must not follow redirects. Files are downloaded with [http], which must, since
 * GitHub serves them from another host.
 */
class GitHubReleases(
    private val http: HttpClient,
    private val noRedirects: HttpClient,
    private val repoUrl: String,
) : ReleaseSource {
    override suspend fun latest(): AppVersion? =
        orNull {
            val response = noRedirects.get("$repoUrl/releases/latest")
            response.headers[HttpHeaders.Location]
                ?.substringAfter("/releases/tag/", missingDelimiterValue = "")
                ?.let(AppVersion::parse)
        }

    override suspend fun file(version: AppVersion, name: String): Either<SjmError, ByteArray> {
        val url = "$repoUrl/releases/download/v$version/$name"
        return request(url) {
            val response = http.get(url)
            if (response.status == HttpStatusCode.OK) Either.Right(response.bodyAsBytes())
            else Either.Left(SjmError.Http(url, "HTTP ${response.status.value}"))
        }
    }
}
