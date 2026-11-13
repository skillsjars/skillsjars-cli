package sjm

import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/** Project operations against a fake index and repository, on the real filesystem, with JARs built by Gradle. */
class ProjectTest {
    private val fs = FileSystem.SYSTEM
    private val hello = coordinates("com.skillsjars:acme__demo__hello")
    private val empty = coordinates("com.skillsjars:acme__demo__empty")
    private val twin = coordinates("com.skillsjars:acme__demo__twin")
    private val needy = coordinates("com.example:needy")

    /** Versions newest first; [latest] can be changed per test to simulate a new release. */
    private class FakeIndex(var known: Map<Coordinates, List<Version>>) : SkillsJarsIndex {
        override suspend fun latest(coordinates: Coordinates) =
            known[coordinates]?.let { Either.Right(it.first()) } ?: Either.Left(SjmError.NotInIndex(coordinates))
        override suspend fun search(query: String?) = Either.Right(emptyList<IndexEntry>())
    }

    /** What the Maven repository has: fixed, unlike the index. */
    private val published = mapOf(
        hello to listOf(version("2"), version("1")),
        empty to listOf(version("1")),
        twin to listOf(version("1")),
        needy to listOf(version("1")),
    )

    private val index = FakeIndex(
        mapOf(
            hello to listOf(version("2"), version("1")),
            empty to listOf(version("1")),
            twin to listOf(version("1")),
            needy to listOf(version("1")),
        ),
    )

    private val jars = object : JarRepository {
        override suspend fun jar(gav: Gav): Either<SjmError, Path> =
            when {
                gav.version !in published[gav.coordinates].orEmpty() -> Either.Left(SjmError.UnknownVersion(gav.coordinates, gav.version))
                gav.coordinates == empty -> Either.Right(fixture("empty-jar"))
                gav.coordinates == needy -> Either.Right(fixture("needy-jar"))
                else -> Either.Right(fixture("skill-jar"))
            }

        override suspend fun pom(gav: Gav): Either<SjmError, PomInfo> =
            Either.Right(
                PomInfo(
                    name = gav.coordinates.artifactId.value,
                    description = "desc",
                    licenses = listOf("MIT"),
                    allowedTools = emptyMap(),
                    dependencies = if (gav.coordinates == needy) listOf(hello, coordinates("com.example:other")) else emptyList(),
                ),
            )
    }

    private fun project(dir: Path, dirs: NonEmptyList<SkillsDir>? = null) = Project(fs, index, jars, dir / Manifest.FILE_NAME, dirs)

    private fun ref(s: String) = SkillsJarRef.parse(s).right()

    private fun listing(dir: Path): Set<String> =
        fs.listRecursively(dir).filter { fs.metadata(it).isRegularFile }.map { it.relativeTo(dir).toString().replace('\\', '/') }.toSet()

    private fun manifest(dir: Path) = Manifest.parse("m", fs.read(dir / Manifest.FILE_NAME) { readUtf8() }).right()

    private val helloFiles = setOf("skillsjars__acme__demo__hello/SKILL.md", "skillsjars__acme__demo__hello/scripts/run.sh")

    @Test
    fun installsIntoAgentsSkillsAndWritesManifest() = runTest {
        withTempDir { dir ->
            val result = project(dir).install(listOf(ref("acme__demo__hello"))).right()
            assertTrue(result.manifestCreated && result.added.map { it.gav } == listOf(Gav(hello, version("2"))))
            assertTrue(result.added.single().pom?.licenses == listOf("MIT") && result.missingDependencies.isEmpty())
            assertTrue(listing(dir / ".agents" / "skills") == helloFiles)
            assertTrue(manifest(dir).skillsJars == mapOf(hello to version("2")) && manifest(dir).dirs == NonEmptyList.of(SkillsDir.Default))
            assertTrue(!fs.exists(dir / ".agents" / ".skills.sjm-staging"))
        }
    }

    @Test
    fun syncPreservesOtherFoldersAndPrunesRemovedSkills() = runTest {
        withTempDir { dir ->
            val skills = dir / ".kiro" / "skills"
            fs.createDirectories(skills / "my-own-skill")
            fs.write(skills / "my-own-skill" / "SKILL.md") { writeUtf8("mine") }
            fs.createDirectories(skills / "skillsjars__gone__skill")
            fs.write(skills / "skillsjars__gone__skill" / "SKILL.md") { writeUtf8("stale") }

            val result = project(dir, NonEmptyList.of(SkillsDir.parse(".kiro/skills").right())).install(listOf(ref("acme__demo__hello"))).right()

            assertTrue(result.synced.single().removed == listOf(SkillFolder("skillsjars__gone__skill")))
            assertTrue(listing(skills) == helloFiles + "my-own-skill/SKILL.md")
        }
    }

