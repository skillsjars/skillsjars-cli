package sjm

import kotlin.test.Test
import kotlin.test.assertTrue

class PomTest {
    @Test
    fun skillsJarPom() {
        val pom = PomInfo.parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
              <parent><groupId>p</groupId><artifactId>parent</artifactId><name>parent name</name></parent>
              <groupId>com.skillsjars</groupId>
              <artifactId>anthropics__skills__pdf</artifactId>
              <name>pdf</name>
              <description>Use this skill for PDFs &amp; forms.</description>
              <licenses>
                <license><name>MIT License</name><url>https://opensource.org/licenses/MIT</url></license>
                <license><name>Apache-2.0</name></license>
              </licenses>
              <developers><developer><name>SkillsJars</name></developer></developers>
              <properties>
                <skillsjars.skill.pdf.allowed-tools>Bash Read</skillsjars.skill.pdf.allowed-tools>
                <other>x</other>
              </properties>
              <dependencyManagement><dependencies><dependency><groupId>m</groupId><artifactId>managed</artifactId></dependency></dependencies></dependencyManagement>
              <dependencies>
                <dependency><groupId>com.skillsjars</groupId><artifactId>a__b__c</artifactId><version>1</version></dependency>
                <dependency><groupId>r</groupId><artifactId>rt</artifactId><scope>runtime</scope></dependency>
                <dependency><groupId>t</groupId><artifactId>test-only</artifactId><scope>test</scope></dependency>
                <dependency><groupId>o</groupId><artifactId>opt</artifactId><optional>true</optional></dependency>
              </dependencies>
            </project>
            """.trimIndent(),
        )
        assertTrue(pom.name == "pdf" && pom.description == "Use this skill for PDFs & forms.")
        assertTrue(pom.licenses == listOf("MIT License", "Apache-2.0"))
        assertTrue(pom.allowedTools == mapOf("pdf" to "Bash Read"))
        assertTrue(pom.dependencies == listOf(coordinates("com.skillsjars:a__b__c"), coordinates("r:rt")), pom.dependencies.toString())
    }

    @Test
    fun emptyPom() {
        assertTrue(PomInfo.parse("<project/>") == PomInfo(null, null, emptyList(), emptyMap(), emptyList()))
    }
}
