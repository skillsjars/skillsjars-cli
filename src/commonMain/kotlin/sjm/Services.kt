package sjm

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path

/** The skillsjars.com index: which SkillsJars sjm supports, and their latest versions. */
interface SkillsJarsIndex {
    /** The latest version, or [SjmError.NotInIndex]. */
    suspend fun latest(coordinates: Coordinates): Either<SjmError, Version>
}

/** Where SkillsJar files come from. Returns a local path to the verified JAR, or [SjmError.UnknownVersion]. */
interface JarRepository {
    suspend fun jar(gav: Gav): Either<SjmError, Path>
}

/** Where sjm releases are published. Null when there is no release or it can't be determined. */
fun interface ReleaseSource {
    suspend fun latest(): AppVersion?
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
            "no usable CA certificates. Install your OS's ca-certificates package, or set SSL_CERT_FILE"
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
}

/** Downloads JARs from a Maven repository into a cache, verifying the `.sha1`. Released artifacts never change. */
class CachingJarRepository(
    private val http: HttpClient,
    private val fs: FileSystem,
    private val cacheDir: Path,
    private val baseUrl: String,
) : JarRepository {

    companion object {
        const val MAX_JAR_BYTES: Long = 50L * 1024 * 1024
    }

    override suspend fun jar(gav: Gav): Either<SjmError, Path> {
        val (c, v) = gav
        val fileName = "${c.artifactId}-$v.jar"
        val dir = cacheDir / "jars" / c.groupId.value / c.artifactId.value / v.value
        val cached = dir / fileName
        if (fs.exists(cached)) return Either.Right(cached)

        val url = "$baseUrl/${c.groupId.path}/${c.artifactId}/$v/$fileName"
        return request(url) {
            val jarResponse = http.get(url)
            if (jarResponse.status == HttpStatusCode.NotFound) return@request Either.Left(SjmError.UnknownVersion(c, v))
            if (jarResponse.status != HttpStatusCode.OK) return@request Either.Left(SjmError.Http(url, "HTTP ${jarResponse.status.value}"))
            val bytes = jarResponse.bodyAsBytes()
            if (bytes.size > MAX_JAR_BYTES) return@request Either.Left(SjmError.TooLarge(gav, bytes.size.toLong(), MAX_JAR_BYTES))

            val shaResponse = http.get("$url.sha1")
            if (shaResponse.status != HttpStatusCode.OK) return@request Either.Left(SjmError.Http("$url.sha1", "HTTP ${shaResponse.status.value}"))
            val expected = shaResponse.bodyAsText().trim().split(Regex("\\s+")).first().lowercase()
            val actual = bytes.toByteString().sha1().hex()
            if (expected != actual) return@request Either.Left(SjmError.ChecksumMismatch(url, expected, actual))

            attempt({ SjmError.Io("can't write $cached: ${it.message}") }) {
                fs.createDirectories(dir)
                val temp = dir / "$fileName.tmp"
                fs.write(temp) { write(bytes) }
                fs.atomicMove(temp, cached)
                cached
            }
        }
    }
}

/**
 * Reads the latest release tag from the redirect of `github.com/<repo>/releases/latest`, which avoids the GitHub API's
 * rate limit. [http] must not follow redirects.
 */
class GitHubReleaseSource(private val http: HttpClient, private val repo: String) : ReleaseSource {
    override suspend fun latest(): AppVersion? =
        orNull {
            val response = http.get("https://github.com/$repo/releases/latest")
            response.headers[HttpHeaders.Location]
                ?.substringAfter("/releases/tag/", missingDelimiterValue = "")
                ?.let(AppVersion::parse)
        }
}
