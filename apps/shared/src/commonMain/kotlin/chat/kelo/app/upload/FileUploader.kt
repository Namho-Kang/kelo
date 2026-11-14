package chat.kelo.app.upload

import chat.kelo.app.chat.AttachmentTooLarge
import chat.kelo.app.core.KeloApi
import chat.kelo.app.my.MediaKind
import java.io.File
import java.io.InputStream

/**
 * The one way a file goes up, for the chat's tray and for My → Documents on every platform: asks the session's [UploadLimitsSource],
 * makes the [UploadPlan] and sends it as one request or in parts ([PartedUpload]). Never holds the file in memory.
 * A file over what the route allows is refused with [AttachmentTooLarge] (its `limit`) before anything is sent; a server that said no
 * throws [chat.kelo.app.core.ApiError] with the server's own words (507 storage full, 409 too many large uploads open, …).
 */
class FileUploader(
    private val api: KeloApi,
    private val limits: UploadLimitsSource,
    private val backoffMs: List<Long> = PartedUpload.BACKOFF_MS,
    /** Where a file of unknown size is copied to learn its size: null = the system's temp directory (Android: the app's own cache). */
    private val tempDir: File? = null,
) {
    /**
     * Uploads [open]'s bytes as [name]; [size] is their number (-1: not known, the stream is then copied to a temp file first, which
     * is deleted afterwards). [onProgress] gets (sent, total). Returns the stored path the server reports.
     */
    suspend fun upload(
        name: String, size: Long, type: String?, open: () -> InputStream,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): String {
        val l = limits.get()
        val image = MediaKind.of(name) == "image" || type.orEmpty().startsWith("image/")
        var plan = UploadPlan.of(size, image, l)
        var length = size
        var source = open
        var spooled: File? = null
        try {
            if (size < 0 && plan !is UploadPlan.Single) {
                // the size is the one thing the plan needs: a route with a limit (and no parts) is told "too large" only after at most limit+1 bytes
                val file = Spool.copy(open, max = (plan as? UploadPlan.TooLarge)?.limit, dir = tempDir)
                spooled = file
                length = file.length()
                source = { file.inputStream() }
                plan = UploadPlan.of(length, image, l)
            }
            return when (plan) {
                UploadPlan.Single -> api.uploadStream(name, length, type, source, onProgress)
                is UploadPlan.Parts -> PartedUpload(PartCalls(api), backoffMs).run(name, length, source, onProgress)
                is UploadPlan.TooLarge -> throw AttachmentTooLarge(plan.limit)
                UploadPlan.NeedsSize -> error("the size is known here")
            }
        } finally {
            spooled?.delete()
        }
    }
}
