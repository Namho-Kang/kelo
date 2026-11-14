package chat.kelo.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.printToString
import chat.kelo.app.core.Profile
import chat.kelo.app.core.Session
import chat.kelo.desktop.core.AppContainer
import chat.kelo.desktop.core.FileKeyValueStore
import chat.kelo.desktop.core.StoreKey
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import javax.imageio.ImageIO

/**
 * The Python that runs tools/mock_server.py: python3, else python (windows-latest has only `python`, and its `python3` may be the
 * Store stub, which starts but fails). Null when neither runs; the UI tests are then skipped.
 */
internal val mockPython: String? by lazy {
    listOf("python3", "python").firstOrNull { exe ->
        runCatching {
            val p = ProcessBuilder(exe, "-c", "import sys; sys.exit(0 if sys.version_info[0] == 3 else 1)").redirectErrorStream(true).start()
            p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
    }
}

/**
 * tools/mock_server.py for the UI tests: started on a free port (never one of the ports other Kelo tools use), stopped by [stop]
 * (and when the test run ends); what it prints goes to build/mock-server/mock-<port>.log. Without Python 3 ([mockPython]) the
 * test is skipped. [env]: extra variables for it (e.g. KELO_MOCK_WALLET=ask).
 */
class MockServer(vararg args: String, env: Map<String, String> = emptyMap()) {
    /**
     * Where the app talks to: the KE2E gateway in front of the mock ([e2e]), else the mock itself. Plain requests (the tests' own
     * /mock/... switches, /healthz) pass through the gateway unchanged.
     */
    val base: String
    /** The app talks KE2E here (spec 5.18: the app never talks in the clear), through apps/ios/tools/mock_e2e_gateway.py */
    val e2e: Boolean
    private val proc: Process
    private var gateway: Process? = null
    private var gatewayPort = 0

    init {
        val port = freePort()
        val script = File("tools/mock_server.py").absoluteFile
        // what it prints (its first line, a traceback when the app drops a connection) goes to a file: nothing reads a pipe, and
        // once Windows' small pipe buffer is full the server would stall on its next traceback
        val log = File("build/mock-server").apply { mkdirs() }.resolve("mock-$port.log")
        val p = runCatching {
            ProcessBuilder(listOf(mockPython ?: "python3", script.path, "--port", "$port") + args).redirectErrorStream(true)
                .redirectOutput(log).also { it.environment().putAll(env) }.start()
        }.getOrNull()
        assumeTrue("Python 3 is needed for the mock server", mockPython != null && p != null)
        proc = p!!
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
        val plain = "http://127.0.0.1:$port"
        assumeTrue("the mock server did not start", healthy(plain, 10_000))
        // KE2E in front of it: the server's own gateway (needs the server's Python environment: the repository's .venv, or uv);
        // without one (a CI runner with only plain Python) the UI tests talk to the mock in the clear (KELO_MOCK_E2E=0 forces that)
        gateway = if (System.getenv("KELO_MOCK_E2E") == "0") null else startGateway(port)
        e2e = gateway != null
        base = if (e2e) "http://127.0.0.1:${gatewayPort}" else plain
    }

    private fun startGateway(upstream: Int): Process? {
        val root = File("../..").absoluteFile.normalize()
        val gw = File(root, "apps/ios/tools/mock_e2e_gateway.py")
        if (!gw.isFile) return null
        val venv = listOf(File(root, ".venv/bin/python"), File(root, ".venv/Scripts/python.exe")).firstOrNull { it.canExecute() }
        val uv = listOfNotNull(System.getenv("UV"), "${System.getProperty("user.home")}/.local/bin/uv", "/opt/homebrew/bin/uv", "/usr/local/bin/uv")
            .firstOrNull { File(it).canExecute() }
        val python = when {
            venv != null -> listOf(venv.path)
            uv != null -> listOf(uv, "run", "--project", root.path, "python")
            else -> return null
        }
        gatewayPort = freePort()
        val log = File("build/mock-server").resolve("gateway-$gatewayPort.log")
        val g = runCatching {
            ProcessBuilder(python + listOf(gw.path, "--port", "$gatewayPort", "--upstream", "$upstream")).directory(root)
                .redirectErrorStream(true).redirectOutput(log).start()
        }.getOrNull() ?: return null
        if (!healthy("http://127.0.0.1:$gatewayPort", 30_000) || !e2eInfo("http://127.0.0.1:$gatewayPort")) {
            g.descendants().forEach { it.destroyForcibly() }
            g.destroyForcibly()
            return null
        }
        return g
    }

    private fun healthy(url: String, waitMs: Long): Boolean {
        val end = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < end) {
            val ok = runCatching { (URI("$url/healthz").toURL().openConnection() as HttpURLConnection).responseCode == 200 }.getOrDefault(false)
            if (ok) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun e2eInfo(url: String): Boolean =
        runCatching { (URI("$url/api/e2e/info").toURL().openConnection() as HttpURLConnection).responseCode == 200 }.getOrDefault(false)

    fun stop() {
        gateway?.let { g -> g.descendants().forEach { it.destroyForcibly() }; g.destroyForcibly() }
        proc.destroyForcibly()
    }

    /**
     * A signed-in app against this server, with its own data folder, in [lang] and [theme]: through KE2E (the key from the gateway's
     * /api/e2e/info, pinned as from a link) whenever the gateway runs, as the real app does.
     */
    fun app(dir: File, lang: String = "en", theme: String = "light"): AppContainer {
        val app = AppContainer(dir, FileKeyValueStore(File(dir, "kelo.properties")), StoreKey.cipher(dir), requireE2e = e2e)
        app.settings.setLanguage(lang)
        app.applyLanguage()
        app.settings.setTheme(theme)
        val pin = if (!e2e) null else runBlocking { chat.kelo.app.core.e2e.E2eInfoCalls.fetch(okhttp3.OkHttpClient(), base) }
            ?.let { chat.kelo.app.core.e2e.KeyPin(it, verified = true) }
        app.sessions.signIn(Session(base, "mock-desktop-token", pin))
        runBlocking { app.setProfile(app.sessions.requireApi().get<Profile>("/api/profile")) }
        return app
    }

    companion object {
        private val TAKEN = setOf(8080, 8765, 8786, 8787, 6080) + (8100..8103) + (6100..6102)

        fun freePort(): Int {
            while (true) {
                val port = ServerSocket(0).use { it.localPort }
                if (port !in TAKEN) return port
            }
        }
    }
}

/** Waiting and screenshots with a hand-driven clock (the character and the spinners never let the page go idle). */
class UiSteps(private val compose: ComposeContentTestRule, private val shots: File) {
    init { shots.mkdirs() }

    fun waitUntil(what: String, timeout: Long = 10_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (true) {
            compose.mainClock.advanceTimeBy(64)
            if (ok()) return
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(25)
        }
    }

    /**
     * Does [action] (a click, a scroll) and waits for [ok]; when it does not come within [every] ms the action is done again, until
     * [timeout]. For steps whose target can be redrawn under them by an answer that arrives late (a list reloaded when a dialog
     * closes, a section filled in after the page scrolled): the KE2E gateway in front of the mock adds a little time to each call.
     */
    fun retry(what: String, timeout: Long = 10_000, every: Long = 2_000, action: () -> Unit, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (true) {
            runCatching(action)
            val round = System.currentTimeMillis() + every
            while (System.currentTimeMillis() < round) {
                compose.mainClock.advanceTimeBy(64)
                if (ok()) return
                Thread.sleep(25)
            }
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
        }
    }

    fun waitText(text: String, timeout: Long = 10_000) =
        waitUntil("\"$text\"", timeout) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    fun has(text: String): Boolean = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /**
     * performScrollTo on [node] with the test clock running by itself for the call. performScrollTo repeats its scroll until the node is
     * on screen and relies on a frame being drawn between two tries; with `mainClock.autoAdvance = false` no frame comes and it never returns.
     */
    fun scrollTo(node: androidx.compose.ui.test.SemanticsNodeInteraction) {
        val before = compose.mainClock.autoAdvance
        compose.mainClock.autoAdvance = true
        try { node.performScrollTo() } finally { compose.mainClock.autoAdvance = before }
        settle()
    }

    fun settle(frames: Int = 8) = repeat(frames) { compose.mainClock.advanceTimeBy(64); Thread.sleep(10) }

    /**
     * The window and any open popup or dialog (each a root of its own), drawn one over the other, as [name].png. The shot a failed
     * test ends on ("...zz-failed...") also prints what is on the screen (the semantics tree) into the test's output, for a CI run
     * on a machine nobody can look at.
     */
    fun shot(name: String) {
        if ("zz-failed" in name) runCatching { println("$name, on the screen:\n" + compose.onAllNodes(isRoot()).printToString(Int.MAX_VALUE)) }
        settle()
        val roots = compose.onAllNodes(isRoot())
        val layers = roots.fetchSemanticsNodes().indices.map { roots[it].captureToImage().toAwtImage() }
        val out = java.awt.image.BufferedImage(layers[0].getWidth(null), layers[0].getHeight(null), java.awt.image.BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().apply { layers.forEach { drawImage(it, 0, 0, null) }; dispose() }
        ImageIO.write(out, "png", File(shots, "$name.png"))
    }
}

/** Where the parity screenshots go: $KELO_SHOTS, else build/parity (copy the ones worth keeping to screens/). */
fun parityShots(): File = System.getenv("KELO_SHOTS")?.let(::File) ?: File("build/parity")
