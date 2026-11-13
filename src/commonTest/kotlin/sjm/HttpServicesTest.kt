package sjm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertTrue

/** The HTTP clients against a fake skillsjars.com, Maven repository, and GitHub. */
class HttpServicesTest {
    private val fs = FileSystem.SYSTEM
    private val c = coordinates("com.skillsjars:acme__demo__hello")

    private fun client(followRedirects: Boolean = true, handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine(handler)) {
            this.followRedirects = followRedirects
            expectSuccess = false
        }

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun latestAndNotInIndex() = runTest {
        val http = client { req ->
            when (req.url.encodedPath) {
                "/com.skillsjars/acme__demo__hello/latest" ->
                    respond("""{"groupId":"com.skillsjars","artifactId":"acme__demo__hello","version":"2026_01_01-abc1234"}""", headers = json)
                else -> respond("""{"error":"not found"}""", HttpStatusCode.NotFound, json)
            }
        }
        val index = HttpSkillsJarsIndex(http, "https://sj.test")
        assertTrue(index.latest(c).right() == version("2026_01_01-abc1234"))
        val unknown = coordinates("com.example:nope")
        assertTrue(index.latest(unknown).left() == SjmError.NotInIndex(unknown))
    }

    @Test
    fun serverErrorsCarryTheMessage() = runTest {
        val http = client { respond("""{"error":"Maven Central is down"}""", HttpStatusCode.BadGateway, json) }
        val error = HttpSkillsJarsIndex(http, "https://sj.test").latest(c).left()
        assertTrue(error is SjmError.Http && error.reason == "HTTP 502: Maven Central is down" && error.exitCode == ExitCode.NetworkError)
    }

    @Test
    fun downloadsVerifiesAndCaches() = runTest {
        val bytes = fs.read(fixture("skill-jar")) { readByteArray() }
        val sha1 = bytes.toByteString().sha1().hex()
        val requests = mutableListOf<String>()
        val http = client { req ->
            requests += req.url.encodedPath
            when {
                req.url.encodedPath.endsWith(".jar.sha1") -> respond("$sha1  acme__demo__hello-2.jar")
                req.url.encodedPath.endsWith(".jar") -> respond(bytes)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        withTempDir { cache ->
            val repo = CachingJarRepository(http, fs, cache, "https://maven.test/maven2")
            val g = Gav(c, version("2"))
            val path = repo.jar(g).right()
            assertTrue(fs.read(path) { readByteArray() }.contentEquals(bytes))
            assertTrue(requests.first() == "/maven2/com/skillsjars/acme__demo__hello/2/acme__demo__hello-2.jar")
            val count = requests.size
            assertTrue(repo.jar(g).right() == path && requests.size == count)
        }
    }

    @Test
    fun missingJarIsAnUnknownVersion() = runTest {
        val http = client { respond("", HttpStatusCode.NotFound) }
        withTempDir { cache ->
            val g = Gav(c, version("9"))
            assertTrue(CachingJarRepository(http, fs, cache, "https://maven.test").jar(g).left() == SjmError.UnknownVersion(c, g.version))
        }
    }

    @Test
    fun checksumMismatchIsNotCached() = runTest {
        val http = client { req ->
            if (req.url.encodedPath.endsWith(".sha1")) respond("0000000000000000000000000000000000000000") else respond(byteArrayOf(1, 2, 3))
        }
        withTempDir { cache ->
            val repo = CachingJarRepository(http, fs, cache, "https://maven.test")
            val g = Gav(c, version("2"))
            assertTrue(repo.jar(g).left() is SjmError.ChecksumMismatch)
            assertTrue(fs.listRecursively(cache).none { fs.metadata(it).isRegularFile })
        }
    }

    @Test
    fun releaseFromRedirect() = runTest {
        val tagged = client(followRedirects = false) {
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://github.com/skillsjars/skillsjars-cli/releases/tag/v1.4.2"))
        }
        assertTrue(GitHubReleases(tagged, tagged, UpdateCheck.REPO_URL).latest() == AppVersion(1, 4, 2))
        val none = client(followRedirects = false) { respond("", HttpStatusCode.NotFound) }
        assertTrue(GitHubReleases(none, none, UpdateCheck.REPO_URL).latest() == null)
    }

    @Test
    fun releaseFiles() = runTest {
        val http = client { req ->
            if (req.url.encodedPath == "/o/r/releases/download/v1.2.3/SHA256SUMS") respond("sums") else respond("", HttpStatusCode.NotFound)
        }
        val releases = GitHubReleases(http, http, "https://gh.test/o/r")
        assertTrue(releases.file(AppVersion(1, 2, 3), "SHA256SUMS").right().decodeToString() == "sums")
        assertTrue(releases.file(AppVersion(1, 2, 3), "nope").left() is SjmError.Http)
    }

    @Test
    fun search() = runTest {
        val http = client { req ->
            assertTrue(req.url.parameters["q"] == "pdf" && req.headers[HttpHeaders.Accept] == "application/json")
            respond(
                """{"query":"pdf","skillsJars":[
                  {"groupId":"com.skillsjars","artifactId":"anthropics__skills__pdf","name":"pdf","description":"PDFs","versions":["2","1"],"securityScanned":true,"allowedTools":{"pdf":"Bash"},"future":1},
                  {"groupId":"bad group","artifactId":"x","name":"x","description":"","versions":[],"securityScanned":false,"allowedTools":{}}]}""",
                headers = json,
            )
        }
        val entries = HttpSkillsJarsIndex(http, "https://sj.test").search("pdf").right()
        assertTrue(
            entries == listOf(
                IndexEntry(coordinates("com.skillsjars:anthropics__skills__pdf"), "pdf", "PDFs", listOf(version("2"), version("1")), true, mapOf("pdf" to "Bash")),
            ),
        )
    }

    @Test
    fun prefersSha256AndReadsPoms() = runTest {
        val pom = "<project><name>hello</name><licenses><license><name>MIT</name></license></licenses></project>".encodeToByteArray()
        val http = client { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith(".pom.sha256") -> respond(pom.toByteString().sha256().hex())
                path.endsWith(".pom") -> respond(pom)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        withTempDir { cache ->
            val info = CachingJarRepository(http, fs, cache, "https://maven.test").pom(Gav(c, version("2"))).right()
            assertTrue(info.name == "hello" && info.licenses == listOf("MIT"))
        }
    }
}
