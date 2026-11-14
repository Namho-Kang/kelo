package chat.kelo.app.upload

import chat.kelo.app.chat.AttachmentTooLarge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * A stream whose size is not known (a provider that reports none) copied to a temp file, a chunk at a time, so the size is known
 * before the upload plan is made. The caller deletes the file afterwards. [dir] null = the system temp directory (on Android the app's
 * own cache directory, which is private to the app).
 */
internal object Spool {
    /** Copies all of [open]; more than [max] bytes (when given) stops it with [AttachmentTooLarge]. The partial file is deleted on any failure. */
    suspend fun copy(open: () -> InputStream, max: Long?, dir: File?): File = withContext(Dispatchers.IO) {
        val file = File.createTempFile("kelo-upload-", ".tmp", dir)
        try {
            val buf = ByteArray(PartBody.CHUNK)
            var total = 0L
            open().use { input ->
                file.outputStream().use { out ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (max != null && total > max) throw AttachmentTooLarge(max)
                        out.write(buf, 0, n)
                    }
                }
            }
            file
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }
}
