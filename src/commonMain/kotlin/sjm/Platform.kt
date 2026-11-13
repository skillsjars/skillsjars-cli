package sjm

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import okio.Path

/** An environment variable, or null when unset or empty. */
expect fun env(name: String): String?

expect fun stderrIsTty(): Boolean

/** The per-user cache dir for sjm, or null when the OS doesn't say where it is. */
expect fun userCacheDir(): Path?

/** The Ktor engine this build uses (a spike variant). */
expect val httpEngineName: String

expect fun httpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient

expect fun writeStderr(text: String)

/**
 * Flushes stdout/stderr and ends the process without running exit handlers. Ktor's curl engine frees its OpenSSL
 * state on a background thread after the client closes, and OpenSSL's own exit handler (`OPENSSL_cleanup`) racing it
 * crashed about 1 run in 40 with SIGSEGV. Nothing else needs exit handlers: every file is written and closed before.
 */
expect fun exitNow(code: Int): Nothing

/** This OS and architecture's release asset (see SPEC.md "Platforms"). */
expect val releaseAssetName: String?

expect val isWindows: Boolean

/** The running executable as the OS reports it (symlinks not yet resolved), or null when it can't tell. */
expect fun currentExecutablePath(): String?

/** chmod 755 (no-op on Windows). */
expect fun makeExecutable(path: Path)

/** Seconds since the Unix epoch. */
expect fun nowEpochSeconds(): Long
