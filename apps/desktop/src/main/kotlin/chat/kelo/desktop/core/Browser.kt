package chat.kelo.desktop.core

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI

/** Opening a web page in the system browser, and the system clipboard. Only http(s) addresses are opened. */
object Browser {
    fun open(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
        if (runCatching { Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) }.getOrDefault(false)) {
            if (runCatching { Desktop.getDesktop().browse(uri) }.isSuccess) return true
        }
        val cmd = when (DesktopPlatform.os) {
            DesktopPlatform.Os.WINDOWS -> listOf("rundll32", "url.dll,FileProtocolHandler", uri.toString())
            DesktopPlatform.Os.MAC -> listOf("open", uri.toString())
            DesktopPlatform.Os.LINUX -> listOf("xdg-open", uri.toString())
        }
        return runCatching { ProcessBuilder(cmd).start() }.isSuccess
    }

    fun copy(text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }
}
