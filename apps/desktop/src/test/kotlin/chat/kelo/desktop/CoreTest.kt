package chat.kelo.desktop

import chat.kelo.app.chat.AttachmentWords
import chat.kelo.app.chat.AttachmentText

import chat.kelo.app.core.SecureStore
import chat.kelo.app.core.Session
import chat.kelo.desktop.core.Autostart
import chat.kelo.desktop.core.FileKeyValueStore
import chat.kelo.desktop.core.Settings
import chat.kelo.desktop.core.SingleInstance
import chat.kelo.desktop.core.StoreKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun signInIsKeptSealedAndSurvivesARestart() {
        val dir = tmp.newFolder()
        val file = File(dir, "kelo.properties")
        SecureStore(FileKeyValueStore(file), StoreKey.cipher(dir)).saveSession(Session("https://kelo.example.com", "secret-token-123"))
        val text = file.readText()
        assertFalse("token written in plain text", text.contains("secret-token-123"))
        assertFalse("address written in plain text", text.contains("kelo.example.com"))
        val again = SecureStore(FileKeyValueStore(file), StoreKey.cipher(dir)).session()
        assertEquals(Session("https://kelo.example.com", "secret-token-123"), again)
    }

    @Test fun aLostKeyMeansSignedOutNotACrash() {
        val dir = tmp.newFolder()
        val file = File(dir, "kelo.properties")
        SecureStore(FileKeyValueStore(file), StoreKey.cipher(dir)).saveSession(Session("https://a.example", "t0ken-t0ken"))
        // the key file is store.key, or store.key.dpapi on Windows (protected with DPAPI): lose whichever this system made
        dir.listFiles { f -> f.name.startsWith("store.key") }!!.forEach { it.delete() }
        assertNull(SecureStore(FileKeyValueStore(file), StoreKey.cipher(dir)).session())
    }

    @Test fun settingsPersist() {
        val dir = tmp.newFolder()
        val kv = FileKeyValueStore(File(dir, "kelo.properties"))
        Settings(kv).apply { setTheme("dark"); setLanguage("ko"); setNotifications(false); setLastNote("https://a", 42) }
        val s = Settings(FileKeyValueStore(File(dir, "kelo.properties")))
        assertEquals("dark", s.theme.value)
        assertEquals("ko", s.language.value)
        assertFalse(s.notifications.value)
        assertEquals(42L, s.lastNote("https://a"))
        assertNull(s.lastNote("https://b"))
        assertEquals("ko", s.shownLanguage())
    }

    @Test fun attachmentsAreNamedInTheMessage() {
        val w = AttachmentWords({ "📎 $it" }, { "Read $it" }, ", ")
        assertEquals("hi\n\n📎 a.pdf\n📎 b.xlsx", AttachmentText.compose(" hi ", listOf("a.pdf", "b.xlsx"), w))
        assertEquals("Read a.pdf, b.xlsx", AttachmentText.compose("", listOf("a.pdf", "b.xlsx"), w))
        assertEquals("hi", AttachmentText.compose("hi", emptyList(), w))
    }

    @Test fun launchAgentStartsHiddenInTheTray() {
        val plist = Autostart.macPlist("/Applications/Kelo.app/Contents/MacOS/Kelo")
        assertTrue(plist.contains("<string>/Applications/Kelo.app/Contents/MacOS/Kelo</string>"))
        assertTrue(plist.contains("<string>--tray</string>"))
        assertTrue(plist.contains("<key>RunAtLoad</key><true/>"))
    }

    @Test fun aSecondStartOnlyWakesTheFirst() {
        val dir = tmp.newFolder()
        val woken = java.util.concurrent.CountDownLatch(1)
        val first = SingleInstance(dir)
        assertTrue(first.acquire { woken.countDown() })
        assertFalse(SingleInstance(dir).acquire { })
        assertTrue(woken.await(5, java.util.concurrent.TimeUnit.SECONDS))
        first.release()
    }
}
