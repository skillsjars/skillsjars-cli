package sjm

import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertTrue

class SelfUpdateTest {
    private val fs = FileSystem.SYSTEM
    private val asset = "sjm-linux-x64"
    private val newBinary = "new sjm".encodeToByteArray()

    private class FakeReleases(val latest: AppVersion?, val files: Map<String, ByteArray>) : ReleaseSource {
        override suspend fun latest() = latest
        override suspend fun file(version: AppVersion, name: String): Either<SjmError, ByteArray> =
            files[name]?.let { Either.Right(it) } ?: Either.Left(SjmError.Http(name, "HTTP 404"))
    }

    private fun sums(bytes: ByteArray) = "${bytes.toByteString().sha256().hex()}  $asset\nffff  other\n".encodeToByteArray()

    private fun releases(binary: ByteArray = newBinary, latest: AppVersion? = AppVersion(1, 1, 0)) =
        FakeReleases(latest, mapOf(SelfUpdater.CHECKSUMS to sums(newBinary), asset to binary))

    private fun installed(executable: Path, windows: Boolean = false, version: AppVersion? = AppVersion(1, 0, 0)) =
        Installation(version, asset, executable, windows)

    private fun withExecutable(block: suspend (Path) -> Unit) = runTest {
        withTempDir { dir ->
            val exe = dir / "sjm"
            fs.write(exe) { writeUtf8("old sjm") }
            block(exe)
        }
    }

    private fun content(path: Path) = fs.read(path) { readUtf8() }

    @Test
    fun replacesTheBinary() = withExecutable { exe ->
        val made = mutableListOf<Path>()
        val outcome = SelfUpdater(fs, releases(), installed(exe)) { made += it }.update().right()
        assertTrue(outcome == SelfUpdateOutcome.Updated(AppVersion(1, 0, 0), AppVersion(1, 1, 0), exe))
        assertTrue(content(exe) == "new sjm" && made.size == 1 && fs.list(exe.parent!!) == listOf(exe))
    }

    @Test
    fun windowsMovesTheRunningExeAside() = withExecutable { exe ->
        SelfUpdater(fs, releases(), installed(exe, windows = true)) {}.update().right()
        val old = SelfUpdater.oldExecutable(exe)
        assertTrue(content(exe) == "new sjm" && content(old) == "old sjm")
        SelfUpdater.cleanUpAfterUpdate(fs, installed(exe, windows = true))
        assertTrue(!fs.exists(old))
    }

    @Test
    fun checksumMismatchKeepsTheOldBinary() = withExecutable { exe ->
        val error = SelfUpdater(fs, releases(binary = "tampered".encodeToByteArray()), installed(exe)) {}.update().left()
        assertTrue(error is SjmError.ChecksumMismatch && content(exe) == "old sjm" && fs.list(exe.parent!!) == listOf(exe))
    }

    @Test
    fun skipsAndUpToDate() = withExecutable { exe ->
        val up = SelfUpdater(fs, releases(latest = AppVersion(1, 0, 0)), installed(exe)) {}.update().right()
        assertTrue(up == SelfUpdateOutcome.UpToDate(AppVersion(1, 0, 0)))
        val dev = SelfUpdater(fs, releases(), installed(exe, version = null)) {}.update().right()
        assertTrue(dev is SelfUpdateOutcome.Skipped)
        assertTrue(SelfUpdater(fs, releases(latest = null), installed(exe)) {}.update().left() is SjmError.Http)
        assertTrue(content(exe) == "old sjm")
    }

    @Test
    fun packageManagersAndChecksums() {
        assertTrue(SelfUpdater.packageManagerUpgrade("/opt/homebrew/Cellar/sjm/1.0.0/bin/sjm".toPath()) == "brew upgrade sjm")
        assertTrue(SelfUpdater.packageManagerUpgrade("C:\\Users\\me\\scoop\\apps\\sjm\\current\\sjm.exe".toPath()) == "scoop update sjm")
        assertTrue(SelfUpdater.packageManagerUpgrade("/home/me/.local/bin/sjm".toPath()) == null)
        val hash = "a".repeat(64)
        assertTrue(SelfUpdater.parseChecksums("$hash  sjm-linux-x64\n$hash *sjm-windows-x64.exe\nbad line\n") == mapOf("sjm-linux-x64" to hash, "sjm-windows-x64.exe" to hash))
    }
}
