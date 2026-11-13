package sjm

import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString

actual fun currentExecutablePath(): String? = memScoped {
    val size = 32768
    val buffer = allocArray<UShortVar>(size)
    val length = platform.windows.GetModuleFileNameW(null, buffer, size.toUInt())
    if (length == 0u || length >= size.toUInt()) null else buffer.toKString()
}

/** Windows has no executable bit. */
actual fun makeExecutable(path: okio.Path) = Unit
