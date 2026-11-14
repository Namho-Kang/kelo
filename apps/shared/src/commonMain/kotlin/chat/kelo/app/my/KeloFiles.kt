package chat.kelo.app.my

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One row of GET /api/files?folders=1 (the agent's own files; api/files.py FILE MANAGER). */
@Serializable
data class HomeEntry(val path: String, val type: String = "file", val bytes: Long? = null, val writable: Boolean = false) {
    val isDir: Boolean get() = type == "dir"
    val name: String get() = path.trimEnd('/').substringAfterLast('/')
}

/** A file in the trash (GET /api/home-trash); kept until [purgeAt] (unix seconds). */
@Serializable
data class HomeTrashed(
    val id: String, val path: String, val bytes: Long? = null, @SerialName("deleted_at") val deletedAt: Double = 0.0,
    val type: String = "file", @SerialName("purge_at") val purgeAt: Double = 0.0,
)

@Serializable data class HomeFile(val path: String, val content: String = "")

object KeloFileRules {
    /** The files Kelo always keeps (deleting one = "back to the start"; the server answers 409 reset). */
    val CORE = listOf("assistant.md", "user.md", "MEMORY.md", "AGENTS.md", "CHECKLIST.md", "NOTIFY_PREFS.md")
    /** Folders where the owner may add folders and delete files. */
    val OPEN_FOLDERS = listOf("memory", "notes")

    fun isCore(path: String) = path in CORE
    /** Delete (to the trash) is offered for files and folders inside memory/ or notes/, not for those two folders themselves. */
    fun canDelete(path: String) = OPEN_FOLDERS.any { path.startsWith("$it/") }
    /** A new folder can be made here ("" = the top: no). */
    fun canMakeFolder(folder: String) = OPEN_FOLDERS.any { folder == it || folder.startsWith("$it/") }

    /** The rows directly inside [folder] ("" = the top): folders first, then core files in [CORE] order, then the rest by name. */
    fun children(all: List<HomeEntry>, folder: String): List<HomeEntry> {
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        val direct = all.filter { e -> e.path.startsWith(prefix) && e.path.length > prefix.length && '/' !in e.path.removePrefix(prefix).trimEnd('/') }
        return direct.sortedWith(compareBy<HomeEntry>({ !it.isDir }, { CORE.indexOf(it.path).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.name.lowercase() }))
    }

    /** The API path for a home path: every part through [segment], joined with "/". */
    fun apiPath(path: String, segment: (String) -> String) = path.split('/').filter { it.isNotEmpty() }.joinToString("/") { segment(it) }

    /** A new folder's name: one part, no slash, not hidden, not empty (trimmed). Null when it cannot be used. */
    fun folderName(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && '/' !in it && '\\' !in it && !it.startsWith(".") && it.length <= 80 }
}
