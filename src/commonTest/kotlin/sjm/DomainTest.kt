package sjm

import kotlin.test.Test
import kotlin.test.assertTrue

class DomainTest {
    @Test
    fun artifactIdDefaultsToSkillsJarsGroup() {
        val ref = SkillsJarRef.parse("anthropics__skills__pdf").right()
        assertTrue(ref.coordinates == coordinates("com.skillsjars:anthropics__skills__pdf") && ref.version == null)
    }

    @Test
    fun groupAndArtifact() {
        val ref = SkillsJarRef.parse("com.jamesward:skills").right()
        assertTrue(ref.coordinates == coordinates("com.jamesward:skills") && ref.version == null)
    }

    @Test
    fun versionWithColonOrAt() {
        assertTrue(SkillsJarRef.parse("com.jamesward:skills:0.0.27").right().version == version("0.0.27"))
        assertTrue(SkillsJarRef.parse("com.jamesward:skills@0.0.27").right().version == version("0.0.27"))
        assertTrue(SkillsJarRef.parse("anthropics__skills__pdf@2026_02_06-1ed29a0").right().version == version("2026_02_06-1ed29a0"))
    }

    @Test
    fun rejectsBadInput() {
        listOf("", "a:b:c:d", "a:b:1@2", "a/b", "../x", "a:..", "a@b@c", "a b").forEach {
            assertTrue(SkillsJarRef.parse(it) is Either.Left, "should reject '$it'")
        }
    }

    @Test
    fun appVersions() {
        assertTrue(AppVersion.parse("v1.2.10")!! > AppVersion.parse("1.2.9")!!)
        assertTrue(AppVersion.parse("0.0.0-dev") == null)
        assertTrue(AppVersion.parse("1.2") == null)
    }

    @Test
    fun updateNotice() {
        val current = AppVersion.parse("0.2.1")
        assertTrue(UpdateCheck.notice(current, AppVersion.parse("0.3.0")) == "sjm 0.3.0 is available (you have 0.2.1). Run: sjm update")
        assertTrue(UpdateCheck.notice(current, current) == null)
        assertTrue(UpdateCheck.notice(null, AppVersion.parse("0.3.0")) == null)
        assertTrue(UpdateCheck.notice(current, null) == null)
    }
}
