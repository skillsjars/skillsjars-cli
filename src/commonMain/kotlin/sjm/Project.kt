package sjm

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okio.FileSystem
import okio.Path

/** A SkillsJar `install` added, with its POM when it could be read (it is only shown, never required). */
data class Added(val gav: Gav, val pom: PomInfo?)

data class InstallResult(
    val manifestPath: Path,
    val manifestCreated: Boolean,
    val added: List<Added>,
    /** Dependencies of the added SkillsJars that aren't in the manifest. sjm doesn't resolve them (SPEC.md). */
    val missingDependencies: Map<Gav, List<Coordinates>>,
    val synced: List<SyncResult>,
)

data class RemoveResult(val removed: List<Gav>, val synced: List<SyncResult>)

/** An installed SkillsJar and the index's latest version, or null when it is no longer in the index. */
data class VersionStatus(val installed: Gav, val latest: Version?) {
    val outdated: Boolean get() = latest != null && latest != installed.version
}

data class UpdateResult(
    val updated: List<Pair<Gav, Version>>,
    val notInIndex: List<Gav>,
    val synced: List<SyncResult>,
)

/**
 * The SkillsJars of one project: its `skillsjars.json` and the skills dirs it lists. Every change resolves,
 * downloads, and extracts everything first, and writes the manifest last, so a failure changes nothing.
 */
class Project(
    private val fs: FileSystem,
    private val index: SkillsJarsIndex,
    private val jars: JarRepository,
    val manifestPath: Path,
    /** Overrides the manifest's dirs for this run; written to a manifest this run creates. */
    private val dirs: NonEmptyList<SkillsDir>? = null,
) {
    private val extractor = Extractor(fs)
    private val projectDir: Path = manifestPath.parent ?: error("manifest path $manifestPath has no parent")

    fun load(): Either<SjmError, Manifest?> =
        if (!fs.exists(manifestPath)) Either.Right(null)
        else attempt({ SjmError.Io("can't read $manifestPath: ${it.message}") }) { fs.read(manifestPath) { readUtf8() } }
            .flatMap { Manifest.parse(manifestPath.toString(), it) }

    private fun loadExisting(): Either<SjmError, Manifest> =
        load().flatMap { it?.let { m -> Either.Right(m) } ?: Either.Left(SjmError.NoManifest(manifestPath.toString())) }

    private fun write(manifest: Manifest): Either<SjmError, Unit> =
        attempt({ SjmError.Io("can't write $manifestPath: ${it.message}") }) {
            val temp = projectDir / "${manifestPath.name}.tmp"
            fs.write(temp) { writeUtf8(manifest.render()) }
            fs.atomicMove(temp, manifestPath)
        }

    private fun targets(manifest: Manifest): List<Path> =
        (dirs ?: manifest.dirs).all.map { dir -> dir.path.split('/').fold(projectDir) { p, s -> p / s } }

    /** Downloads every SkillsJar in [manifest], extracts them, then writes [manifest]. */
    private suspend fun apply(manifest: Manifest): Either<SjmError, List<SyncResult>> =
        coroutineScope { manifest.gavs.map { gav -> async { jars.jar(gav).map { gav to it } } }.awaitAll() }
            .sequence()
            .flatMap { extractor.sync(targets(manifest), it) }
            .flatMap { synced -> write(manifest).map { synced } }

    private fun requireInstalled(manifest: Manifest, coordinates: List<Coordinates>): Either<SjmError, Unit> =
        coordinates.firstOrNull { it !in manifest.skillsJars }
            ?.let { Either.Left(SjmError.NotInstalled(it)) }
            ?: Either.Right(Unit)

    /** Every SkillsJar must be in the index. A pinned version is checked when its JAR is downloaded. */
    private suspend fun resolve(ref: SkillsJarRef): Either<SjmError, Gav> =
        index.latest(ref.coordinates).map { latest -> Gav(ref.coordinates, ref.version ?: latest) }

    /** `sjm install [refs]`: add [refs] at their latest (or pinned) version, then sync. No refs: just sync. */
    suspend fun install(refs: List<SkillsJarRef>): Either<SjmError, InstallResult> =
        load().flatMap { existing ->
            val base = existing ?: Manifest.empty(dirs ?: NonEmptyList.of(SkillsDir.Default))
            coroutineScope { refs.map { async { resolve(it) } }.awaitAll() }.sequence().flatMap { gavs ->
                val manifest = gavs.fold(base) { m, gav -> m.with(gav) }
                val poms = coroutineScope { gavs.map { gav -> async { (jars.pom(gav) as? Either.Right)?.value } }.awaitAll() }
                apply(manifest).map { synced ->
                    val added = gavs.zip(poms) { gav, pom -> Added(gav, pom) }
                    InstallResult(
                        manifestPath = manifestPath,
                        manifestCreated = existing == null,
                        added = added,
                        missingDependencies = added
                            .associate { a -> a.gav to a.pom?.dependencies.orEmpty().filterNot { it in manifest.skillsJars } }
                            .filterValues { it.isNotEmpty() },
                        synced = synced,
                    )
                }
            }
        }

    /** `sjm remove <coordinates>`: drop them from the manifest, then sync, which deletes their skill folders. */
    suspend fun remove(coordinates: List<Coordinates>): Either<SjmError, RemoveResult> =
        loadExisting().flatMap { manifest ->
            requireInstalled(manifest, coordinates).flatMap {
                val removed = coordinates.distinct().map { Gav(it, manifest.skillsJars.getValue(it)) }
                apply(coordinates.fold(manifest) { m, c -> m.without(c) }).map { RemoveResult(removed, it) }
            }
        }

    /** The installed SkillsJars with the index's latest version of each. */
    suspend fun status(): Either<SjmError, List<VersionStatus>> =
        loadExisting().flatMap { manifest -> statusOf(manifest.gavs) }

    private suspend fun statusOf(gavs: List<Gav>): Either<SjmError, List<VersionStatus>> =
        coroutineScope {
            gavs.map { gav ->
                async {
                    when (val latest = index.latest(gav.coordinates)) {
                        is Either.Right -> Either.Right(VersionStatus(gav, latest.value))
                        is Either.Left -> if (latest.value is SjmError.NotInIndex) Either.Right(VersionStatus(gav, null)) else latest
                    }
                }
            }.awaitAll()
        }.sequence()

    /**
     * `sjm update [coordinates]`: bump them (all when empty) to the index's latest version, then sync. Ones no longer
     * in the index stay pinned. Null when there is no manifest.
     */
    suspend fun update(coordinates: List<Coordinates>): Either<SjmError, UpdateResult?> =
        load().flatMap { existing ->
            if (existing == null) return@flatMap Either.Right(null)
            requireInstalled(existing, coordinates).flatMap {
                val selected = if (coordinates.isEmpty()) existing.gavs else existing.gavs.filter { it.coordinates in coordinates }
                statusOf(selected).flatMap { statuses ->
                    val updated = statuses.mapNotNull { s -> s.latest?.takeIf { s.outdated }?.let { s.installed to it } }
                    val manifest = updated.fold(existing) { m, (gav, v) -> m.with(Gav(gav.coordinates, v)) }
                    apply(manifest).map { synced ->
                        UpdateResult(updated, statuses.filter { it.latest == null }.map { it.installed }, synced)
                    }
                }
            }
        }
}
