package chat.kelo.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import chat.kelo.app.chat.ChatItem
import chat.kelo.app.notify.OpenTarget
import chat.kelo.app.core.PairingClient
import chat.kelo.app.core.PairingLinks
import chat.kelo.app.core.Profile
import chat.kelo.app.chat.AttachmentWords
import chat.kelo.app.chat.ChatViewModel
import chat.kelo.desktop.chat.DesktopChat
import chat.kelo.desktop.core.AppContainer
import chat.kelo.desktop.core.LiveRequest
import chat.kelo.desktop.core.MY_PAGE_MODEL
import chat.kelo.desktop.core.DesktopPlatform
import chat.kelo.desktop.ui.KeloFrame
import chat.kelo.app.start.ConnectFlow
import chat.kelo.desktop.ui.Page
import chat.kelo.desktop.ui.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Screenshots of the real screens, drawn off screen with the same composables, state and server calls as the window (Compose's
 * ImageComposeScene), so they can be made on a machine whose screen cannot be captured:
 *
 *   KELO_HOME=/tmp/kelo-shots ./gradlew run --args="--snapshot screens --pair https://host/#pair=CODE [--lang ko] [--send TEXT] [--size 1920x1080 [--open schedules,model]]"
 *
 * Without a saved sign-in it first draws the connect screen, then pairs with --pair. --send types a message into the chat and
 * draws the reply while it streams (with its tool chips, approval card and answer buttons): use it only against a server whose
 * replies are scripted (tools/mock_server.py), never one that sends the text to a real model.
 */
object Snapshots {
    private var W = 1280
    private var H = 820
    private val DENSITY = Density(1.5f)

    fun run(dir: File, args: List<String>) {
        System.setProperty("java.awt.headless", "true")
        fun arg(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        dir.mkdirs()
        // --size 1920x1080: the window's size in pixels (the store's screenshots); only the main pages are drawn then
        val size = arg("--size")?.split("x")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }
        if (size != null) { W = (size[0] / DENSITY.density).toInt(); H = (size[1] / DENSITY.density).toInt() }
        val prefix = arg("--prefix")?.let { "$it-" }.orEmpty()
        val app = AppContainer()
        arg("--lang")?.let { app.settings.setLanguage(it); app.applyLanguage() }
        app.settings.setTheme("light")
        println("Kelo snapshots into ${dir.absolutePath} (data: ${DesktopPlatform.dataDir})")
        runBlocking {
            withContext(Dispatchers.Swing) {
                if (app.sessions.api.value == null) {
                    shot(dir, "${prefix}01-connect", W, H, 1200) { KeloFrame(app) { ConnectFlow(initialLink = arg("--pair").orEmpty()) } }
                    val link = PairingLinks.parse(arg("--pair") ?: error("--pair LINK is needed to sign in")) ?: error("not a pairing link")
                    val session = withContext(Dispatchers.IO) { app.pairing.pair(link, DesktopPlatform.deviceName() + " · Kelo") }
                    app.sessions.signIn(session)
                }
                withContext(Dispatchers.IO) { runCatching { app.sessions.requireApi().get<Profile>("/api/profile") }.onSuccess(app::setProfile) }
                val chatHandle = DesktopChat(app)
                val chat = chatHandle.vm
                val send = arg("--send")
                if (send == null) {
                    shot(dir, "${prefix}02-chat", W, H, 5000) { page(app, chat, Page.CHAT) }
                    shot(dir, "${prefix}03-goals", W, H, 3000) { page(app, chat, Page.GOALS) }
                    shot(dir, "${prefix}04-work", W, H, 4000) { page(app, chat, Page.WORK) }
                    shot(dir, "${prefix}05-connectors", W, H, 4000) { page(app, chat, Page.CONNECTORS) }
                    shot(dir, "${prefix}06-my", W, if (size != null) H else 2300, 4000) { page(app, chat, Page.MY) }
                    if (size != null) { openedPages(app, chat, dir, prefix, arg("--open")); chatHandle.close(); println("done"); kotlin.system.exitProcess(0) }
                    app.settings.setTheme("dark")
                    shot(dir, "${prefix}07-chat-dark", W, H, 3000) { page(app, chat, Page.CHAT) }
                    shot(dir, "${prefix}08-my-dark", W, 2300, 3000) { page(app, chat, Page.MY) }
                    app.settings.setTheme("light")
                    // a narrow window in long-word and right-to-left languages: every label must wrap, nothing cut or overlapping
                    for (lang in listOf("de", "ar", "th")) {
                        app.settings.setLanguage(lang)
                        shot(dir, "${prefix}09-narrow-my-$lang", 520, 2600, 2500) { page(app, chat, Page.MY) }
                        shot(dir, "${prefix}10-narrow-chat-$lang", 520, 900, 2500) { page(app, chat, Page.CHAT) }
                    }
                } else {
                    // a scripted reply: streaming text, tool chips, an approval card, then answer buttons
                    var started = false
                    shot(dir, "${prefix}11-chat-streaming", W, H, 6000, onFrame = { t ->
                        if (!started && t > 1500) {
                            started = true
                            chat.setInput(send)
                            chat.send(words = AttachmentWords({ it }, { it }, ", "))
                        }
                    }) { page(app, chat, Page.CHAT) }
                    shot(dir, "${prefix}12-chat-approval", W, H, 8000, until = { chat.state.value.items.any { it is ChatItem.Approval } }) { page(app, chat, Page.CHAT) }
                    chat.state.value.items.filterIsInstance<ChatItem.Approval>().firstOrNull { it.open }?.let { chat.approve(it.id, true) }
                    shot(dir, "${prefix}13-chat-replies", W, H, 15000, until = { !chat.state.value.busy && chat.state.value.items.isNotEmpty() }) { page(app, chat, Page.CHAT) }
                    app.settings.setTheme("dark")
                    shot(dir, "${prefix}14-chat-replies-dark", W, H, 2000) { page(app, chat, Page.CHAT) }
                    app.settings.setTheme("light")
                    // the time lines above messages that start a new stretch: a tall window shows the whole history (mock --spread)
                    shot(dir, "${prefix}15-chat-timelines", W, 2100, 2500) { page(app, chat, Page.CHAT) }
                    app.settings.setTheme("dark")
                    shot(dir, "${prefix}16-chat-timelines-dark", W, 2100, 2500) { page(app, chat, Page.CHAT) }
                    app.settings.setTheme("light")
                    shot(dir, "${prefix}17-chat-timelines-narrow", 520, 2100, 2500) { page(app, chat, Page.CHAT) }
                }
                chatHandle.close()
            }
        }
        println("done")
        kotlin.system.exitProcess(0)
    }

