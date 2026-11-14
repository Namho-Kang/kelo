package chat.kelo.desktop

import chat.kelo.desktop.chat.ClipboardImages
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ctrl+V / Cmd+V of a picture in the chat field: a screenshot becomes a PNG to attach, copied picture files attach as they are, text stays text. */
class ClipboardImagesTest {
    private class Clip(private val data: Map<DataFlavor, Any>) : Transferable {
        override fun getTransferDataFlavors() = data.keys.toTypedArray()
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor in data
        override fun getTransferData(flavor: DataFlavor) = data.getValue(flavor)
    }

    private val dir = Files.createTempDirectory("kelo-paste-test").toFile()

    @Test fun aScreenshotOnTheClipboardBecomesAPngToAttach() {
        val shot = BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB)
        val got = ClipboardImages.files(Clip(mapOf(DataFlavor.imageFlavor to shot)), dir, now = 1_762_000_000_000)!!
        assertEquals(1, got.size)
        assertTrue(got[0].name.startsWith("pasted-") && got[0].name.endsWith(".png"))
        val back = ImageIO.read(got[0])
        assertEquals(40, back.width)
        // a second paste in the same second gets its own name
        val again = ClipboardImages.files(Clip(mapOf(DataFlavor.imageFlavor to shot)), dir, now = 1_762_000_000_000)!!
        assertTrue(again[0] != got[0] && again[0].exists())
    }

    @Test fun copiedPictureFilesAttachAsTheyAreAndOtherFilesDoNot() {
        val pic = File(dir, "receipt.JPG").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val doc = File(dir, "notes.txt").apply { writeText("x") }
        assertEquals(listOf(pic), ClipboardImages.files(Clip(mapOf(DataFlavor.javaFileListFlavor to listOf(pic, doc))), dir))
        assertNull(ClipboardImages.files(Clip(mapOf(DataFlavor.javaFileListFlavor to listOf(doc))), dir))
    }

    @Test fun textStaysText() {
        assertNull(ClipboardImages.files(StringSelection("hello"), dir))
        assertNull(ClipboardImages.files(null, dir))
        val shot = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
        assertNull(ClipboardImages.files(Clip(mapOf(DataFlavor.imageFlavor to shot, DataFlavor.stringFlavor to "https://x.example/a.png")), dir))
    }
}
