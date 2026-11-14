package chat.kelo.desktop.core

import chat.kelo.app.chat.AttachmentTooLarge
import chat.kelo.app.chat.AttachmentUnreadable
import chat.kelo.app.platform.FilePicker
import chat.kelo.app.platform.PickedFile
import chat.kelo.app.platform.PickedImage
import chat.kelo.app.platform.SaveTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** The system's own file dialogs behind the shared [FilePicker]. */
class DesktopFilePicker : FilePicker {
    override suspend fun pickImage(maxBytes: Long): PickedImage? {
        val file = choose(FileDialog.LOAD, "") ?: return null
        return withContext(Dispatchers.IO) {
            if (file.length() > maxBytes) throw AttachmentTooLarge(maxBytes)
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: throw AttachmentUnreadable()
            PickedImage(bytes, if (file.name.endsWith(".png", true)) "image/png" else "image/jpeg")
        }
    }

    override suspend fun pickFile(types: List<String>): PickedFile? {
        val file = choose(FileDialog.LOAD, "") ?: return null
        val type = runCatching { java.nio.file.Files.probeContentType(file.toPath()) }.getOrNull().orEmpty()
        return PickedFile(file.name, file.length(), type) { file.inputStream() }
    }

    override suspend fun saveFile(name: String, type: String): SaveTarget? {
        val file = choose(FileDialog.SAVE, name) ?: return null
        return SaveTarget { file.outputStream() }
    }

    private suspend fun choose(mode: Int, name: String): File? = withContext(Dispatchers.Main) {
        val dialog = FileDialog(null as Frame?, "", mode)
        dialog.file = name.ifEmpty { null }
        dialog.isVisible = true
        dialog.file?.let { File(dialog.directory, it) }
    }
}
