package sjm

import kotlinx.cinterop.toKString
import okio.Path
import okio.Path.Companion.toPath
import kotlin.experimental.ExperimentalNativeApi

actual fun env(name: String): String? = platform.posix.getenv(name)?.toKString()?.takeIf { it.isNotEmpty() }

actual fun stderrIsTty(): Boolean = platform.posix.isatty(2) != 0

@OptIn(ExperimentalNativeApi::class)
actual fun userCacheDir(): Path? =
    when (Platform.osFamily) {
        OsFamily.WINDOWS -> env("LOCALAPPDATA")?.let { it.toPath() / "sjm" / "cache" }
        OsFamily.MACOSX -> env("HOME")?.let { it.toPath() / "Library" / "Caches" / "sjm" }
        else -> (env("XDG_CACHE_HOME")?.toPath() ?: env("HOME")?.let { it.toPath() / ".cache" })?.let { it / "sjm" }
    }

actual fun writeStderr(text: String) {
    platform.posix.fputs(text, platform.posix.stderr)
}
