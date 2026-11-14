package chat.kelo.app.platform

import java.io.InputStream
import java.io.OutputStream

/** A file the owner picked: its name, size and type, and a way to read it (each call opens a new stream). */
class PickedFile(val name: String, val size: Long, val type: String, val open: () -> InputStream)

/** A picture the owner picked, already scaled down to fit the size asked for. */
class PickedImage(val bytes: ByteArray, val type: String)

/** Where a file is to be saved: a way to open it for writing. */
class SaveTarget(val open: () -> OutputStream)

/**
 * The system's file and photo pickers. Each call suspends until the owner picks or backs out (null). Each app has one implementation
 * (Android: the activity-result pickers; desktop: its file chooser); shared screens never open a picker themselves.
 */
interface FilePicker {
    /**
     * A picture, scaled to at most [maxBytes]. Throws [chat.kelo.app.chat.AttachmentTooLarge] when it cannot be made that small and
     * [chat.kelo.app.chat.AttachmentUnreadable] when it cannot be read.
     */
    suspend fun pickImage(maxBytes: Long): PickedImage?

    /** Any file of the given MIME types (all files: a wildcard type). */
    suspend fun pickFile(types: List<String>): PickedFile?

    /** Asks where to save a file named [name] of [type]. */
    suspend fun saveFile(name: String, type: String): SaveTarget?
}
