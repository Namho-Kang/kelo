package chat.kelo.desktop

import chat.kelo.desktop.chat.DesktopChat
import chat.kelo.app.chat.ChatViewModel

import chat.kelo.app.res.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import chat.kelo.desktop.core.LatestBuild
import chat.kelo.desktop.core.DesktopPlatform
import chat.kelo.desktop.core.UpdateRules
import chat.kelo.app.update.UpdateRules as SharedUpdateRules
import chat.kelo.desktop.core.UpdateState.Phase
import chat.kelo.desktop.core.Updater
import chat.kelo.desktop.ui.KeloFrame
import chat.kelo.desktop.ui.Page
import chat.kelo.desktop.ui.Shell
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * The desktop app's update check (core/Updates.kt): version order, which row of latest.json is this computer's, where the file is
 * fetched from, the sha256 check, the once-a-day timing, the request carrying nothing but an honest User-Agent; the download with
 * progress, a mismatch deleting the file, the installer opened only after the check; and the banner + My card (ko, light and
 * dark) against a local mock (never kelo.chat). Screenshots: $KELO_SHOTS or build/parity as v18-update-*.png.
 */
class UpdateTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private var web: MockWebServer? = null
    private var mock: MockServer? = null

    @After fun stop() {
        web?.shutdown()
        mock?.stop()
    }

    private val installer = ByteArray(300_000) { (it % 251).toByte() }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun latest(sha: String, version: String = "1.0.3") = """
        {"android": {"version": "0.1.402322", "size": 10, "sha256": "${"a".repeat(64)}", "date": "", "file": "kelo-0.1.402322.apk", "url": "/download/android"},
         "mac": {"version": "$version", "size": ${installer.size}, "sha256": "$sha", "date": "2026-11-08T12:10:38Z", "file": "kelo-$version.dmg", "url": "/download/mac"},
         "windows": {"version": "$version", "size": ${installer.size}, "sha256": "$sha", "date": "", "file": "kelo-$version.exe", "url": "/download/windows"}}
    """.trimIndent()

    /** A mock of kelo.chat/download: latest.json and the installer (slowly, so the progress shows). */
    private fun serve(sha: String, version: String = "1.0.3"): MockWebServer {
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/download/latest.json" -> MockResponse().setBody(latest(sha, version)).setHeader("Content-Type", "application/json")
                "/download/mac", "/download/windows" -> MockResponse().setBody(Buffer().write(installer)).setHeader("Content-Length", installer.size)
                    .throttleBody(32 * 1024, 150, java.util.concurrent.TimeUnit.MILLISECONDS)
                else -> MockResponse().setResponseCode(404)
            }
        }
        s.start()
        web = s
        return s
    }

    @Test fun versionsCompareByNumbersAndOnlyNewerCounts() {
        assertTrue(UpdateRules.isNewer("1.0.3", "1.0.2"))
        assertTrue(UpdateRules.isNewer("1.0.10", "1.0.9"))          // numbers, not text
        assertTrue(UpdateRules.isNewer("1.1", "1.0.9"))
        assertFalse(UpdateRules.isNewer("1.0.2", "1.0.2"))
        assertFalse(UpdateRules.isNewer("1.0", "1.0.0"))
        assertFalse(UpdateRules.isNewer("1.0.1", "1.0.2"))
        assertFalse(UpdateRules.isNewer("1.0.2-beta", "1.0.2"))     // a suffix is ignored
        assertTrue(UpdateRules.isNewer("0.1.402322", "0.1.402102"))
    }

    @Test fun theRightRowTheRightAddressAndAnHonestRequest() {
        val all = UpdateRules.parse(latest(sha(installer)))
        assertEquals("mac", UpdateRules.platformKey(DesktopPlatform.Os.MAC))
        assertEquals("windows", UpdateRules.platformKey(DesktopPlatform.Os.WINDOWS))
        assertNull(UpdateRules.platformKey(DesktopPlatform.Os.LINUX))
        assertEquals("kelo-1.0.3.dmg", UpdateRules.pick(all, "mac")?.file)
        assertEquals("kelo-1.0.3.exe", UpdateRules.pick(all, "windows")?.file)
        assertNull(UpdateRules.pick(all, null))
        assertNull(UpdateRules.pick(all, "windows-msi"))
        // a row that is not complete, or whose file is not this platform's kind, is not offered
        val bad = mapOf("mac" to LatestBuild("1.0.3", 10, "zz", file = "kelo.dmg"), "windows" to LatestBuild("1.0.3", 10, "a".repeat(64), file = "kelo.dmg"))
        assertNull(UpdateRules.pick(bad, "mac"))
        assertNull(UpdateRules.pick(bad, "windows"))
        assertEquals(emptyMap<String, LatestBuild>(), UpdateRules.parse("not json"))
        // relative addresses resolve against the list's own address; only http(s)
        val b = all.getValue("mac")
        assertEquals("https://kelo.chat/download/mac", UpdateRules.downloadUrl("https://kelo.chat", b))
        assertEquals("http://127.0.0.1:9/download/mac", UpdateRules.downloadUrl("http://127.0.0.1:9", b))
        assertNull(UpdateRules.downloadUrl("https://kelo.chat", b.copy(url = "file:///etc/passwd")))
        assertEquals("https://kelo.chat", UpdateRules.base(null, null))
        assertEquals("http://127.0.0.1:9", UpdateRules.base(" http://127.0.0.1:9/ ", "https://other"))
        assertEquals("https://env", UpdateRules.base("", "https://env"))
        assertEquals("Kelo-Desktop/1.0.2 (macOS)", UpdateRules.userAgent("1.0.2", DesktopPlatform.Os.MAC))
        assertTrue(UpdateRules.due(null, 0))
        assertFalse(UpdateRules.due(1000, 1000 + UpdateRules.DAY_MS - 1))
        assertTrue(UpdateRules.due(1000, 1000 + UpdateRules.DAY_MS))
    }

    @Test fun theShaCheckFindsAChangedFile() {
        val f = tmp.newFile("x.dmg").apply { writeBytes(installer) }
        assertTrue(UpdateRules.verify(f, sha(installer)))
        assertTrue(UpdateRules.verify(f, sha(installer).uppercase()))
        assertFalse(UpdateRules.verify(f, sha(installer + 1)))
        assertFalse(UpdateRules.verify(File(tmp.root, "missing"), sha(installer)))
    }

    @Test fun downloadChecksThenOpensAndAMismatchDeletesTheFile() = runBlocking {
        val s = serve(sha(installer))
        val opened = mutableListOf<File>()
        val dir = tmp.newFolder()
        val u = Updater("1.0.2", DesktopPlatform.Os.MAC, s.url("/").toString().trimEnd('/'), dir, opener = { opened += it; true }, store = false)
        assertTrue(u.enabled)
        u.check()
        assertEquals(Phase.AVAILABLE, u.state.value.phase)
        assertEquals("1.0.3", u.state.value.build?.version)
        val req = s.takeRequest()
        assertEquals("/download/latest.json", req.path)                   // no query, no account, no device
        assertEquals("Kelo-Desktop/1.0.2 (macOS)", req.getHeader("User-Agent"))
        assertNull(req.getHeader("Authorization"))
        assertNull(req.getHeader("Cookie"))
        val seen = java.util.Collections.synchronizedList(mutableListOf<Float>())
        val watch = launch(kotlinx.coroutines.Dispatchers.Default) { u.state.collect { if (it.phase == Phase.DOWNLOADING) seen += it.progress } }
        u.download()
        watch.cancel()
        assertEquals(Phase.OPENED, u.state.value.phase)
        assertEquals(1, opened.size)
        assertEquals(sha(installer), UpdateRules.sha256(opened[0]))
        assertTrue(seen.any { it > 0f && it < 1f })                        // progress was shown along the way
        // the same build again with a wrong sha256 in the list: the file is deleted, nothing is opened
        s.shutdown()
        val s2 = serve(sha(installer + 1), version = "1.0.4")
        val u2 = Updater("1.0.2", DesktopPlatform.Os.MAC, s2.url("/").toString().trimEnd('/'), dir, opener = { opened += it; true }, store = false)
        u2.check()
        u2.download()
        assertEquals(Phase.MISMATCH, u2.state.value.phase)
        assertEquals(1, opened.size)
        assertFalse(File(dir, "updates/kelo-1.0.4.dmg").exists())
        assertFalse(File(dir, "updates/kelo-1.0.4.dmg.part").exists())
        // the same version as running, Linux, a store copy or a run without a version: no offer / no check
        val same = Updater("1.0.4", DesktopPlatform.Os.MAC, s2.url("/").toString().trimEnd('/'), dir, store = false)
        same.check()
        assertEquals(Phase.LATEST, same.state.value.phase)
        assertFalse(Updater("1.0.2", DesktopPlatform.Os.LINUX, "http://x", dir, store = false).enabled)
        assertFalse(Updater("1.0.2", DesktopPlatform.Os.WINDOWS, "http://x", dir, store = true).enabled)
        assertFalse(Updater(null, DesktopPlatform.Os.MAC, "http://x", dir, store = false).enabled)
        val gone = Updater("1.0.2", DesktopPlatform.Os.MAC, "http://127.0.0.1:1", dir, store = false)
        gone.check()
        assertEquals(Phase.CHECK_FAILED, gone.state.value.phase)
    }

    @Test fun theBannerAndTheMyCardShowTheNewVersionAndTheDownload() {
        val ui = UiSteps(compose, parityShots())
        try { bannerFlow(ui) } catch (e: Throwable) { runCatching { ui.shot("v18-update-zz-failed") }; throw e }
    }

    private fun bannerFlow(ui: UiSteps) {
        val ko = Words.of("ko")
        val m = MockServer("--lang", "ko").also { mock = it }
        val app = m.app(tmp.newFolder(), lang = "ko")
        val s = serve(sha(installer))
        val opened = mutableListOf<File>()
        app.updates = Updater("1.0.2", DesktopPlatform.Os.MAC, s.url("/").toString().trimEnd('/'), tmp.newFolder(), opener = { opened += it; true }, store = false)
        val chatHandle = DesktopChat(app)
        val chat = chatHandle.vm
        compose.mainClock.autoAdvance = false
        compose.setContent {
            var page by remember { mutableStateOf(Page.GOALS) }
            KeloFrame(app) {
                Box(Modifier.size(1024.dp, 768.dp)) {
                    Shell(app, chat, page, onPage = { page = it }, onSignedOut = {}, connectorToOpen = null, onConnectorOpened = {}, side = chatHandle.side)
                }
            }
        }
        val offer = ko.get(Res.string.update_available, "1.0.3", SharedUpdateRules.size(installer.size.toLong()))
        ui.waitText("매일 30분 걷기")
        ui.waitText(offer)
        app.settings.setTheme("light"); ui.shot("v18-update-banner-ko-light")
        app.settings.setTheme("dark"); ui.shot("v18-update-banner-ko-dark")
        app.settings.setTheme("light")
        compose.onAllNodes(hasText(ko.get(Res.string.update_get)) and hasClickAction()).onFirst().performClick()
        ui.settle()
        assertEquals("1.0.3", app.settings.dismissedUpdate.value)          // the banner shows once per version
        ui.waitText(ko.get(Res.string.update_check))                          // My opened, with the card and the manual check row
        ui.waitUntil("the download's progress", 15_000) { app.updates.state.value.phase == Phase.DOWNLOADING && app.updates.state.value.progress in 0.2f..0.8f }
        app.settings.setTheme("light"); ui.shot("v18-update-my-progress-ko-light")
        app.settings.setTheme("dark"); ui.shot("v18-update-my-progress-ko-dark")
        app.settings.setTheme("light")
        ui.waitUntil("the installer opened", 20_000) { app.updates.state.value.phase == Phase.OPENED }
        ui.waitText(ko.get(Res.string.desktop_update_quit_note))
        assertEquals(1, opened.size)
        app.settings.setTheme("light"); ui.shot("v18-update-my-opened-ko-light")
        app.settings.setTheme("dark"); ui.shot("v18-update-my-opened-ko-dark")
        assertFalse(ui.has(offer + "X"))
    }

    @Test fun theServersMinimumIsTheVersionAsOneNumber() {
        // the same numbers as the server's desktop_version_code (src/assistant/server.py)
        assertEquals(100000003L, UpdateRules.versionCode("1.0.3"))
        assertEquals(100100000L, UpdateRules.versionCode("1.1"))
        assertEquals(100000000L, UpdateRules.versionCode("1"))
        assertEquals(25525565535L, UpdateRules.versionCode("255.255.65535"))
        assertNull(UpdateRules.versionCode(null))
        assertNull(UpdateRules.versionCode("dev"))
        assertNull(UpdateRules.versionCode("1.0.3-beta"))
        assertNull(UpdateRules.versionCode("1.0.3.0"))
        val order = listOf("0.9.99999", "1.0.2", "1.0.3", "1.0.10", "1.2.0", "1.10.0", "2.0.0").map { UpdateRules.versionCode(it)!! }
        assertEquals(order.sorted(), order)
        val min = UpdateRules.versionCode("1.0.3")
        assertTrue(UpdateRules.belowMin("1.0.2", min))
        assertTrue(UpdateRules.belowMin("0.9", min))
        assertFalse(UpdateRules.belowMin("1.0.3", min))
        assertFalse(UpdateRules.belowMin("1.0.10", min))
        assertFalse(UpdateRules.belowMin("1.0.2", null))                // no minimum
        assertFalse(UpdateRules.belowMin("1.0.2", 0))
        assertFalse(UpdateRules.belowMin(null, min))                    // a run from Gradle (no version) is never stopped
        assertFalse(UpdateRules.belowMin("1.0.2-beta", min))
    }

    @Test fun belowTheMinimumTheButtonDownloadsAtOnce() = runBlocking {
        val s = serve(sha(installer))
        val opened = mutableListOf<File>()
        val u = Updater("1.0.2", DesktopPlatform.Os.WINDOWS, s.url("/").toString().trimEnd('/'), tmp.newFolder(), opener = { opened += it; true }, store = false)
        u.requireAtLeast(UpdateRules.versionCode("1.0.2"))
        assertFalse(u.required.value)
        assertEquals(Phase.IDLE, u.state.value.phase)                     // nothing asked of the network
        u.requireAtLeast(UpdateRules.versionCode("1.0.3"))
        assertTrue(u.required.value)
        assertEquals(Phase.AVAILABLE, u.state.value.phase)                // the check ran at once
        assertEquals(Updater.Way.DOWNLOAD, u.way)
        u.updateNow()
        assertEquals(Phase.OPENED, u.state.value.phase)                   // downloaded, checked, opened
        assertEquals(1, opened.size)
        u.updateNow()                                                     // again: the same checked installer opens again
        assertEquals(2, opened.size)
        u.requireAtLeast(null)
        assertFalse(u.required.value)
        // without a check first, the button checks and then downloads
        val fresh = Updater("1.0.2", DesktopPlatform.Os.MAC, s.url("/").toString().trimEnd('/'), tmp.newFolder(), opener = { opened += it; true }, store = false)
        fresh.updateNow()
        assertEquals(Phase.OPENED, fresh.state.value.phase)
        // the Store copy goes to the Store, Linux to the website; neither downloads
        val links = mutableListOf<String>()
        val store = Updater("1.0.2", DesktopPlatform.Os.WINDOWS, "https://kelo.example", tmp.newFolder(), store = true)
        store.requireAtLeast(UpdateRules.versionCode("1.0.3"))
        assertTrue(store.required.value)
        assertEquals(Phase.IDLE, store.state.value.phase)
        store.updateNow { links += it; true }
        val linux = Updater("1.0.2", DesktopPlatform.Os.LINUX, "https://kelo.example", tmp.newFolder(), store = false)
        linux.updateNow { links += it; true }
        assertEquals(listOf(Updater.STORE_UPDATES, "https://kelo.example"), links)
        // a run without a version is never stopped
        val dev = Updater(null, DesktopPlatform.Os.MAC, "https://kelo.example", tmp.newFolder(), store = false)
        dev.requireAtLeast(UpdateRules.versionCode("9.0.0"))
        assertFalse(dev.required.value)
    }

    /** The shell covers everything with the required screen (ko and en, light and dark): parity-update-required-*.png. */
    @Test fun theRequiredScreenCoversTheApp() {
        val ui = UiSteps(compose, parityShots())
        try { requiredFlow(ui) } catch (e: Throwable) { runCatching { ui.shot("parity-update-required-zz-failed") }; throw e }
    }

    private fun requiredFlow(ui: UiSteps) {
        val m = MockServer("--lang", "ko", "--min-app-version", """{"android": 402400, "desktop": 100000003}""").also { mock = it }
        val app = m.app(tmp.newFolder(), lang = "ko")
        assertEquals(100000003L, app.profile.value?.minAppVersion?.get("desktop"))
        val s = serve(sha(installer))
        val opened = mutableListOf<File>()
        app.updates = Updater("1.0.2", DesktopPlatform.Os.MAC, s.url("/").toString().trimEnd('/'), tmp.newFolder(), opener = { opened += it; true }, store = false)
        val chatHandle = DesktopChat(app)
        val chat = chatHandle.vm
        compose.mainClock.autoAdvance = false
        compose.setContent {
            var page by remember { mutableStateOf(Page.CHAT) }
            KeloFrame(app) {
                Box(Modifier.size(1024.dp, 768.dp)) {
                    Shell(app, chat, page, onPage = { page = it }, onSignedOut = {}, connectorToOpen = null, onConnectorOpened = {}, side = chatHandle.side)
                }
            }
        }
        for (lang in listOf("ko", "en")) {
            val words = Words.of(lang)
            app.settings.setLanguage(lang); app.applyLanguage()
            ui.waitText(words.get(Res.string.update_required_title))
            ui.waitUntil("the check before the button", 10_000) { app.updates.state.value.phase == Phase.AVAILABLE }
            ui.waitText(words.get(Res.string.update_now))
            ui.waitText(words.get(Res.string.my_version, "1.0.2"))
            app.settings.setTheme("light"); ui.shot("parity-update-required-$lang-light")
            app.settings.setTheme("dark"); ui.shot("parity-update-required-$lang-dark")
            app.settings.setTheme("light")
        }
        // 업데이트 starts the download right there; the card shows its progress, then the installer opens
        val ko = Words.of("ko")
        app.settings.setLanguage("ko"); app.applyLanguage()
        ui.waitText(ko.get(Res.string.update_now))
        compose.onAllNodes(hasText(ko.get(Res.string.update_now)) and hasClickAction()).onFirst().performClick()
        ui.waitUntil("the download's progress", 15_000) { app.updates.state.value.phase == Phase.DOWNLOADING && app.updates.state.value.progress in 0.2f..0.8f }
        app.settings.setTheme("light"); ui.shot("parity-update-required-progress-ko-light")
        app.settings.setTheme("dark"); ui.shot("parity-update-required-progress-ko-dark")
        app.settings.setTheme("light")
        ui.waitUntil("the installer opened", 20_000) { app.updates.state.value.phase == Phase.OPENED }
        ui.waitText(ko.get(Res.string.desktop_update_quit_note))
        assertEquals(1, opened.size)
        assertTrue(app.updates.required.value)                            // it stays until the new version runs
    }
}
