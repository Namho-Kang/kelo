package chat.kelo.desktop.chat

import chat.kelo.app.chat.AttachmentUnreadable
import chat.kelo.app.chat.ChatViewModel
import chat.kelo.app.chat.Prepared
import java.io.File

/**
 * What a file sent from this computer is, by its name: the tray's chip needs to know whether it is a picture (the phone's picker
 * knows the type; a dropped or picked file has only its name).
 */
object AttachmentKinds {
    private val IMAGE = setOf("png", "jpg", "jpeg", "gif", "webp", "heic", "heif", "bmp")

    fun isImage(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in IMAGE
}

/**
 * Puts a dropped or picked file in the tray: the chip shows at once and the upload runs in the background, read a chunk at a time
 * (never all in memory), as the phone does for a file it was handed. One request or in parts, as the server's limits for this route say.
 */
fun ChatViewModel.attachFile(file: File) = attach(image = AttachmentKinds.isImage(file.name), label = file.name) {
    if (!file.isFile || !file.canRead()) throw AttachmentUnreadable()
    val length = file.length()   // any size: the upload plan decides, with the server's limits for this route
    val type = runCatching { java.nio.file.Files.probeContentType(file.toPath()) }.getOrNull()
    Prepared(file.name, null, type, length = length, open = { file.inputStream() })
}
