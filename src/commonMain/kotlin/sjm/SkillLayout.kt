package sjm


/** A folder sjm owns in a skills dir, e.g. `skillsjars__acme__demo__hello`. */
value class SkillFolder(val name: String) {
    override fun toString(): String = name

    companion object {
        const val PREFIX = "skillsjars__"
        fun isOwned(name: String): Boolean = name.startsWith(PREFIX)
        fun forRoot(root: NonEmptyList<String>): SkillFolder = SkillFolder(PREFIX + root.all.joinToString("__"))
    }
}

/** A file in a JAR: its entry name (no leading `/`) and uncompressed size. */
data class ZipItem(val entry: String, val size: Long)

/** A JAR entry and where it goes inside its skill folder. */
data class PlannedFile(val entry: String, val relative: NonEmptyList<String>)

data class PlannedSkill(val folder: SkillFolder, val files: List<PlannedFile>)

data class JarPlan(val gav: Gav, val skills: NonEmptyList<PlannedSkill>)

/**
 * The SkillsJar layout shared with the build plugins: skills live under `META-INF/skills/` or
 * `META-INF/resources/skills/`, a skill root is any directory with a `SKILL.md`, and a root's path is flattened with
 * `__` under a `skillsjars__` prefix.
 */
object SkillLayout {
    private val prefixes = listOf("META-INF/skills/", "META-INF/resources/skills/")
    private const val MARKER = "SKILL.md"
    const val MAX_UNCOMPRESSED_BYTES: Long = 200L * 1024 * 1024

    private fun isSafeSegment(segment: String): Boolean =
        segment.isNotEmpty() &&
            segment != "." &&
            segment != ".." &&
            segment.none { it == '\\' || it == ':' || it.code < 0x20 }

    /** The entry's path segments below the skills prefix, or null when it isn't under one. */
    private fun underPrefix(entry: String): List<String>? =
        prefixes.firstOrNull { entry.startsWith(it) }?.let { entry.removePrefix(it).split('/') }

    fun planJar(gav: Gav, items: List<ZipItem>): Either<SjmError, JarPlan> {
        val total = items.sumOf { it.size }
        if (total > MAX_UNCOMPRESSED_BYTES) return Either.Left(SjmError.TooLarge(gav, total, MAX_UNCOMPRESSED_BYTES))

        val skillItems = items.mapNotNull { item -> underPrefix(item.entry)?.let { item.entry to it } }
        val unsafe = skillItems.firstOrNull { (_, segments) -> !segments.all(::isSafeSegment) }
        if (unsafe != null) return Either.Left(SjmError.UnsafePath(gav, unsafe.first))

        val roots = skillItems
            .filter { (_, segments) -> segments.last() == MARKER }
            .mapNotNull { (_, segments) -> NonEmptyList.fromList(segments.dropLast(1)) }
            .distinct()
            .sortedByDescending { it.all.size }

        val files = skillItems.mapNotNull { (entry, segments) ->
            roots.firstOrNull { root -> segments.size > root.all.size && segments.take(root.all.size) == root.all }
                ?.let { root ->
                    NonEmptyList.fromList(segments.drop(root.all.size))?.let { relative ->
                        SkillFolder.forRoot(root) to PlannedFile(entry, relative)
                    }
                }
        }

        val skills = files.groupBy({ it.first }, { it.second })
            .map { (folder, planned) -> PlannedSkill(folder, planned.sortedBy { it.entry }) }
            .sortedBy { it.folder.name }

        return NonEmptyList.fromList(skills)
            ?.let { Either.Right(JarPlan(gav, it)) }
            ?: Either.Left(SjmError.NoSkills(gav))
    }

    /** Two JARs may not write the same skill folder. */
    fun checkConflicts(plans: List<JarPlan>): Either<SjmError, List<JarPlan>> {
        val owners = plans.flatMap { plan -> plan.skills.all.map { it.folder to plan.gav } }
        val conflict = owners.groupBy({ it.first }, { it.second })
            .entries
            .firstOrNull { it.value.size > 1 }
        return conflict
            ?.let { Either.Left(SjmError.PathConflict(it.key, it.value[0], it.value[1])) }
            ?: Either.Right(plans)
    }
}
