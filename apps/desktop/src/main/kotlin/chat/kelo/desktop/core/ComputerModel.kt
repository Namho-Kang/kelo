package chat.kelo.desktop.core

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The computer's model as people know it ("MacBook Pro", "ThinkPad X1 Carbon Gen 11", "XPS 13 9340"), never its host name (which often
 * holds a person's name). Read once from the system's own hardware description; [pick] is the pure part.
 */
object ComputerModel {
    /** Placeholders that vendors leave in the firmware instead of a name. */
    private val JUNK = listOf("to be filled", "system product name", "default string", "not applicable", "none", "o.e.m", "invalid")

    private fun usable(s: String?): String? = s?.trim()?.takeIf { v -> v.length in 2..40 && JUNK.none { v.lowercase().contains(it) } }

    /**
     * [osKind]: "Windows" | "Mac" | "Linux". Windows/Linux: [vendor], [product] (SystemProductName / product_name) and [version]
     * (SystemVersion / product_version; Lenovo keeps the marketing name there). Mac: [product] is system_profiler's "Model Name".
     */
    fun pick(osKind: String, vendor: String?, product: String?, version: String?): String {
        val fallback = if (osKind == "Windows") "Windows PC" else osKind
        if (osKind == "Mac") return usable(product) ?: "Mac"
        val v = vendor?.trim()?.lowercase().orEmpty()
        val name = if (v.startsWith("lenovo")) usable(version) ?: usable(product) else usable(product)
        return name ?: fallback
    }

    @Volatile private var cached: String? = null

    /** The model of this computer (looked up once; "" -> the plain kind). */
    fun current(os: DesktopPlatform.Os): String = cached ?: lookUp(os).also { cached = it }

    private fun lookUp(os: DesktopPlatform.Os): String = runCatching {
        when (os) {
            DesktopPlatform.Os.MAC -> pick("Mac", null, run("/usr/sbin/system_profiler", "SPHardwareDataType")
                .lineSequence().firstOrNull { it.trim().startsWith("Model Name:") }?.substringAfter(':'), null)
            DesktopPlatform.Os.WINDOWS -> {
                val bios = run("reg", "query", "HKLM\\HARDWARE\\DESCRIPTION\\System\\BIOS")
                fun value(name: String) = bios.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("$name ") }
                    ?.split(Regex("\\s+REG_SZ\\s+"), limit = 2)?.getOrNull(1)
                pick("Windows", value("SystemManufacturer"), value("SystemProductName"), value("SystemVersion"))
            }
            DesktopPlatform.Os.LINUX -> {
                fun dmi(n: String) = runCatching { File("/sys/class/dmi/id/$n").readText() }.getOrNull()
                pick("Linux", dmi("sys_vendor"), dmi("product_name"), dmi("product_version"))
            }
        }
    }.getOrElse { if (os == DesktopPlatform.Os.WINDOWS) "Windows PC" else if (os == DesktopPlatform.Os.MAC) "Mac" else "Linux" }

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly()
        return out
    }
}
