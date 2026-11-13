package sjm

/** What `sjm install` shows about a SkillsJar, and the dependencies it can't install for you. */
data class PomInfo(
    val name: String?,
    val description: String?,
    val licenses: List<String>,
    /** `allowed-tools` per skill name, from `skillsjars.skill.<name>.allowed-tools` properties. */
    val allowedTools: Map<String, String>,
    /** `compile` and `runtime` dependencies (the ones the build plugins would extract too). */
    val dependencies: List<Coordinates>,
) {
    companion object {
        private const val ALLOWED_TOOLS_PREFIX = "skillsjars.skill."
        private const val ALLOWED_TOOLS_SUFFIX = ".allowed-tools"

        private fun unescape(s: String): String =
            s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
                .trim()

        private fun section(xml: String, tag: String): String? =
            Regex("<$tag(?:\\s[^>]*)?>([\\s\\S]*?)</$tag>").find(xml)?.groupValues?.get(1)

        private fun sections(xml: String, tag: String): List<String> =
            Regex("<$tag(?:\\s[^>]*)?>([\\s\\S]*?)</$tag>").findAll(xml).map { it.groupValues[1] }.toList()

        private fun text(xml: String, tag: String): String? = section(xml, tag)?.let(::unescape)?.takeIf { it.isNotEmpty() }

        /**
         * Reads the few POM fields sjm shows. POMs of SkillsJars are generated and flat, so this reads only top-level
         * elements, after dropping the sections that repeat their names (`parent`, `dependencyManagement`, `build`,
         * `profiles`, and so on). It is for display and warnings only; nothing is resolved from it.
         */
        fun parse(xml: String): PomInfo {
            fun drop(xml: String, tags: List<String>) =
                tags.fold(xml) { acc, tag -> acc.replace(Regex("<$tag(?:\\s[^>]*)?>[\\s\\S]*?</$tag>"), "") }

            val noComments = xml.replace(Regex("<!--[\\s\\S]*?-->"), "")
            // Sections with their own <dependencies> come out first, so what's left is the project's own.
            val ownSections = drop(noComments, listOf("dependencyManagement", "build", "profiles", "reporting"))
            val dependencies = section(ownSections, "dependencies").orEmpty()
            val top = drop(
                ownSections,
                listOf(
                    "parent", "dependencies", "developers", "contributors", "organization", "scm", "issueManagement",
                    "distributionManagement", "licenses", "properties", "mailingLists", "repositories", "pluginRepositories",
                ),
            )

            val properties = section(noComments, "properties").orEmpty()
            val allowedTools = Regex("<([A-Za-z0-9._-]+)>([^<]*)</\\1>").findAll(properties)
                .map { it.groupValues[1] to unescape(it.groupValues[2]) }
                .filter { (k, _) -> k.startsWith(ALLOWED_TOOLS_PREFIX) && k.endsWith(ALLOWED_TOOLS_SUFFIX) }
                .associate { (k, v) -> k.removePrefix(ALLOWED_TOOLS_PREFIX).removeSuffix(ALLOWED_TOOLS_SUFFIX) to v }

            return PomInfo(
                name = text(top, "name"),
                description = text(top, "description"),
                licenses = sections(section(noComments, "licenses").orEmpty(), "license").mapNotNull { text(it, "name") },
                allowedTools = allowedTools,
                dependencies = sections(dependencies, "dependency")
                    .filter { (text(it, "scope") ?: "compile") in setOf("compile", "runtime") && text(it, "optional") != "true" }
                    .mapNotNull { dep ->
                        val g = text(dep, "groupId") ?: return@mapNotNull null
                        val a = text(dep, "artifactId") ?: return@mapNotNull null
                        (Coordinates.parse("$g:$a") as? Either.Right)?.value
                    },
            )
        }
    }
}
