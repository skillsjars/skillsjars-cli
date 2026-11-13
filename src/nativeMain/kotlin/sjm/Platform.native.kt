package sjm

import kotlinx.cinterop.convert
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

actual fun exitNow(code: Int): Nothing {
    platform.posix.fflush(null)
    platform.posix._exit(code)
    error("_exit returned")
}

@OptIn(ExperimentalNativeApi::class)
actual val releaseAssetName: String? =
    when (Platform.osFamily to Platform.cpuArchitecture) {
        OsFamily.LINUX to CpuArchitecture.X64 -> "sjm-linux-x64"
        OsFamily.LINUX to CpuArchitecture.ARM64 -> "sjm-linux-arm64"
        OsFamily.MACOSX to CpuArchitecture.ARM64 -> "sjm-macos-arm64"
        OsFamily.WINDOWS to CpuArchitecture.X64 -> "sjm-windows-x64.exe"
        else -> null
    }

@OptIn(ExperimentalNativeApi::class)
actual val isWindows: Boolean = Platform.osFamily == OsFamily.WINDOWS

actual fun makeExecutable(path: Path) {
    if (!isWindows) platform.posix.chmod(path.toString(), "755".toInt(8).convert())
}

@OptIn(kotlin.time.ExperimentalTime::class)
actual fun nowEpochSeconds(): Long = kotlin.time.Clock.System.now().epochSeconds
