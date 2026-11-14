package chat.kelo.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import chat.kelo.app.chat.AttachState
import chat.kelo.app.res.*
import chat.kelo.desktop.chat.DesktopChat
import chat.kelo.desktop.chat.attachFile
import chat.kelo.desktop.ui.KeloFrame
import chat.kelo.desktop.ui.Page
import chat.kelo.desktop.ui.Shell
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

/**
 * A file the route's one-request limit does not allow goes up in parts: the mock acts like a route through Cloudflare
 * (MOCK_UPLOAD_SINGLE_MAX = 5 MB, parts of 4 MB), so a 12 MB file is refused in one request (413) and goes as three parts; the chip finishes.
 */
class BigUploadTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private var mock: MockServer? = null
    private val en = Words.of("en")

    @After fun stop() { mock?.stop() }

    @Test fun aTwelveMegabyteFileGoesUpInPartsAndItsChipFinishes() {
        val ui = UiSteps(compose, parityShots())
        val m = MockServer("--lang", "en", env = mapOf("MOCK_UPLOAD_SINGLE_MAX" to "5000000", "MOCK_UPLOAD_PART_SIZE" to "4000000")).also { mock = it }
        val app = m.app(tmp.newFolder(), lang = "en")
        val handle = DesktopChat(app)
        val chat = handle.vm
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KeloFrame(app) {
                Box(Modifier.size(1024.dp, 768.dp)) {
                    Shell(app, chat, Page.CHAT, onPage = {}, onSignedOut = {}, connectorToOpen = null, onConnectorOpened = {}, side = handle.side)
                }
            }
        }
        val file = tmp.newFile("movie.mp4")
        RandomAccessFile(file, "rw").use { it.setLength(12_000_000L) }
        chat.attachFile(file)
        ui.waitUntil("the chip finishes", timeout = 60_000) { chat.state.value.attachments.firstOrNull()?.state != AttachState.UPLOADING }
        val chip = chat.state.value.attachments.single()
        assertEquals("failed with ${chip.error}", AttachState.DONE, chip.state)
        assertEquals("movie.mp4", chip.stored)
        ui.waitText(en.get(Res.string.attach_done))
        assertTrue(ui.has("movie.mp4"))
        handle.close()
    }
}
