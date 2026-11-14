package chat.kelo.desktop.core

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import kotlin.concurrent.thread

/**
 * One Kelo per user: a second start (the Start menu while it waits in the tray, or starting at sign-in twice) only asks the running
 * one to show its window, then quits. The first one holds a lock file in [DesktopPlatform.dataDir] and listens on a loopback port it
 * writes next to it (nothing outside this device can reach it; the request carries no data).
 */
class SingleInstance(private val dir: File) {
    private var lock: FileLock? = null
    private var server: ServerSocket? = null

    /** True when this is the only Kelo; [onShow] then runs whenever another start asks for the window. */
    fun acquire(onShow: () -> Unit): Boolean {
        val raf = RandomAccessFile(File(dir, "instance.lock"), "rw")
        val l = runCatching { raf.channel.tryLock() }.getOrNull()
        if (l == null) {
            raf.close()
            poke()
            return false
        }
        lock = l
        runCatching {
            val s = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
            server = s
            File(dir, "instance.port").writeText(s.localPort.toString())
            thread(isDaemon = true, name = "kelo-instance") {
                while (!s.isClosed) {
                    val c = runCatching { s.accept() }.getOrNull() ?: break
                    runCatching { c.getInputStream().read(); c.close() }
                    onShow()
                }
            }
        }
        return true
    }

    private fun poke() {
        val port = runCatching { File(dir, "instance.port").readText().trim().toInt() }.getOrNull() ?: return
        runCatching { Socket(InetAddress.getLoopbackAddress(), port).use { it.getOutputStream().write(1) } }
    }

    fun release() {
        runCatching { server?.close() }
        runCatching { lock?.release() }
    }
}
