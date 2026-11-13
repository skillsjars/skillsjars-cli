package sjm

import com.github.ajalt.clikt.command.main
import kotlinx.coroutines.runBlocking
import okio.FileSystem

fun main(args: Array<String>) {
    SelfUpdater.cleanUpAfterUpdate(FileSystem.SYSTEM, currentInstallation(FileSystem.SYSTEM))
    runBlocking { sjmCli().main(args) }
    exitNow(0)
}
