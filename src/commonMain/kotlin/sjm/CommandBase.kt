package sjm

import com.github.ajalt.clikt.command.CoreSuspendingCliktCommand

/**
 * Clikt without Mordant. The spike chose `clikt-core` over `clikt`: sjm has no TUI, and Mordant added about 1 MB
 * (SPEC.md "Spike 1 results").
 */
typealias CommandBase = CoreSuspendingCliktCommand
