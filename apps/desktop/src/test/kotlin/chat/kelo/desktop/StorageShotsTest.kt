package chat.kelo.desktop

import chat.kelo.app.res.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import chat.kelo.app.core.Profile
import chat.kelo.app.core.Session
import chat.kelo.desktop.core.AppContainer
import chat.kelo.desktop.core.FileKeyValueStore
import chat.kelo.desktop.core.StoreKey
import chat.kelo.desktop.ui.KeloFrame
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * The storage warning and the cleanup screen against the REAL server (src/assistant/server.py through tools/storage_demo_server.py:
 * a filled data folder, the cap at 83% / 96%, or the server's own disk almost full). My home's row, the resources page's warning
 * with 정리하기, the cleanup screen, the confirm dialog and the result; Korean and English, light and dark, and a narrow window.
 * Saved as storage-*.png in build/parity ($KELO_SHOTS). Needs the server's environment (.venv, or `uv`); skipped without it.
 */
@OptIn(ExperimentalTestApi::class)
class StorageShotsTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val procs = mutableListOf<Process>()
    private val start = androidx.compose.runtime.mutableStateOf<String?>(null)

    @After fun stop() { procs.forEach { it.destroy(); it.waitFor(); } }

    private fun server(lang: String, level: String): String {
        val root = File("../..").absoluteFile.normalize()
        val port = MockServer.freePort()
        val p = runCatching {
            val uv = listOfNotNull(System.getenv("UV"), "${System.getProperty("user.home")}/.local/bin/uv", "/opt/homebrew/bin/uv", "/usr/local/bin/uv")
                .firstOrNull { File(it).canExecute() } ?: "uv"
            // the project's own environment when it is there (one process to stop), else through uv
            val venv = File(root, ".venv/bin/python")
            val python = if (venv.canExecute()) listOf(venv.path) else listOf(uv, "run", "--project", root.path, "python")
            ProcessBuilder(python + listOf(File(root, "tools/storage_demo_server.py").path,
                "--state", tmp.newFolder().path, "--lang", lang, "--level", level, "--port", "$port"))
                .directory(root).redirectErrorStream(true).redirectOutput(File(tmp.root, "server-$port.log")).start()
        }.getOrNull()
        assumeTrue("uv is needed to run the real server", p != null)
        procs += p!!
        val base = "http://127.0.0.1:$port"
        val end = System.currentTimeMillis() + 60_000
        var up = false
        while (!up && System.currentTimeMillis() < end && p.isAlive) {
            up = runCatching { (URI("$base/healthz").toURL().openConnection() as HttpURLConnection).responseCode == 200 }.getOrDefault(false)
            if (!up) Thread.sleep(200)
        }
        assumeTrue("the server did not start: " + File(tmp.root, "server-$port.log").readText().takeLast(2000), up)
        return base
    }

    private fun app(base: String, lang: String, theme: String): AppContainer {
        val dir = tmp.newFolder()
        val app = AppContainer(dir, FileKeyValueStore(File(dir, "kelo.properties")), StoreKey.cipher(dir), requireE2e = false)
        app.settings.setLanguage(lang)
        app.applyLanguage()
        app.settings.setTheme(theme)
        app.sessions.signIn(Session(base, "kelo-storage-demo-token-0001"))
        runBlocking { app.setProfile(app.sessions.requireApi().get<Profile>("/api/profile")) }
        return app
    }

    /** A mouse wheel over the page (no animated scrollTo: the clock is driven by hand here). */
    private fun scroll(anyText: String, ticks: Float) {
        repeat(10) {
            compose.onAllNodesWithText(anyText, substring = true).onFirst().performMouseInput { scroll(ticks / 10) }
            repeat(15) { compose.mainClock.advanceTimeBy(64); Thread.sleep(10) }
        }
    }

    @Test fun koWarn() = walk("ko", "warn", 1000, cleanup = true)

    @Test fun enCritical() = walk("en", "critical", 1000, cleanup = true)

    @Test fun koHostLow() = walk("ko", "host", 1000, cleanup = false)

    @Test fun enHostLow() = walk("en", "host", 1000, cleanup = false)

    @Test fun deNarrow() = walk("de", "critical", 360, cleanup = true, tag = "narrow-")

    private fun walk(lang: String, level: String, width: Int, cleanup: Boolean, tag: String = "") {
        val base = server(if (lang == "ko") "ko" else "en", level)
        val s = Words.of(lang)
        val app = app(base, lang, "light")
        val ui = UiSteps(compose, parityShots())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.size(width.dp, 2400.dp)) {   // tall: the whole cleanup list fits, nothing to scroll
                KeloFrame(app) { MyPaneAt(app, start) }
            }
        }
        val name = "storage-$tag$level-$lang"
        try {
            val row = when (level) {
                "host" -> s.get(Res.string.storage_row_host)
                "critical" -> s.get(Res.string.storage_row_critical)
                else -> s.get(Res.string.storage_row_warn, 82).substringBefore("82")
            }
            ui.waitText(row, timeout = 20_000)                                    // My home: the row says it
            for (theme in listOf("light", "dark")) {
                app.settings.setTheme(theme)
                ui.settle()
                ui.shot("$name-my-$theme")
            }
            app.settings.setTheme("light")
            compose.onAllNodesWithText(s.get(Res.string.resources_title)).onFirst().performClick()   // as a person would
            val clean = s.get(Res.string.storage_clean_up)
            ui.waitText(clean, timeout = 20_000)                                  // the warning with 정리하기
            for (theme in listOf("light", "dark")) {
                app.settings.setTheme(theme)
                ui.settle()
                ui.shot("$name-resources-$theme")
            }
            app.settings.setTheme("light")
            if (!cleanup) return
            compose.onAllNodesWithText(clean).onFirst().performClick()
            val intro = s.get(Res.string.storage_cleanup_intro)
            ui.waitText(intro, timeout = 20_000)
            ui.waitText(s.get(Res.string.storage_group_uploads))
            for (theme in listOf("light", "dark")) {
                app.settings.setTheme(theme)
                ui.settle()
                ui.shot("$name-cleanup-$theme")
            }
            app.settings.setTheme("light")
            // tick the old uploads (the whole group) and the older backups, then delete with the confirm dialog
            compose.onAllNodesWithText(s.get(Res.string.storage_group_uploads)).onFirst().performClick()
            scroll(intro, 200f)                                                      // the window is 768 high: down to the end
            compose.onAllNodesWithText(s.get(Res.string.storage_group_backups)).onFirst().performClick()
            ui.settle()
            val delete = s.get(Res.string.storage_delete_button)
            ui.shot("$name-selected-light")
            compose.onAllNodesWithText(delete).onFirst().performClick()
            ui.waitText(s.get(Res.string.storage_confirm_title))
            ui.shot("$name-confirm-light")
            compose.onAllNodesWithText(delete).fetchSemanticsNodes().size.let { check(it >= 2) { "the dialog's button" } }
            compose.onAllNodesWithText(delete)[compose.onAllNodesWithText(delete).fetchSemanticsNodes().size - 1].performClick()
            ui.waitText(s.get(Res.string.storage_deleted, "").trim().substringBefore(" ").ifEmpty { "✓" }, timeout = 20_000)
            ui.settle(20)
            check(!ui.has(s.get(Res.string.storage_group_uploads))) { "the old uploads are gone from the list" }
            scroll(intro, -200f)                                                     // back to the top, where the result is
            ui.shot("$name-result-light")
            app.settings.setTheme("dark")
            ui.settle()
            ui.shot("$name-result-dark")
            app.settings.setTheme("light")
        } catch (e: Throwable) {
            runCatching { ui.shot("$name-zz-failed") }
            throw e
        }
    }
}
