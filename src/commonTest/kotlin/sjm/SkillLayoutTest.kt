package sjm

import kotlin.test.Test
import kotlin.test.assertTrue

class SkillLayoutTest {
    private val g = gav("com.skillsjars:acme__demo__hello:1")

    private fun items(vararg entries: String) = entries.map { ZipItem(it, 10) }

    private fun layout(plan: JarPlan): Map<String, List<String>> =
        plan.skills.all.associate { s -> s.folder.name to s.files.map { it.relative.all.joinToString("/") } }

    @Test
    fun flattensSkillRootsAndIgnoresOtherFiles() {
        val plan = SkillLayout.planJar(
            g,
            items(
                "META-INF/MANIFEST.MF",
                "META-INF/skills/acme/demo/hello/SKILL.md",
                "META-INF/skills/acme/demo/hello/scripts/run.sh",
                "META-INF/resources/skills/acme/demo/group/other/SKILL.md",
                "META-INF/skills/acme/demo/README.md",
            ),
        ).right()
        assertTrue(
            layout(plan) == mapOf(
                "skillsjars__acme__demo__group__other" to listOf("SKILL.md"),
                "skillsjars__acme__demo__hello" to listOf("SKILL.md", "scripts/run.sh"),
            ),
        )
    }

    @Test
    fun nestedRootsUseTheDeepestRoot() {
        val plan = SkillLayout.planJar(
            g,
            items("META-INF/skills/a/SKILL.md", "META-INF/skills/a/b/SKILL.md", "META-INF/skills/a/b/x.txt"),
        ).right()
        assertTrue(layout(plan) == mapOf("skillsjars__a" to listOf("SKILL.md"), "skillsjars__a__b" to listOf("SKILL.md", "x.txt")))
    }

    @Test
    fun noSkills() {
        assertTrue(SkillLayout.planJar(g, items("META-INF/MANIFEST.MF", "META-INF/skills/SKILL.md")).left() == SjmError.NoSkills(g))
    }

    @Test
    fun rejectsUnsafePaths() {
        listOf(
            "META-INF/skills/a/../../../etc/SKILL.md",
            "META-INF/skills/a/./SKILL.md",
            "META-INF/skills/a//SKILL.md",
            "META-INF/skills/a\\..\\SKILL.md",
            "META-INF/skills/C:/SKILL.md",
        ).forEach { entry ->
            assertTrue(SkillLayout.planJar(g, items("META-INF/skills/ok/SKILL.md", entry)).left() == SjmError.UnsafePath(g, entry), entry)
        }
    }

    @Test
    fun rejectsDecompressionBombs() {
        val big = listOf(ZipItem("META-INF/skills/a/SKILL.md", SkillLayout.MAX_UNCOMPRESSED_BYTES + 1))
        assertTrue(SkillLayout.planJar(g, big).left() is SjmError.TooLarge)
    }

    @Test
    fun conflictsBetweenJars() {
        val other = gav("com.example:other:2")
        val a = SkillLayout.planJar(g, items("META-INF/skills/x/SKILL.md")).right()
        val b = SkillLayout.planJar(other, items("META-INF/skills/x/SKILL.md")).right()
        assertTrue(SkillLayout.checkConflicts(listOf(a, b)).left() == SjmError.PathConflict(SkillFolder("skillsjars__x"), g, other))
    }
}
