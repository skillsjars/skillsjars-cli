package sjm

import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path

sealed interface SelfUpdateOutcome {
    data class Updated(val from: AppVersion, val to: AppVersion, val path: Path) : SelfUpdateOutcome
    data class UpToDate(val version: AppVersion) : SelfUpdateOutcome
    data class Skipped(val reason: String) : SelfUpdateOutcome
}

/** Where the running binary is and how it was installed. */
data class Installation(
    val version: AppVersion?,
    /** The release asset for this OS and architecture, e.g. `sjm-linux-x64`. */
    val assetName: String?,
    /** The running executable, symlinks resolved. */
    val executable: Path?,
    val windows: Boolean,
)

/**
 * Replaces the running sjm with the latest release, verified against the release's `SHA256SUMS`. The new binary is
 * written next to the old one (same filesystem) and moved over it. Windows can't replace a running `.exe`, so there
 * the old one is moved aside to `sjm.exe.old` first and deleted on the next start ([cleanUpAfterUpdate]).
 */
class SelfUpdater(
    private val fs: FileSystem,
    private val releases: ReleaseSource,
    private val installation: Installation,
    private val makeExecutable: (Path) -> Unit,
) {
    companion object {
        const val CHECKSUMS = "SHA256SUMS"

        /** The upgrade command when a package manager installed sjm, which should then do the update. */
        fun packageManagerUpgrade(executable: Path): String? {
            val p = executable.toString().replace('\\', '/').lowercase()
            return when {
                "/cellar/" in p -> "brew upgrade sjm"
                "/scoop/apps/" in p -> "scoop update sjm"
                else -> null
            }
        }

        /** `<sha256>  <name>` lines (`sha256sum` output; a `*` before the name marks binary mode). */
        fun parseChecksums(text: String): Map<String, String> =
            text.lines().mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size == 2 && parts[0].length == 64) parts[1].removePrefix("*") to parts[0].lowercase() else null
            }.toMap()

        fun oldExecutable(executable: Path): Path = executable.parent?.let { it / "${executable.name}.old" } ?: executable

        /** Deletes what a Windows self-update left behind. Best effort: it is retried on every start. */
        fun cleanUpAfterUpdate(fs: FileSystem, installation: Installation) {
            if (!installation.windows) return
            val executable = installation.executable ?: return
            try {
                fs.delete(oldExecutable(executable), mustExist = false)
            } catch (_: Exception) {
                // Still in use by a process started before the update.
            }
        }
    }

    suspend fun update(): Either<SjmError, SelfUpdateOutcome> {
        val current = installation.version
            ?: return Either.Right(SelfUpdateOutcome.Skipped("this is a development build ($SJM_VERSION)"))
        val asset = installation.assetName
            ?: return Either.Right(SelfUpdateOutcome.Skipped("no sjm release is built for this OS and architecture"))
        val executable = installation.executable
            ?: return Either.Right(SelfUpdateOutcome.Skipped("can't find the sjm executable"))
        packageManagerUpgrade(executable)?.let { return Either.Right(SelfUpdateOutcome.Skipped("sjm was installed by a package manager. Run: $it")) }

        val latest = releases.latest()
            ?: return Either.Left(SjmError.Http(UpdateCheck.REPO_URL, "can't determine the latest sjm release"))
        if (latest <= current) return Either.Right(SelfUpdateOutcome.UpToDate(current))

        return releases.file(latest, CHECKSUMS).flatMap { sums ->
            val expected = parseChecksums(sums.decodeToString())[asset]
                ?: return@flatMap Either.Left(SjmError.Http(UpdateCheck.REPO_URL, "sjm $latest's $CHECKSUMS has no $asset"))
            releases.file(latest, asset).flatMap { bytes ->
                val actual = bytes.toByteString().sha256().hex()
                if (actual != expected) Either.Left(SjmError.ChecksumMismatch("sjm $latest $asset", expected, actual, "SHA-256"))
                else replace(executable, bytes).map { SelfUpdateOutcome.Updated(current, latest, executable) }
            }
        }
    }

    private fun replace(executable: Path, bytes: ByteArray): Either<SjmError, Unit> {
        val dir = executable.parent ?: return Either.Left(SjmError.Io("$executable has no parent directory"))
        val staged = dir / ".${executable.name}.new"
        return attempt({ SjmError.Io("can't replace $executable (${it.message}). Rerun with permission to write $dir") }) {
            fs.write(staged) { write(bytes) }
            makeExecutable(staged)
            if (installation.windows) {
                val old = oldExecutable(executable)
                fs.delete(old, mustExist = false)
                fs.atomicMove(executable, old)
                fs.atomicMove(staged, executable)
            } else {
                fs.atomicMove(staged, executable)
            }
        }.mapLeft { error ->
            try {
                fs.delete(staged, mustExist = false)
            } catch (_: Exception) {
            }
            error
        }
    }
}