    @Test
    fun failureLeavesSkillsAndManifestUnchanged() = runTest {
        withTempDir { dir ->
            val p = project(dir)
            p.install(listOf(ref("acme__demo__hello"))).right()
            val before = listing(dir / ".agents" / "skills")
            val manifestBefore = fs.read(p.manifestPath) { readUtf8() }

            assertTrue(p.install(listOf(ref("acme__demo__empty"))).left() is SjmError.NoSkills)
            assertTrue(p.install(listOf(ref("acme__demo__twin"))).left() is SjmError.PathConflict)
            assertTrue(p.install(listOf(ref("com.example:unknown"))).left() is SjmError.NotInIndex)
            assertTrue(p.install(listOf(ref("acme__demo__hello@9"))).left() is SjmError.UnknownVersion)

            assertTrue(listing(dir / ".agents" / "skills") == before)
            assertTrue(fs.read(p.manifestPath) { readUtf8() } == manifestBefore)
        }
    }

    @Test
    fun noArgsSyncsTheManifest() = runTest {
        withTempDir { dir ->
            fs.write(dir / Manifest.FILE_NAME) { writeUtf8("""{"skillsjars": {"$hello": "2"}}""") }
            val result = project(dir).install(emptyList()).right()
            assertTrue(!result.manifestCreated && result.added.isEmpty())
            assertTrue(listing(dir / ".agents" / "skills") == helloFiles)
        }
    }

    @Test
    fun warnsAboutDependenciesNotInTheManifest() = runTest {
        withTempDir { dir ->
            val p = project(dir)
            p.install(listOf(ref("acme__demo__hello"))).right()
            val result = p.install(listOf(ref("com.example:needy"))).right()
            assertTrue(result.missingDependencies == mapOf(Gav(needy, version("1")) to listOf(coordinates("com.example:other"))))
        }
    }

    @Test
    fun removeDeletesSkillsAndTheEntry() = runTest {
        withTempDir { dir ->
            val p = project(dir)
            p.install(listOf(ref("acme__demo__hello"))).right()
            val result = p.remove(listOf(hello)).right()
            assertTrue(result.removed == listOf(Gav(hello, version("2"))))
            assertTrue(result.synced.single().removed == listOf(SkillFolder("skillsjars__acme__demo__hello")))
            assertTrue(manifest(dir).skillsJars.isEmpty() && listing(dir / ".agents" / "skills").isEmpty())
            assertTrue(p.remove(listOf(hello)).left() == SjmError.NotInstalled(hello))
        }
    }

    @Test
    fun commandsThatNeedAManifest() = runTest {
        withTempDir { dir ->
            assertTrue(project(dir).remove(listOf(hello)).left() is SjmError.NoManifest)
            assertTrue(project(dir).status().left() is SjmError.NoManifest)
            assertTrue(project(dir).update(emptyList()).right() == null)
        }
    }

    @Test
    fun statusAndUpdate() = runTest {
        withTempDir { dir ->
            val p = project(dir)
            p.install(listOf(ref("acme__demo__hello@1"))).right()
            assertTrue(p.status().right() == listOf(VersionStatus(Gav(hello, version("1")), version("2"))))

            val updated = p.update(emptyList()).right()!!
            assertTrue(updated.updated == listOf(Gav(hello, version("1")) to version("2")) && updated.notInIndex.isEmpty())
            assertTrue(manifest(dir).skillsJars == mapOf(hello to version("2")))
            assertTrue(p.status().right().none { it.outdated })
            assertTrue(p.update(listOf(coordinates("com.example:nope"))).left() == SjmError.NotInstalled(coordinates("com.example:nope")))
        }
    }

    @Test
    fun updateLeavesSkillsJarsThatLeftTheIndexPinned() = runTest {
        withTempDir { dir ->
            val p = project(dir)
            p.install(listOf(ref("acme__demo__hello@1"))).right()
            val saved = index.known
            index.known = saved - hello
            try {
                assertTrue(p.status().right() == listOf(VersionStatus(Gav(hello, version("1")), null)))
                val r = p.update(emptyList()).right()!!
                assertTrue(r.updated.isEmpty() && r.notInIndex == listOf(Gav(hello, version("1"))))
                assertTrue(manifest(dir).skillsJars == mapOf(hello to version("1")))
            } finally {
                index.known = saved
            }
        }
    }
}
