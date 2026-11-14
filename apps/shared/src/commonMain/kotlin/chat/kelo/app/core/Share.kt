package chat.kelo.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Something another app handed to Kelo (the share sheet, or "Kelo" in a text selection menu). */
sealed interface Shared {
    /** Text for the chat's draft */
    data class Text(val text: String) : Shared
    /** A document copied into the app's cache, to upload like the "+" button does */
    data class File(val path: String, val name: String, val mime: String?) : Shared
    /** A file the assistant cannot read (the server takes PDF, Word and Excel only) */
    data class Unsupported(val name: String) : Shared
    /** A file that could not be read or copied (the size limit is the upload plan's, decided when it goes up) */
    data object Failed : Shared
}

/** What an incoming intent asks for, read from its plain values (so it can be tested without Android). */
sealed interface ShareRequest {
    data class Text(val text: String) : ShareRequest
    /** EXTRA_STREAM: a file to copy, of this type */
    data class Stream(val mime: String?) : ShareRequest
    data object None : ShareRequest
}

object ShareParse {
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT"

    /** The kinds the server's upload accepts (home.py UPLOAD_SUFFIXES), by suffix and by type. */
    private val SUFFIXES = setOf("pdf", "docx", "xlsx", "xlsm", "mp4", "m4v", "mov", "webm", "3gp", "mkv",
        "mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "amr")
    private val TYPES = mapOf(
        "application/pdf" to "pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.ms-excel.sheet.macroEnabled.12" to "xlsm",
        "video/mp4" to "mp4", "video/quicktime" to "mov", "video/webm" to "webm", "video/3gpp" to "3gp",
        "audio/mpeg" to "mp3", "audio/mp4" to "m4a", "audio/x-m4a" to "m4a", "audio/aac" to "aac", "audio/ogg" to "ogg",
        "audio/wav" to "wav", "audio/flac" to "flac",
    )
    /** Longest text taken into the draft. */
    const val MAX_TEXT = 20_000

    /**
     * ACTION_SEND with a file (EXTRA_STREAM) is a file; with text (EXTRA_TEXT, the subject in front when there is one) it is text;
     * ACTION_PROCESS_TEXT is the selected text. Anything else, or empty text, is nothing.
     */
    fun parse(action: String?, type: String?, text: CharSequence?, subject: CharSequence?, processText: CharSequence?, hasStream: Boolean): ShareRequest =
        when (action) {
            ACTION_PROCESS_TEXT -> processText?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { ShareRequest.Text(it.take(MAX_TEXT)) } ?: ShareRequest.None
            ACTION_SEND -> when {
                hasStream && !type.orEmpty().startsWith("text/") -> ShareRequest.Stream(type)
                !text.isNullOrBlank() -> {
                    val body = text.toString().trim()
                    val head = subject?.toString()?.trim().orEmpty()
                    ShareRequest.Text((if (head.isNotEmpty() && !body.startsWith(head)) "$head\n$body" else body).take(MAX_TEXT))
                }
                hasStream -> ShareRequest.Stream(type)
                else -> ShareRequest.None
            }
            else -> ShareRequest.None
        }

    /** The name to upload under: the file's own name, with the suffix its type implies when it has none. Null: not uploadable. */
    fun uploadName(displayName: String?, mime: String?): String? {
        val raw = displayName?.substringAfterLast('/')?.trim().orEmpty().ifEmpty { "document" }
        val suffix = raw.substringAfterLast('.', "").lowercase()
        if (suffix in SUFFIXES) return raw
        val implied = TYPES[mime?.lowercase()] ?: return null
        return if (suffix.isEmpty()) "$raw.$implied" else "${raw.substringBeforeLast('.')}.$implied"
    }
}

/**
 * The one thing waiting to go into the chat. It stays here until the chat takes it, so a share made before signing in is used
 * right after signing in.
 */
class ShareInbox {
    private val _pending = MutableStateFlow<Shared?>(null)
    val pending: StateFlow<Shared?> = _pending.asStateFlow()

    fun put(s: Shared) {
        _pending.value = s
    }

    /** Takes [s] if it is still the pending one (a newer share is not lost). */
    fun take(s: Shared): Boolean = _pending.compareAndSet(s, null)

    fun clear() {
        _pending.value = null
    }
}
