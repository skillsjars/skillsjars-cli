package sjm

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.random.Random

fun <E, A> Either<E, A>.right(): A =
    when (this) {
        is Either.Right -> value
        is Either.Left -> throw AssertionError("expected Right, was Left($value)")
    }

fun <E, A> Either<E, A>.left(): E =
    when (this) {
        is Either.Left -> value
        is Either.Right -> throw AssertionError("expected Left, was Right($value)")
    }

fun gav(s: String): Gav = SkillsJarRef.parse(s).right().let { Gav(it.coordinates, it.version!!) }

fun coordinates(s: String): Coordinates = Coordinates.parse(s).right()

fun version(s: String): Version = Version.parse(s).right()

fun fixture(name: String): Path =
    (env("SJM_TEST_FIXTURES") ?: error("SJM_TEST_FIXTURES is not set; run the tests through Gradle")).toPath() / "$name.jar"

/** A fresh temp dir, deleted after [block]. */
inline fun <A> withTempDir(block: (Path) -> A): A {
    val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "sjm-test-${Random.nextLong().toULong()}"
    FileSystem.SYSTEM.createDirectories(dir)
    return try {
        block(dir)
    } finally {
        FileSystem.SYSTEM.deleteRecursively(dir)
    }
}
