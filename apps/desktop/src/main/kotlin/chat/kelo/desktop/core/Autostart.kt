package chat.kelo.desktop.core

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Starting Kelo when the user signs in to this device, hidden in the tray (the launcher is started with [TRAY_ARG]).
 *
 * - Windows (MSI/EXE install): a value under HKCU\Software\Microsoft\Windows\CurrentVersion\Run.
 * - Windows (Microsoft Store / MSIX): the package's own startup task (AppxManifest.xml); Windows asks the user and shows it in
 *   Settings → Apps → Startup, so the app only says where to switch it ([Mode.SYSTEM]).
 * - macOS: a LaunchAgent in ~/Library/LaunchAgents. Linux: ~/.config/autostart/kelo.desktop.
 * Run from Gradle (no installed launcher) it cannot be switched ([Mode.UNAVAILABLE]).
 */
object Autostart {
    const val TRAY_ARG = "--tray"
    private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val VALUE = "Kelo"

    enum class Mode { SWITCH, SYSTEM, UNAVAILABLE }

    val mode: Mode
        get() = when {
            DesktopPlatform.isMsix -> Mode.SYSTEM
            DesktopPlatform.launcher == null -> Mode.UNAVAILABLE
            else -> Mode.SWITCH
        }

    fun isOn(): Boolean = runCatching {
        when (DesktopPlatform.os) {
            DesktopPlatform.Os.WINDOWS -> run("reg", "query", RUN_KEY, "/v", VALUE).first == 0
            DesktopPlatform.Os.MAC -> macAgent().isFile
            DesktopPlatform.Os.LINUX -> linuxEntry().isFile
        }
    }.getOrDefault(false)

    /** Returns whether it is on afterwards. */
    fun set(on: Boolean): Boolean {
        val launcher = DesktopPlatform.launcher ?: return false
        runCatching {
            when (DesktopPlatform.os) {
                DesktopPlatform.Os.WINDOWS ->
                    if (on) run("reg", "add", RUN_KEY, "/v", VALUE, "/t", "REG_SZ", "/d", "\"${launcher.absolutePath}\" $TRAY_ARG", "/f")
                    else run("reg", "delete", RUN_KEY, "/v", VALUE, "/f")
                DesktopPlatform.Os.MAC -> {
                    val f = macAgent()
                    if (on) {
                        f.parentFile.mkdirs()
                        f.writeText(macPlist(File(launcher, "Contents/MacOS/Kelo").absolutePath))
                    } else f.delete()
                }
                DesktopPlatform.Os.LINUX -> {
                    val f = linuxEntry()
                    if (on) {
                        f.parentFile.mkdirs()
                        f.writeText("[Desktop Entry]\nType=Application\nName=Kelo\nExec=\"${launcher.absolutePath}\" $TRAY_ARG\nX-GNOME-Autostart-enabled=true\n")
                    } else f.delete()
                }
            }
        }
        return isOn()
    }

    private fun macAgent() = File(System.getProperty("user.home"), "Library/LaunchAgents/chat.kelo.desktop.plist")
    private fun linuxEntry() = File(System.getenv("XDG_CONFIG_HOME") ?: (System.getProperty("user.home") + "/.config"), "autostart/kelo.desktop")

    internal fun macPlist(executable: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
            <key>Label</key><string>chat.kelo.desktop</string>
            <key>ProgramArguments</key>
            <array><string>${xml(executable)}</string><string>$TRAY_ARG</string></array>
            <key>RunAtLoad</key><true/>
        </dict>
        </plist>
    """.trimIndent() + "\n"

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1 to out }
        return p.exitValue() to out
    }
}
