package sjm

enum class ExitCode(val code: Int) {
    Ok(0),
    UserError(1),
    NetworkError(2),
    ExtractionError(3),
}

/** Errors the user has to act on. Bugs are thrown, not represented here. */
sealed interface SjmError {
    val message: String
    val exitCode: ExitCode

    data class InvalidArgument(override val message: String) : SjmError {
        override val exitCode = ExitCode.UserError
    }

    data class NotInIndex(val coordinates: Coordinates) : SjmError {
        override val message = "$coordinates is not in the skillsjars.com index"
        override val exitCode = ExitCode.UserError
    }

    data class UnknownVersion(val coordinates: Coordinates, val version: Version) : SjmError {
        override val message = "$coordinates has no version $version"
        override val exitCode = ExitCode.UserError
    }

    data class InvalidManifest(val path: String, val reason: String) : SjmError {
        override val message = "$path: $reason"
        override val exitCode = ExitCode.UserError
    }

    data class Http(val url: String, val reason: String) : SjmError {
        override val message = "$url: $reason"
        override val exitCode = ExitCode.NetworkError
    }

    data class ChecksumMismatch(val url: String, val expected: String, val actual: String) : SjmError {
        override val message = "$url: SHA-1 is $actual, expected $expected"
        override val exitCode = ExitCode.NetworkError
    }

    data class NoSkills(val gav: Gav) : SjmError {
        override val message = "$gav contains no skills (no SKILL.md under META-INF/skills/)"
        override val exitCode = ExitCode.ExtractionError
    }

    data class UnsafePath(val gav: Gav, val entry: String) : SjmError {
        override val message = "$gav has an unsafe entry path: $entry"
        override val exitCode = ExitCode.ExtractionError
    }

    data class TooLarge(val gav: Gav, val bytes: Long, val limit: Long) : SjmError {
        override val message = "$gav is too large ($bytes bytes, limit $limit)"
        override val exitCode = ExitCode.ExtractionError
    }

    data class PathConflict(val folder: SkillFolder, val first: Gav, val second: Gav) : SjmError {
        override val message = "$folder is in both $first and $second"
        override val exitCode = ExitCode.ExtractionError
    }

    data class Io(val reason: String) : SjmError {
        override val message = reason
        override val exitCode = ExitCode.ExtractionError
    }
}
