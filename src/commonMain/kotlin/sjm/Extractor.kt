package sjm

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use

data class SyncResult(val dir: Path, val written: List<SkillFolder>, val removed: List<SkillFolder>)

/**
 * Makes each skills dir hold exactly the skills of the given JARs. Only `skillsjars__*` folders are touched. Every
 * JAR is read and checked before anything is written, and each dir is built in a sibling staging dir first, so a
 * failure leaves the skills dirs as they were.
 */
class Extractor(private val fs: FileSystem) {

    private val zipRoot = "/".toPath()

    private fun items(zip: FileSystem): List<ZipItem> =
        zip.listRecursively(zipRoot)
            .mapNotNull { path ->
                zip.metadataOrNull(path)
                    ?.takeIf { it.isRegularFile }
                    ?.let { ZipItem(path.toString().removePrefix("/"), it.size ?: 0L) }
            }
            .toList()

    private fun plan(gav: Gav, jar: Path): Either<SjmError, Pair<FileSystem, JarPlan>> =
        attempt({ SjmError.Io("can't read $gav ($jar): ${it.message}") }) { fs.openZip(jar) }
            .flatMap { zip ->
                attempt({ SjmError.Io("can't list $gav ($jar): ${it.message}") }) { items(zip) }
                    .flatMap { SkillLayout.planJar(gav, it) }
                    .map { zip to it }
            }

    private fun stage(staging: Path, jars: List<Pair<FileSystem, JarPlan>>) {
        if (fs.exists(staging)) fs.deleteRecursively(staging)
        fs.createDirectories(staging)
        jars.forEach { (zip, plan) ->
            plan.skills.all.forEach { skill ->
                skill.files.forEach { file ->
                    val target = file.relative.all.fold(staging / skill.folder.name) { dir, segment -> dir / segment }
                    target.parent?.let { fs.createDirectories(it) }
                    zip.source(zipRoot / file.entry).use { src ->
                        fs.sink(target).buffer().use { it.writeAll(src) }
                    }
                }
            }
        }
    }

    private fun swap(dir: Path, staging: Path): SyncResult {
        fs.createDirectories(dir)
        val existing = fs.list(dir).filter { SkillFolder.isOwned(it.name) }
        val staged = fs.list(staging)
        existing.forEach { fs.deleteRecursively(it) }
        staged.forEach { fs.atomicMove(it, dir / it.name) }
        fs.deleteRecursively(staging)
        val stagedNames = staged.map { it.name }.toSet()
        return SyncResult(
            dir = dir,
            written = staged.map { SkillFolder(it.name) }.sortedBy { it.name },
            removed = existing.map { it.name }.filterNot { it in stagedNames }.map(::SkillFolder).sortedBy { it.name },
        )
    }

    fun sync(dirs: List<Path>, jars: List<Pair<Gav, Path>>): Either<SjmError, List<SyncResult>> =
        jars.map { (gav, jar) -> plan(gav, jar) }.sequence()
            .flatMap { planned -> SkillLayout.checkConflicts(planned.map { it.second }).map { planned } }
            .flatMap { planned ->
                val staged = dirs.map { dir ->
                    val staging = (dir.parent ?: ".".toPath()) / ".${dir.name}.sjm-staging"
                    attempt({ SjmError.Io("can't write $staging: ${it.message}") }) { stage(staging, planned) }
                        .map { dir to staging }
                }.sequence()
                staged.flatMap { pairs ->
                    pairs.map { (dir, staging) ->
                        attempt({ SjmError.Io("can't update $dir: ${it.message}") }) { swap(dir, staging) }
                    }.sequence()
                }
            }
}