    /**
     * --open schedules,model,task:ID,tab:ID: more screens a click away from the main pages (Work -> Schedules, My -> Model, a task's
     * page, a browser tab live), drawn the same way: the page the app would be on once the request is handled.
     */
    private suspend fun openedPages(app: AppContainer, chat: ChatViewModel, dir: File, prefix: String, spec: String?) {
        for (item in spec.orEmpty().split(",").filter { it.isNotBlank() }) {
            val kind = item.substringBefore(":")
            val id = item.substringAfter(":", "")
            val page = when (kind) {
                "schedules" -> { app.openRequests.value = OpenTarget.Schedules; Page.WORK }
                "task" -> { app.openRequests.value = OpenTarget.Task(id.toLong()); Page.WORK }
                "model" -> { app.myPageRequests.value = MY_PAGE_MODEL; Page.MY }
                "tab" -> { app.liveTab.value = LiveRequest(id, false, null); Page.CHAT }
                else -> continue
            }
            shot(dir, "${prefix}20-open-${item.replace(':', '-')}", W, H, 5000) { page(app, chat, page) }
            app.liveTab.value = null
        }
    }

    @Composable
    private fun page(app: AppContainer, chat: ChatViewModel, page: Page) {
        KeloFrame(app) { Shell(app, chat, page, onPage = {}, onSignedOut = {}, connectorToOpen = null, onConnectorOpened = {}) }
    }

    /**
     * Draws [content] for [settleMs] (frames every 50 ms, so effects load their data and animations move), or until [until] holds
     * (then a little longer), and writes the last frame as [name].png.
     */
    private suspend fun shot(
        dir: File, name: String, w: Int, h: Int, settleMs: Long,
        until: (() -> Boolean)? = null, onFrame: (Long) -> Unit = {},
        content: @Composable () -> Unit,
    ) {
        val scene = ImageComposeScene((w * DENSITY.density).toInt(), (h * DENSITY.density).toInt(), DENSITY, coroutineContext = Dispatchers.Swing, content = content)
        try {
            val start = System.currentTimeMillis()
            var metAt = -1L
            while (true) {
                val t = System.currentTimeMillis() - start
                onFrame(t)
                scene.render(System.nanoTime())
                if (until != null && metAt < 0 && until()) metAt = t
                if (t >= settleMs || (metAt >= 0 && t - metAt > 1200)) break
                delay(50)
            }
            val img = scene.render(System.nanoTime())
            File(dir, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            println("  $name.png")
        } finally {
            scene.close()
        }
    }
}
