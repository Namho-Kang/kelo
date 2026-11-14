package chat.kelo.desktop.chat

import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.imageio.ImageIO

/**
 * Ctrl+V / Cmd+V of a picture in the chat's text field: a screenshot on the clipboard (Win+Shift+S, Win+V history, macOS
 * Ctrl+Shift+Cmd+4) or picture files copied in Explorer / Finder. The picture is written to a PNG file in a folder of the app's own
 * and attached like a file picked with "+" (the tray, the size limit, removable before sending). Text on the clipboard pastes as
 * text: [files] gives null then and the key goes on to the field.
 */
object ClipboardImages {
    private val PICTURE = setOf("png", "jpg", "jpeg", "webp", "gif", "heic", "heif", "bmp")
    private const val KEEP_MS = 24 * 3600 * 1000L

    /** The folder pasted pictures are written to (older ones are removed on the next paste). */
    val folder: File get() = File(System.getProperty("java.io.tmpdir"), "kelo-paste")

    /** What a paste of [t] attaches: picture files copied as files, else the clipboard's picture as a new PNG in [dir]; null: no picture. */
    fun files(t: Transferable?, dir: File = folder, now: Long = System.currentTimeMillis()): List<File>? {
        if (t == null) return null
        if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            val listed = runCatching { (t.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>).orEmpty().filterIsInstance<File>() }.getOrDefault(emptyList())
            val pictures = listed.filter { it.isFile && it.extension.lowercase() in PICTURE }
            if (pictures.isNotEmpty()) return pictures
        }
        // a picture with text alongside (a copied web image carries its address): the picture wins only when there is no plain text
        if (!t.isDataFlavorSupported(DataFlavor.imageFlavor) || t.isDataFlavorSupported(DataFlavor.stringFlavor) &&
            runCatching { (t.getTransferData(DataFlavor.stringFlavor) as? String).orEmpty().isNotBlank() }.getOrDefault(false)) return null
        val image = runCatching { t.getTransferData(DataFlavor.imageFlavor) as? Image }.getOrNull() ?: return null
        val w = image.getWidth(null)
        val h = image.getHeight(null)
        if (w <= 0 || h <= 0) return null
        val pic = image as? BufferedImage ?: BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { b ->
            val g = b.createGraphics()
            g.drawImage(image, 0, 0, null)
            g.dispose()
        }
        dir.mkdirs()
        dir.listFiles()?.filter { now - it.lastModified() > KEEP_MS }?.forEach { it.delete() }
        var f = File(dir, "pasted-" + SimpleDateFormat("yyyyMMdd-HHmmss").format(Date(now)) + ".png")
        var n = 1
        while (f.exists()) f = File(dir, f.nameWithoutExtension.substringBefore("_") + "_" + (++n) + ".png")
        return if (ImageIO.write(pic, "png", f)) listOf(f) else null
    }

    /** The system clipboard's pictures, or null (no picture, or the clipboard is busy). */
    fun fromClipboard(): List<File>? = runCatching { files(Toolkit.getDefaultToolkit().systemClipboard.getContents(null)) }.getOrNull()
}
