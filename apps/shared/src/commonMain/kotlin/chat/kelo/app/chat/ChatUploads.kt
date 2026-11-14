package chat.kelo.app.chat

import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/** The photos and files in the tray: uploading them in the background, their progress and failures, and taking them off again. */
internal class ChatUploads(private val host: ChatHost) {
    // ---- attachments ("+": a photo, the camera, a file)
    private val uploads = HashMap<String, Job>()

    /**
     * Puts a chip in the tray at once and uploads in the background (PUT /api/uploads/{name}): [prepare] reads the pick (and makes
     * a photo smaller) off the main thread. A failure stays on the chip with the reason (the server's own words when it said no).
     */
    fun attach(image: Boolean, label: String, prepare: () -> Prepared) {
        if (runCatching { host.api() }.isFailure) return   // signed out
        val id = host.attachmentId()
        host.state.update { it.copy(attachments = it.attachments + Attachment(id, label, image)) }
        uploads[id] = host.scope.launch {
            var prepared: Prepared? = null
            try {
                val p = withContext(Dispatchers.IO) { prepare() }
                prepared = p
                change(id) { it.copy(name = p.name, total = p.length.coerceAtLeast(0), thumbnail = p.thumbnail) }
                var shown = -1
                val progress: (Long, Long) -> Unit = { sent, total ->
                    val pct = if (total > 0) (sent * 100 / total).toInt() else 0
                    if (pct != shown) {   // a few dozen updates for a whole file, not one per chunk
                        shown = pct
                        change(id) { it.copy(sent = sent, total = total) }
                    }
                }
                // the upload plan decides: one request, or in parts; a file over what this route takes is refused before anything is sent
                val bytes = p.bytes
                val open: () -> java.io.InputStream = if (bytes != null) ({ java.io.ByteArrayInputStream(bytes) }) else p.open ?: throw AttachmentUnreadable()
                val path = host.sessions.uploader().upload(p.name, p.length, p.type, open, progress)
                val stored = path.removePrefix("uploads/").ifEmpty { p.name }
                change(id) { it.copy(state = AttachState.DONE, stored = stored, sent = it.total) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AttachmentTooLarge) {
                fail(id, AttachError.TooLarge(e.limit))
            } catch (e: ApiError) {
                fail(id, AttachErrors.of(e))
            } catch (e: Exception) {
                host.platform.log("KeloAttach", "could not read the attachment", e)
                fail(id, AttachError.Unreadable)
            } finally {
                uploads.remove(id)
                prepared?.done?.invoke()
            }
        }
    }

    private fun change(id: String, f: (Attachment) -> Attachment) =
        host.state.update { s -> s.copy(attachments = s.attachments.map { if (it.id == id) f(it) else it }) }

    private fun fail(id: String, e: AttachError) {
        change(id) { it.copy(state = AttachState.FAILED, error = e) }
        host.events.tryEmit(ChatEvent.ERROR)
    }

    /**
     * The chip's ×: an upload still running is cancelled; one already up is taken off the server again (DELETE /api/uploads/{name}),
     * since nothing was sent with it.
     */
    fun removeAttachment(id: String) {
        val a = host.state.value.attachments.firstOrNull { it.id == id } ?: return
        uploads.remove(id)?.cancel()
        host.state.update { s -> s.copy(attachments = s.attachments.filterNot { it.id == id }) }
        val stored = a.stored
        if (a.state == AttachState.DONE && stored != null) {
            host.scope.launch {
                runCatching { host.api().delete<JsonElement>("/api/uploads/${KeloApi.segment(stored)}") }
            }
        }
    }
}

/** What an upload that failed with an [ApiError] tells the chip: the server's own words (507 storage full, 409 too many large uploads open, 413 …). */
internal object AttachErrors {
    fun of(e: ApiError): AttachError = if (e.isNetwork) AttachError.Network else AttachError.Server(e.detail)
}
