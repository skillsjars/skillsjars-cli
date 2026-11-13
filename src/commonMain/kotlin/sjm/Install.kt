package sjm

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okio.FileSystem
import okio.Path

data class InstallResult(
    val manifestPath: Path,
    val manifestCreated: Boolean,
    val added: List<Gav>,
    val synced: List<SyncResult>,
)

/** `sjm install`: add SkillsJars to the manifest (resolving versions from the index), then sync the skills dirs. */
class Installer(
    private val fs: FileSystem,
    private val index: SkillsJarsIndex,
    private val jars: JarRepository,
) {
    private val extractor = Extractor(fs)

    private fun loadManifest(path: Path): Either<SjmError, Manifest?> =
        if (!fs.exists(path)) Either.Right(null)
        else attempt({ SjmError.Io("can't read $path: ${it.message}") }) { fs.read(path) { readUtf8() } }
            .flatMap { Manifest.parse(path.toString(), it) }

    private fun writeManifest(path: Path, manifest: Manifest): Either<SjmError, Unit> =
        attempt({ SjmError.Io("can't write $path: ${it.message}") }) {
            val temp = path.parent?.let { it / "${path.name}.tmp" } ?: error("manifest path $path has no parent")
            fs.write(temp) { writeUtf8(manifest.render()) }
            fs.atomicMove(temp, path)
        }

    /** Every SkillsJar must be in the index. A pinned version is checked when its JAR is downloaded. */
    private suspend fun resolve(ref: SkillsJarRef): Either<SjmError, Gav> =
        index.latest(ref.coordinates).map { latest -> Gav(ref.coordinates, ref.version ?: latest) }

    /**
     * [dirs] overrides the manifest's dirs for this run; when the manifest is created it is written to it.
     * Nothing is written unless every SkillsJar resolves, downloads, and extracts.
     */
    suspend fun install(manifestPath: Path, refs: List<SkillsJarRef>, dirs: NonEmptyList<SkillsDir>?): Either<SjmError, InstallResult> =
        loadManifest(manifestPath).flatMap { existing ->
            val base = existing ?: Manifest.empty(dirs ?: NonEmptyList.of(SkillsDir.Default))
            val resolved = coroutineScope { refs.map { async { resolve(it) } }.awaitAll() }.sequence()
            resolved.flatMap { added ->
                val manifest = added.fold(base) { m, gav -> m.with(gav) }
                val downloaded = coroutineScope {
                    manifest.gavs.map { gav -> async { jars.jar(gav).map { gav to it } } }.awaitAll()
                }.sequence()
                val projectDir = manifestPath.parent ?: error("manifest path $manifestPath has no parent")
                val targets = (dirs ?: manifest.dirs).all.map { dir -> dir.path.split('/').fold(projectDir) { p, s -> p / s } }
                downloaded
                    .flatMap { extractor.sync(targets, it) }
                    .flatMap { synced ->
                        writeManifest(manifestPath, manifest).map {
                            InstallResult(manifestPath, existing == null, added, synced)
                        }
                    }
            }
        }
}
