package chat.kelo.desktop.core

import java.io.File
import java.util.Locale

/** Which desktop system the app runs on, and where it keeps its files. */
object DesktopPlatform {
    enum class Os { WINDOWS, MAC, LINUX }

    val os: Os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).let {
        when {
            it.startsWith("windows") -> Os.WINDOWS
            it.contains("mac") || it.contains("darwin") -> Os.MAC
            else -> Os.LINUX
        }
    }

    /**
     * The app's own folder: %APPDATA%\Kelo on Windows, ~/Library/Application Support/Kelo on macOS, $XDG_CONFIG_HOME/kelo on Linux.
     * KELO_HOME overrides it (tests and screenshots use a folder of their own).
     */
    val dataDir: File by lazy {
        val override = System.getenv("KELO_HOME")?.takeIf { it.isNotBlank() } ?: System.getProperty("kelo.home")
        val dir = when {
            !override.isNullOrBlank() -> File(override)
            os == Os.WINDOWS -> File(System.getenv("APPDATA") ?: (System.getProperty("user.home") + "\\AppData\\Roaming"), "Kelo")
            os == Os.MAC -> File(System.getProperty("user.home"), "Library/Application Support/Kelo")
            else -> File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.config"), "kelo")
        }
        dir.mkdirs()
        restrict(dir)
        dir
    }

    /**
     * Installed from the Microsoft Store (an MSIX package): the app then lives under ...\WindowsApps\ and Windows itself decides
     * whether it starts at sign-in (the package's startup task, switched in Settings → Apps → Startup).
     */
    val isMsix: Boolean by lazy {
        os == Os.WINDOWS && (System.getProperty("compose.application.resources.dir").orEmpty().contains("WindowsApps", ignoreCase = true) ||
            System.getProperty("java.home").orEmpty().contains("WindowsApps", ignoreCase = true))
    }

    /** The launcher the installer made (Kelo.exe / Kelo.app), for starting at sign-in; null when run from Gradle. */
    val launcher: File? by lazy {
        val home = File(System.getProperty("java.home").orEmpty())
        when (os) {
            // <install>\runtime is java.home; the launcher is <install>\Kelo.exe
            Os.WINDOWS -> home.parentFile?.let { File(it, "Kelo.exe") }?.takeIf { it.isFile }
            // Kelo.app/Contents/runtime/Contents/Home
            Os.MAC -> generateSequence(home) { it.parentFile }.firstOrNull { it.name.endsWith(".app") }
            Os.LINUX -> home.parentFile?.let { File(it, "bin/Kelo") }?.takeIf { it.isFile }
        }
    }

    /** Owner-only access to a folder or file where the system allows it (the token is sealed anyway). */
    fun restrict(f: File) {
        runCatching {
            f.setReadable(false, false); f.setReadable(true, true)
            f.setWritable(false, false); f.setWritable(true, true)
            if (f.isDirectory) { f.setExecutable(false, false); f.setExecutable(true, true) }
        }
    }

    /** The name this device gets in 마이 → 내 기기: the computer's model, e.g. "MacBook Pro" (never the host name). */
    fun deviceName(): String = ComputerModel.current(os).take(30)
}
