package chat.kelo.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareParseTest {
    private fun send(type: String?, text: String? = null, subject: String? = null, stream: Boolean = false) =
        ShareParse.parse(ShareParse.ACTION_SEND, type, text, subject, null, stream)

    @Test fun sharedTextBecomesDraftText() {
        assertEquals(ShareRequest.Text("https://example.com/a"), send("text/plain", "  https://example.com/a "))
        // a subject goes in front, unless the text already starts with it
        assertEquals(ShareRequest.Text("기사 제목\nhttps://x.y"), send("text/plain", "https://x.y", subject = "기사 제목"))
        assertEquals(ShareRequest.Text("기사 제목 https://x.y"), send("text/plain", "기사 제목 https://x.y", subject = "기사 제목"))
        assertEquals(ShareRequest.None, send("text/plain", "   "))
    }

    @Test fun selectedTextFromTheMenu() {
        assertEquals(ShareRequest.Text("선택한 문장"), ShareParse.parse(ShareParse.ACTION_PROCESS_TEXT, "text/plain", null, null, " 선택한 문장 ", false))
        assertEquals(ShareRequest.None, ShareParse.parse(ShareParse.ACTION_PROCESS_TEXT, "text/plain", null, null, "", false))
    }

    @Test fun filesAreStreams() {
        assertEquals(ShareRequest.Stream("application/pdf"), send("application/pdf", stream = true))
        assertEquals(ShareRequest.Stream("image/jpeg"), send("image/jpeg", text = "caption", stream = true))
        // a text file with text alongside: the text is what was meant
        assertEquals(ShareRequest.Text("본문"), send("text/plain", "본문", stream = true))
        assertEquals(ShareRequest.Stream("text/plain"), send("text/plain", stream = true))
    }

    @Test fun otherIntentsAreIgnored() {
        assertEquals(ShareRequest.None, ShareParse.parse("android.intent.action.MAIN", null, "x", null, null, false))
        assertEquals(ShareRequest.None, ShareParse.parse(null, null, null, null, null, false))
        assertEquals(ShareRequest.None, send("application/pdf"))
    }

    @Test fun longTextIsCut() {
        val t = (send("text/plain", "가".repeat(ShareParse.MAX_TEXT + 50)) as ShareRequest.Text).text
        assertEquals(ShareParse.MAX_TEXT, t.length)
    }

    @Test fun uploadNamesFollowTheServersTypes() {
        assertEquals("보고서.pdf", ShareParse.uploadName("보고서.pdf", "application/pdf"))
        assertEquals("표.XLSX", ShareParse.uploadName("표.XLSX", null))
        assertEquals("document.pdf", ShareParse.uploadName(null, "application/pdf"))
        assertEquals("scan.pdf", ShareParse.uploadName("scan", "application/pdf"))
        assertEquals("memo.docx", ShareParse.uploadName("memo.bin", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertNull(ShareParse.uploadName("IMG_0001.jpg", "image/jpeg"))
        assertEquals("trip.mp4", ShareParse.uploadName("trip.mp4", "video/mp4"))
        assertEquals("memo.m4a", ShareParse.uploadName("memo", "audio/mp4"))
        assertNull(ShareParse.uploadName("notes.txt", "text/plain"))
    }

    @Test fun theInboxKeepsTheNewestUntilTaken() {
        val inbox = ShareInbox()
        val a = Shared.Text("a")
        val b = Shared.Text("b")
        inbox.put(a)
        inbox.put(b)
        assertFalse(inbox.take(a))   // a newer share replaced it
        assertEquals(b, inbox.pending.value)
        assertTrue(inbox.take(b))
        assertNull(inbox.pending.value)
    }
}
