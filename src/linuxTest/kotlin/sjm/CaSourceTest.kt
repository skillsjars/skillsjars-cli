package sjm

import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertTrue

class CaSourceTest {
    private val fs = FileSystem.SYSTEM

    @Test
    fun environmentWins() {
        withTempDir { dir ->
            assertTrue(caSource(fs, "/x.pem", "/d", dir) == CaSource.File("/x.pem"))
            assertTrue(caSource(fs, null, "/d", dir) == CaSource.Dir("/d"))
        }
    }

    @Test
    fun systemBundleBeforeEmbedded() {
        withTempDir { dir ->
            val system = dir / "system.pem"
            fs.write(system) { writeUtf8("x") }
            assertTrue(caSource(fs, null, null, dir / "cache", listOf(dir / "missing.pem", system)) == CaSource.File(system.toString()))
            assertTrue(!fs.exists(dir / "cache"))
        }
    }

    @Test
    fun embeddedBundleWhenNoSystemBundle() {
        withTempDir { dir ->
            val source = caSource(fs, null, null, dir, listOf(dir / "missing.pem"))
            val path = (source as CaSource.File).path
            val text = fs.read(path.toPath()) { readUtf8() }
            assertTrue(text == embeddedCaBundle && text.split("BEGIN CERTIFICATE").size > 100)
            assertTrue(caSource(fs, null, null, dir, emptyList()) == source)
        }
    }

    @Test
    fun noneWithoutACacheDir() {
        assertTrue(caSource(fs, null, null, null, emptyList()) == CaSource.None)
    }
}
