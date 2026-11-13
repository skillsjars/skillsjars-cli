package sjm

import kotlinx.cinterop.convert
import platform.Foundation.NSBundle

/** `_NSGetExecutablePath` isn't in Kotlin/Native's macOS platform libraries; NSBundle reports the same path. */
actual fun currentExecutablePath(): String? = NSBundle.mainBundle.executablePath

actual fun makeExecutable(path: okio.Path) {
    platform.posix.chmod(path.toString(), "755".toInt(8).convert())
}
