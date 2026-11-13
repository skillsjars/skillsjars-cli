package sjm

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertTrue

class UpdateCheckTest {
    private val fs = FileSystem.SYSTEM

    private class CountingReleases(val answer: suspend () -> AppVersion?) : ReleaseSource {
        var calls = 0
        override suspend fun latest(): AppVersion? {
            calls++
            return answer()
        }
        override suspend fun file(version: AppVersion, name: String): Either<SjmError, ByteArray> = error("unused")
    }

    @Test
    fun checksOncePerInterval() = runTest {
        withTempDir { cache ->
            val releases = CountingReleases { AppVersion(1, 2, 3) }
            val now = 1_000_000L
            assertTrue(UpdateCheck(fs, cache, releases) { now }.alongside { "a" } == ("a" to AppVersion(1, 2, 3)))
            assertTrue(UpdateCheck(fs, cache, releases) { now + 3600 }.alongside { "b" } == ("b" to AppVersion(1, 2, 3)))
            assertTrue(releases.calls == 1)
            UpdateCheck(fs, cache, releases) { now + UpdateCheck.interval.inWholeSeconds }.alongside { "c" }
            assertTrue(releases.calls == 2)
        }
    }

    @Test
    fun noReleaseIsCachedButATimeoutIsNot() = runTest {
        withTempDir { cache ->
            val none = CountingReleases { null }
            assertTrue(UpdateCheck(fs, cache, none) { 0 }.alongside { 1 } == (1 to null))
            UpdateCheck(fs, cache, none) { 10 }.alongside { 1 }
            assertTrue(none.calls == 1)

            withTempDir { other ->
                val hanging = CountingReleases { awaitCancellation() }
                assertTrue(UpdateCheck(fs, other, hanging) { 0 }.alongside { 2 } == (2 to null))
                UpdateCheck(fs, other, hanging) { 10 }.alongside { 2 }
                assertTrue(hanging.calls == 2)
            }
        }
    }
}
