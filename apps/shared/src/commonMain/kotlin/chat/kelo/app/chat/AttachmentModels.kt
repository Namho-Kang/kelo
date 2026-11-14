package chat.kelo.app.chat

import androidx.compose.ui.graphics.ImageBitmap
import java.io.IOException

/** A pick that is bigger than [limit] bytes: what the route allows (the upload plan), or what the phone itself can read (a photo is read whole). */
class AttachmentTooLarge(val limit: Long) : IOException()

/** A pick that cannot be read. */
class AttachmentUnreadable : IOException()

/**
 * A photo or file picked with "+", shown above the composer while it uploads (PUT /api/uploads/{name}) and sent with the next
 * message. [sent] / [total] are bytes (total is 0 while the photo is still being made smaller). [stored] is the name the server
 * keeps it under once it is up.
 */
data class Attachment(
    val id: String,
    val name: String,
    val image: Boolean,
    val state: AttachState = AttachState.UPLOADING,
    val sent: Long = 0,
    val total: Long = 0,
    val stored: String? = null,
    val error: AttachError? = null,
    val thumbnail: ImageBitmap? = null,
) {
    /** 0..1, or null while the size is not known yet */
    val progress: Float? get() = if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else null
}

enum class AttachState { UPLOADING, DONE, FAILED }

sealed interface AttachError {
    /** The server's own words (e.g. which kinds of file it takes) */
    data class Server(val text: String) : AttachError
    /** More than [limit] bytes: the most the server takes on this route, or for a photo */
    data class TooLarge(val limit: Long) : AttachError
    data object Network : AttachError
    data object Unreadable : AttachError
}

/**
 * What gets uploaded for one pick: the name to store it under, the bytes (a photo, made smaller) or a stream to read as it goes up
 * ([open]; a document or video is never held in memory), their type, and a small picture for the chip. [length]: the size, -1
 * unknown. [done] runs once the upload ended either way (a shared file's copy is deleted).
 */
class Prepared(
    val name: String,
    val bytes: ByteArray?,
    val type: String?,
    val thumbnail: ImageBitmap? = null,
    val length: Long = bytes?.size?.toLong() ?: -1L,
    val open: (() -> java.io.InputStream)? = null,
    val done: () -> Unit = {},
)

/** The words the message uses for its attachments (from string resources, so they follow the app's language). */
class AttachmentWords(
    /** One line per attachment under the typed text, e.g. "📎 %1$s" */
    val line: (String) -> String,
    /** The whole message when nothing was typed, e.g. "%1$s 내용을 읽고 요약해줘." with the names */
    val ask: (String) -> String,
    /** Between names in [ask], e.g. ", " */
    val separator: String,
)

object AttachmentText {
    /** The message sent: the typed text with a line per attachment under it, or (nothing typed) a request to read them. */
    fun compose(text: String, names: List<String>, words: AttachmentWords): String {
        val typed = text.trim()
        if (names.isEmpty()) return typed
        if (typed.isEmpty()) return words.ask(names.joinToString(words.separator))
        return typed + "\n\n" + names.joinToString("\n") { words.line(it) }
    }
}

/** How a photo is made smaller before it is uploaded. */
object ImageScale {
    const val MAX_EDGE = 2048
    const val JPEG_QUALITY = 85
    const val THUMB_PX = 160

    /** The size that fits [maxEdge] on the long edge, keeping the shape (unchanged when it already fits). */
    fun target(w: Int, h: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        val long = maxOf(w, h)
        if (long <= maxEdge || long <= 0) return w to h
        val f = maxEdge.toDouble() / long
        return maxOf(1, Math.round(w * f).toInt()) to maxOf(1, Math.round(h * f).toInt())
    }

    /** The largest power of two that still decodes at least [maxEdge] on the long edge (BitmapFactory's inSampleSize). */
    fun sampleSize(w: Int, h: Int, maxEdge: Int = MAX_EDGE): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= maxEdge) s *= 2
        return s
    }

    /**
     * Whether the picked bytes can go up as they are: a PNG that already fits. Everything else is re-encoded as JPEG, which also
     * drops what a camera writes into a photo (the place it was taken).
     */
    fun keepOriginal(w: Int, h: Int, mime: String?): Boolean = mime.equals("image/png", ignoreCase = true) && maxOf(w, h) <= MAX_EDGE

    /** The name a re-encoded photo is stored under: its own name with .jpg, or photo-<time>.jpg. */
    fun jpegName(display: String?, now: Long): String {
        val base = display?.substringAfterLast('/')?.substringBeforeLast('.')?.trim().orEmpty()
        return (base.ifEmpty { "photo-$now" }) + ".jpg"
    }
}
