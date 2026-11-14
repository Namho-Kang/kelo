package chat.kelo.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentRulesTest {
    private val words = AttachmentWords(line = { "📎 $it" }, ask = { "$it 내용을 읽고 요약해줘." }, separator = ", ")

    @Test fun theTypedTextTakesALinePerAttachment() {
        assertEquals("이거 봐줘\n\n📎 a.pdf\n📎 b.jpg", AttachmentText.compose("  이거 봐줘 ", listOf("a.pdf", "b.jpg"), words))
    }

    @Test fun nothingTypedAsksToReadThem() {
        assertEquals("a.pdf, b.docx 내용을 읽고 요약해줘.", AttachmentText.compose(" ", listOf("a.pdf", "b.docx"), words))
    }

    @Test fun noAttachmentsLeavesTheText() {
        assertEquals("hi", AttachmentText.compose(" hi\n", emptyList(), words))
        assertEquals("", AttachmentText.compose("", emptyList(), words))
    }

    @Test fun photosFitTheLongEdge() {
        assertEquals(2048 to 1536, ImageScale.target(4032, 3024))
        assertEquals(1536 to 2048, ImageScale.target(3024, 4032))
        assertEquals(800 to 600, ImageScale.target(800, 600))   // already small
        assertEquals(2048 to 1, ImageScale.target(10000, 3))     // never 0 px
    }

    @Test fun decodingSkipsWhatIsNotNeeded() {
        assertEquals(1, ImageScale.sampleSize(3000, 2000))   // halving would go under 2048
        assertEquals(2, ImageScale.sampleSize(4096, 3072))
        assertEquals(4, ImageScale.sampleSize(8192, 6144))
        assertEquals(1, ImageScale.sampleSize(100, 100))
    }

    @Test fun onlyASmallPngGoesAsItIs() {
        assertTrue(ImageScale.keepOriginal(1080, 2400, "image/png").not())   // too tall
        assertTrue(ImageScale.keepOriginal(1080, 1920, "image/PNG"))
        assertFalse(ImageScale.keepOriginal(800, 600, "image/jpeg"))   // a camera photo is re-encoded (drops its location)
        assertFalse(ImageScale.keepOriginal(800, 600, null))
    }

    @Test fun reencodedPhotosAreJpg() {
        assertEquals("IMG_1234.jpg", ImageScale.jpegName("IMG_1234.HEIC", 5))
        assertEquals("photo-5.jpg", ImageScale.jpegName(null, 5))
        assertEquals("photo-5.jpg", ImageScale.jpegName(" ", 5))
    }

    @Test fun progressIsAFraction() {
        assertNull(Attachment("a", "x", image = true).progress)
        assertEquals(0.5f, Attachment("a", "x", image = true, sent = 5, total = 10).progress!!, 0.0001f)
    }
}
