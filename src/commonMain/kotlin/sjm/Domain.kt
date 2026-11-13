package sjm


/** A list with at least one element. */
data class NonEmptyList<out A>(val head: A, val tail: List<A>) {
    val all: List<A> get() = listOf(head) + tail

    companion object {
        fun <A> of(head: A, vararg tail: A): NonEmptyList<A> = NonEmptyList(head, tail.toList())
        fun <A> fromList(list: List<A>): NonEmptyList<A>? =
            list.firstOrNull()?.let { NonEmptyList(it, list.drop(1)) }
    }
}

/** Maven coordinate components end up in URLs and file paths, so they are restricted to a safe character set. */
private val coordinateChars = Regex("[A-Za-z0-9._-]+")

private fun parseComponent(kind: String, s: String): Either<String, String> =
    when {
        !coordinateChars.matches(s) -> Either.Left("$kind '$s' may only contain letters, digits, '.', '_' and '-'")
        s == "." || s == ".." -> Either.Left("$kind '$s' is not allowed")
        else -> Either.Right(s)
    }

value class GroupId private constructor(val value: String) {
    override fun toString(): String = value
    val path: String get() = value.replace('.', '/')

    companion object {
        val SkillsJars: GroupId = GroupId("com.skillsjars")
        fun parse(s: String): Either<String, GroupId> = parseComponent("groupId", s).map(::GroupId)
    }
}

value class ArtifactId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(s: String): Either<String, ArtifactId> = parseComponent("artifactId", s).map(::ArtifactId)
    }
}

value class Version private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(s: String): Either<String, Version> = parseComponent("version", s).map(::Version)
    }
}

/** `groupId:artifactId`, the key of a SkillsJar in the manifest. */
data class Coordinates(val groupId: GroupId, val artifactId: ArtifactId) : Comparable<Coordinates> {
    override fun toString(): String = "$groupId:$artifactId"
    override fun compareTo(other: Coordinates): Int = toString().compareTo(other.toString())

    companion object {
        fun parse(s: String): Either<String, Coordinates> {
            val parts = s.split(':')
            return when (parts.size) {
                2 -> GroupId.parse(parts[0]).flatMap { g -> ArtifactId.parse(parts[1]).map { a -> Coordinates(g, a) } }
                else -> Either.Left("'$s' is not groupId:artifactId")
            }
        }
    }
}

data class Gav(val coordinates: Coordinates, val version: Version) {
    override fun toString(): String = "$coordinates:$version"
}

/**
 * A SkillsJar as typed on the command line:
 * `artifactId`, `groupId:artifactId`, or `groupId:artifactId:version`, optionally followed by `@version`.
 */
data class SkillsJarRef(val coordinates: Coordinates, val version: Version?) {
    companion object {
        fun parse(input: String): Either<String, SkillsJarRef> {
            val at = input.split('@')
            if (at.size > 2) return Either.Left("'$input' has more than one '@'")
            val atVersion = at.getOrNull(1)
            val parts = at[0].split(':')
            val (group, artifact, colonVersion) = when (parts.size) {
                1 -> Triple(GroupId.SkillsJars.value, parts[0], null)
                2 -> Triple(parts[0], parts[1], null)
                3 -> Triple(parts[0], parts[1], parts[2])
                else -> return Either.Left("'$input' has too many ':' separated parts")
            }
            if (colonVersion != null && atVersion != null) return Either.Left("'$input' has two versions")
            val versionString = colonVersion ?: atVersion
            return GroupId.parse(group).flatMap { g ->
                ArtifactId.parse(artifact).flatMap { a ->
                    val version = versionString?.let { v -> Version.parse(v) }
                    when (version) {
                        null -> Either.Right(SkillsJarRef(Coordinates(g, a), null))
                        is Either.Left -> version
                        is Either.Right -> Either.Right(SkillsJarRef(Coordinates(g, a), version.value))
                    }
                }
            }
        }
    }
}

/** A release version of sjm itself, e.g. `v1.2.3` or `1.2.3`. Pre-release and dev builds don't parse. */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val pattern = Regex("v?(\\d+)\\.(\\d+)\\.(\\d+)")
        fun parse(s: String): AppVersion? =
            pattern.matchEntire(s)?.groupValues?.drop(1)?.mapNotNull { it.toIntOrNull() }
                ?.takeIf { it.size == 3 }
                ?.let { (major, minor, patch) -> AppVersion(major, minor, patch) }
    }
}
