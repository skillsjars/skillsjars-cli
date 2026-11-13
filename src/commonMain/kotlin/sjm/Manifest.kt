package sjm

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A skills directory, relative to the manifest's directory, e.g. `.agents/skills`. */
value class SkillsDir private constructor(val path: String) {
    override fun toString(): String = path

    companion object {
        val Default: SkillsDir = SkillsDir(".agents/skills")

        fun parse(s: String): Either<String, SkillsDir> {
            val normalized = s.replace('\\', '/').trimEnd('/')
            val segments = normalized.split('/')
            return when {
                normalized.isEmpty() -> Either.Left("skills dir must not be empty")
                normalized.startsWith("/") || (normalized.length > 1 && normalized[1] == ':') ->
                    Either.Left("skills dir '$s' must be relative to the project")
                segments.any { it == ".." } -> Either.Left("skills dir '$s' must not contain '..'")
                else -> Either.Right(SkillsDir(segments.filter { it.isNotEmpty() && it != "." }.joinToString("/")))
            }
        }
    }
}

/** `skillsjars.json`. [extra] holds top-level fields sjm doesn't know, so they survive a rewrite. */
data class Manifest(
    val dirs: NonEmptyList<SkillsDir>,
    val skillsJars: Map<Coordinates, Version>,
    val extra: Map<String, JsonElement>,
) {
    val gavs: List<Gav> get() = skillsJars.entries.sortedBy { it.key }.map { Gav(it.key, it.value) }

    fun with(gav: Gav): Manifest = copy(skillsJars = skillsJars + (gav.coordinates to gav.version))

    fun render(): String {
        val known = mapOf(
            DIRS to JsonArray(dirs.all.map { JsonPrimitive(it.path) }),
            SKILLSJARS to JsonObject(
                skillsJars.entries.sortedBy { it.key }.associate { (c, v) -> c.toString() to JsonPrimitive(v.value) },
            ),
        )
        return json.encodeToString(JsonElement.serializer(), JsonObject(known + extra.entries.sortedBy { it.key }.associate { it.key to it.value })) + "\n"
    }

    companion object {
        const val FILE_NAME = "skillsjars.json"
        private const val DIRS = "dirs"
        private const val SKILLSJARS = "skillsjars"

        @OptIn(ExperimentalSerializationApi::class)
        private val json = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

        fun empty(dirs: NonEmptyList<SkillsDir>): Manifest = Manifest(dirs, emptyMap(), emptyMap())

        fun parse(source: String, text: String): Either<SjmError, Manifest> {
            fun invalid(reason: String) = Either.Left(SjmError.InvalidManifest(source, reason))

            val root = attempt({ "not valid JSON: ${it.message}" }) { Json.parseToJsonElement(text) }
            val obj = when (root) {
                is Either.Left -> return invalid(root.value)
                is Either.Right -> root.value as? JsonObject ?: return invalid("must be a JSON object")
            }

            val dirsElement = obj[DIRS]
            val dirStrings = when (dirsElement) {
                null -> emptyList()
                is JsonArray -> dirsElement.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return invalid("'$DIRS' must be an array of strings") }
                else -> return invalid("'$DIRS' must be an array of strings")
            }
            val dirs = when (val parsed = dirStrings.map(SkillsDir::parse).sequence()) {
                is Either.Left -> return invalid(parsed.value)
                is Either.Right -> NonEmptyList.fromList(parsed.value.distinct()) ?: NonEmptyList.of(SkillsDir.Default)
            }

            val jarsElement = obj[SKILLSJARS]
            val entries = when (jarsElement) {
                null -> emptyList()
                is JsonObject -> jarsElement.entries.map { (k, v) ->
                    val version = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: return invalid("'$SKILLSJARS' values must be version strings")
                    Coordinates.parse(k).flatMap { c -> Version.parse(version).map { c to it } }
                }
                else -> return invalid("'$SKILLSJARS' must be an object")
            }
            return when (val parsed = entries.sequence()) {
                is Either.Left -> invalid(parsed.value)
                is Either.Right -> Either.Right(Manifest(dirs, parsed.value.toMap(), obj - DIRS - SKILLSJARS))
            }
        }
    }
}
