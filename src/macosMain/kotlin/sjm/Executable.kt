package sjm

import platform.Foundation.NSBundle

/** `_NSGetExecutablePath` isn't in Kotlin/Native's macOS platform libraries; NSBundle reports the same path. */
actual fun currentExecutablePath(): String? = NSBundle.mainBundle.executablePath
