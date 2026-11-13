package sjm

import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/** Install against fake index/repository and the real filesystem, with JARs built by Gradle. */
class InstallTest {
    private val fs = FileSystem.SYSTEM
    private val hello = coordinates("com.skillsjars:acme__demo__hello")
    private val empty = coordinates("com.skillsjars:acme__demo__empty")
    private val twin = coordinates("com.skillsjars:acme__demo__twin")

    private val index = object : SkillsJarsIndex {
        val known = mapOf(hello to listOf(version("2")), empty to listOf(version("1")), twin to listOf(version("1")))
        override suspend fun latest(coordinates: Coordinates) =
            known[coordinates]?.let { Either.Right(it.first()) } ?: Either.Left(SjmError.NotInIndex(coordinates))
    }

    private val jars = object : JarRepository {
        override suspend fun jar(gav: Gav): Either<SjmError, Path> =
            when {
                gav.version !in index.known.getValue(gav.coordinates) -> Either.Left(SjmError.UnknownVersion(gav.coordinates, gav.version))
                gav.coordinates == empty -> Either.Right(fixture("empty-jar"))
                else -> Either.Right(fixture("skill-jar"))
            }
    }

    private val installer = Installer(fs, index, jars)

    private fun ref(s: String) = SkillsJarRef.parse(s).right()

    private fun listing(dir: Path): Set<String> =
        fs.listRecursively(dir).filter { fs.metadata(it).isRegularFile }.map { it.relativeTo(dir).toString().replace('\\', '/') }.toSet()

    @Test
    fun installsIntoAgentsSkillsAndWritesManifest() = runTest {
        withTempDir { project ->
            val result = installer.install(project / Manifest.FILE_NAME, listOf(ref("acme__demo__hello")), null).right()
            assertTrue(result.manifestCreated && result.added == listOf(Gav(hello, version("2"))))
            assertTrue(
                listing(project / ".agents" / "skills") == setOf(
                    "skillsjars__acme__demo__hello/SKILL.md",
                    "skillsjars__acme__demo__hello/scripts/run.sh",
                ),
            )
            val manifest = Manifest.parse("m", fs.read(project / Manifest.FILE_NAME) { readUtf8() }).right()
            assertTrue(manifest.skillsJars == mapOf(hello to version("2")) && manifest.dirs == NonEmptyList.of(SkillsDir.Default))
            assertTrue(!fs.exists(project / ".agents" / ".skills.sjm-staging"))
        }
    }

    @Test
    fun syncPreservesOtherFoldersAndPrunesRemovedSkills() = runTest {
        withTempDir { project ->
            val skills = project / ".kiro" / "skills"
            fs.createDirectories(skills / "my-own-skill")
            fs.write(skills / "my-own-skill" / "SKILL.md") { writeUtf8("mine") }
            fs.createDirectories(skills / "skillsjars__gone__skill")
            fs.write(skills / "skillsjars__gone__skill" / "SKILL.md") { writeUtf8("stale") }
            val dirs = NonEmptyList.of(SkillsDir.parse(".kiro/skills").right())

            val result = installer.install(project / Manifest.FILE_NAME, listOf(ref("acme__demo__hello")), dirs).right()

            assertTrue(result.synced.single().removed == listOf(SkillFolder("skillsjars__gone__skill")))
            assertTrue(
                listing(skills) == setOf(
                    "my-own-skill/SKILL.md",
                    "skillsjars__acme__demo__hello/SKILL.md",
                    "skillsjars__acme__demo__hello/scripts/run.sh",
                ),
            )
        }
    }

    @Test
    fun failureLeavesSkillsAndManifestUnchanged() = runTest {
        withTempDir { project ->
            val manifestPath = project / Manifest.FILE_NAME
            installer.install(manifestPath, listOf(ref("acme__demo__hello")), null).right()
            val before = listing(project / ".agents" / "skills")
            val manifestBefore = fs.read(manifestPath) { readUtf8() }

            assertTrue(installer.install(manifestPath, listOf(ref("acme__demo__empty")), null).left() is SjmError.NoSkills)
            assertTrue(installer.install(manifestPath, listOf(ref("acme__demo__twin")), null).left() is SjmError.PathConflict)
            assertTrue(installer.install(manifestPath, listOf(ref("com.example:unknown")), null).left() is SjmError.NotInIndex)
            assertTrue(installer.install(manifestPath, listOf(ref("acme__demo__hello@9")), null).left() is SjmError.UnknownVersion)

            assertTrue(listing(project / ".agents" / "skills") == before)
            assertTrue(fs.read(manifestPath) { readUtf8() } == manifestBefore)
        }
    }

    @Test
    fun noArgsSyncsTheManifest() = runTest {
        withTempDir { project ->
            val manifestPath = project / Manifest.FILE_NAME
            fs.write(manifestPath) { writeUtf8("""{"skillsjars": {"$hello": "2"}}""") }
            val result = installer.install(manifestPath, emptyList(), null).right()
            assertTrue(!result.manifestCreated && result.added.isEmpty())
            assertTrue(fs.exists(project / ".agents" / "skills" / "skillsjars__acme__demo__hello" / "SKILL.md"))
        }
    }
}
