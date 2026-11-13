package sjm

import com.github.ajalt.clikt.command.main
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking { sjmCli().main(args) }
