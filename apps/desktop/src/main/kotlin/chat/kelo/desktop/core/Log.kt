package chat.kelo.desktop.core

import java.io.File
import java.time.LocalDateTime

/**
 * A short log in [DesktopPlatform.dataDir]/kelo.log (what started, what failed), kept under 1 MB. Never a token, an address with a
 * code, or anything the user typed.
 */
object Log {
    private const val MAX = 1_000_000L
    private val file: File by lazy { File(DesktopPlatform.dataDir, "kelo.log") }

    @Synchronized
    fun line(msg: String) {
        runCatching {
            if (file.length() > MAX) file.renameTo(File(file.parentFile, "kelo.log.1"))
            file.appendText("${LocalDateTime.now()} $msg\n")
        }
    }

    fun error(where: String, e: Throwable) = line("$where: ${e.javaClass.name}: ${e.message}\n" + e.stackTrace.take(12).joinToString("\n") { "    at $it" })

    /** Anything that escapes on any thread is written down. */
    fun install() {
        Thread.setDefaultUncaughtExceptionHandler { t, e -> error("uncaught on ${t.name}", e) }
    }
}
