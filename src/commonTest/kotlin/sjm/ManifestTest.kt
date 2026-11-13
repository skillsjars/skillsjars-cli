package sjm

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue

class ManifestTest {
    private val sample = """
        {
          "dirs": [".kiro/skills"],
          "skillsjars": {
            "com.skillsjars:anthropics__skills__pdf": "2026_02_06-1ed29a0",
            "com.jamesward:skills": "0.0.27"
          },
          "note": "kept"
        }
    """.trimIndent()

    @Test
    fun parses() {
        val m = Manifest.parse("m", sample).right()
        assertTrue(m.dirs == NonEmptyList.of(SkillsDir.parse(".kiro/skills").right()))
        assertTrue(m.skillsJars[coordinates("com.jamesward:skills")] == version("0.0.27"))
        assertTrue(m.extra["note"] == JsonPrimitive("kept"))
    }

    @Test
    fun rendersSortedWithTwoSpacesAndKeepsUnknownFields() {
        val rendered = Manifest.parse("m", sample).right().render()
        val expected = """
            {
              "dirs": [
                ".kiro/skills"
              ],
              "skillsjars": {
                "com.jamesward:skills": "0.0.27",
                "com.skillsjars:anthropics__skills__pdf": "2026_02_06-1ed29a0"
              },
              "note": "kept"
            }

        """.trimIndent()
        assertTrue(rendered == expected, rendered)
        assertTrue(Manifest.parse("m", rendered).right() == Manifest.parse("m", sample).right())
    }

    @Test
    fun missingDirsDefaultToAgentsSkills() {
        assertTrue(Manifest.parse("m", "{}").right().dirs == NonEmptyList.of(SkillsDir.Default))
    }

    @Test
    fun rejectsInvalid() {
        listOf(
            "[]", "nope", """{"dirs": "x"}""", """{"dirs": ["/abs"]}""", """{"dirs": ["../up"]}""",
            """{"skillsjars": {"bad": "1"}}""", """{"skillsjars": {"a:b": 1}}""",
        ).forEach { assertTrue(Manifest.parse("m", it) is Either.Left, "should reject $it") }
    }

    @Test
    fun skillsDirs() {
        assertTrue(SkillsDir.parse("./.agents/skills/").right().path == ".agents/skills")
        assertTrue(SkillsDir.parse(".claude\\skills").right().path == ".claude/skills")
        assertTrue(SkillsDir.parse("C:\\x") is Either.Left)
    }
}
