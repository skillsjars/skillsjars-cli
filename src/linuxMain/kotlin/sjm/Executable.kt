package sjm

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString

actual fun currentExecutablePath(): String? = memScoped {
    val size = 4096
    val buffer = allocArray<ByteVar>(size)
    val length = platform.posix.readlink("/proc/self/exe", buffer, (size - 1).toULong())
    if (length <= 0) null else {
        buffer[length.toInt()] = 0
        buffer.toKString()
    }
}
