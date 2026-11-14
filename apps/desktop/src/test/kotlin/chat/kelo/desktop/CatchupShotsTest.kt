package chat.kelo.desktop

import chat.kelo.app.chat.ToolChip
import chat.kelo.app.chat.BrowserCard

import chat.kelo.desktop.chat.DesktopChat
import chat.kelo.app.chat.ChatViewModel

import chat.kelo.app.chat.PaymentStepActions
import chat.kelo.app.chat.PaymentStepCard

import chat.kelo.app.res.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import chat.kelo.app.ui.theme.Kelo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import chat.kelo.app.agent.ScheduleGrantList
import chat.kelo.app.my.BriefingSettings
import chat.kelo.app.my.BriefingSettingsRules
import chat.kelo.desktop.core.AppContainer
import chat.kelo.app.chat.ChatItem
import chat.kelo.app.chat.PaymentStep
import chat.kelo.app.chat.PinNeed
import chat.kelo.app.chat.ToolState
import chat.kelo.app.notify.OpenTarget
import chat.kelo.desktop.chat.PinNeedCard
import chat.kelo.desktop.pages.WorkPane
import chat.kelo.desktop.ui.KeloFrame
import chat.kelo.desktop.ui.Page
import chat.kelo.desktop.ui.Shell
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The screens the desktop app gained to catch up with the phone (apps/desktop/CATCHUP.md), against tools/mock_server.py with
 * KELO_MOCK_CATCHUP (tools/mock_parts/catchup_desktop.py): the chat with the morning briefing, the setup card of a "when X happens,
 * do Y" task (urgent), a permission for many items with its list, and a payment step (approve on the phone: the code, the QR code,
 * the time left; the page's PIN keypad clicked with the mouse); the folded tool chips and the browser card; My → Notifications with
 * weekday and weekend hours and the briefing's settings; a watcher's and a job's grants. In Korean and English, light and dark, wide
 * and narrow. Saved as catchup-*.png in $KELO_SHOTS (else build/parity).
 */
class CatchupShotsTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private var mock: MockServer? = null

    @After fun stop() { mock?.stop() }

    @Test fun chatKoreanWide() = chatFlow("ko", wide = true)

    @Test fun chatEnglishNarrow() = chatFlow("en", wide = false)

    @Test fun keypad() {
        val words = Words.of("ko")
        val m = MockServer("--lang", "ko", env = mapOf("KELO_MOCK_CATCHUP" to "pad")).also { mock = it }
        val app = m.app(tmp.newFolder(), lang = "ko")
        val chatHandle = DesktopChat(app)
        val chat = chatHandle.vm
        val ui = UiSteps(compose, parityShots())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KeloFrame(app) { Box(Modifier.size(1024.dp, 768.dp)) { Shell(app, chat, Page.CHAT, onPage = {}, onSignedOut = {}, connectorToOpen = null, onConnectorOpened = {}, side = chatHandle.side) } }
        }
        try {
            ui.waitUntil("the chat loaded", timeout = 15_000) { chat.state.value.loaded && chat.state.value.asks.size == 2 }
            chat.refreshPaymentSteps()
            ui.waitUntil("the keypad step", timeout = 15_000) { chat.state.value.paymentSteps.isNotEmpty() }
            scrollTo(ui, "step:st-pad")
            ui.waitText(words.get(Res.string.pay_step_title_pin))
            // the keypad picture comes from the page; a click on it is a tap at the same spot there
            ui.waitUntil("the keypad picture") { !ui.has(words.get(Res.string.pay_step_pad_loading)) }
            repeat(3) {
                compose.onNode(hasContentDescription(words.get(Res.string.pay_step_pad))).performClick()
                ui.settle(12)
            }
            ui.waitText("●●●")
            shots(ui, app, "catchup-chat-ko-keypad")
            compose.onNode(hasText(words.get(Res.string.pay_step_clear))).performClick()
            ui.waitText(words.get(Res.string.pay_step_pad_none))
        } catch (e: Throwable) {
            runCatching { ui.shot("catchup-chat-ko-keypad-zz-failed") }
            throw e
        }
    }

    // ---- a 520 dp window in German, Arabic (right to left) and Thai: the new cards and settings wrap and mirror (light only)

    @Test fun chatNarrowGerman() = chatNarrow("de")

    @Test fun chatNarrowArabic() = chatNarrow("ar")

    @Test fun chatNarrowThai() = chatNarrow("th")

    @Test fun notifyNarrowGerman() = notifyNarrow("de")

    @Test fun notifyNarrowArabic() = notifyNarrow("ar")

    @Test fun notifyNarrowThai() = notifyNarrow("th")

    /** The chat at 520 dp in [lang] (the mock's sample data in English): the waiting cards, the setup card, the payment step, the
     *  list of many items and the morning briefing; nothing is drawn under the floating agent or the "↓" pill. */
    private fun chatNarrow(lang: String) {
        val words = Words.of(lang)
        val m = MockServer("--lang", "en", env = mapOf("KELO_MOCK_CATCHUP" to "1")).also { mock = it }
        val app = m.app(tmp.newFolder(), lang = lang)
        val chatHandle = DesktopChat(app)
        val chat = chatHandle.vm
        val ui = UiSteps(compose, parityShots())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KeloFrame(app) {
                Box(Modifier.size(520.dp, 768.dp)) { Shell(app, chat, Page.CHAT, onPage = {}, onSignedOut = {}, connectorToOpen = null, onConnectorOpened = {}, side = chatHandle.side) }
            }
        }
        val name = "catchup-narrow-$lang-chat"
        try {
            ui.waitUntil("the chat loaded", timeout = 15_000) {
                chat.state.value.items.any { it is ChatItem.Msg && it.briefing != null } && chat.state.value.asks.size == 2
            }
            ui.waitText(words.get(Res.string.chat_approval_list_show, 37))
            ui.shot("$name-asks-light")
            scrollTo(ui, "ask:ap-grant")
            ui.waitText(words.get(Res.string.task_grant_question))
            ui.shot("$name-grant-light")
            chat.refreshPaymentSteps()
            ui.waitUntil("the payment step", timeout = 15_000) { chat.state.value.paymentSteps.isNotEmpty() }
            scrollTo(ui, "step:st-app")
            ui.waitText(words.get(Res.string.desktop_pay_step_on_phone))
            ui.shot("$name-step-light")
            scrollTo(ui, "ask:ap-bulk")
            compose.onNode(hasText(words.get(Res.string.chat_approval_list_show, 37))).performSemanticsAction(SemanticsActions.OnClick)
            ui.waitText("1. deals1.example.com")
            ui.shot("$name-list-light")
            scrollTo(ui, "m3")
            ui.waitText(words.get(Res.string.briefing_title))
            ui.shot("$name-briefing-light")
        } catch (e: Throwable) {
            runCatching { ui.shot("$name-zz-failed") }
            throw e
        }
    }

    /** My → Notifications at 520 dp in [lang]: the hours by day type, the briefing's settings and "what it reads". */
    private fun notifyNarrow(lang: String) {
        val words = Words.of(lang)
        val m = MockServer("--lang", "en", env = mapOf("KELO_MOCK_CATCHUP" to "1")).also { mock = it }
        val app = m.app(tmp.newFolder(), lang = lang)
        val ui = UiSteps(compose, parityShots())
        val start = androidx.compose.runtime.mutableStateOf<String?>(MyView.NOTIFY)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KeloFrame(app) {
                Box(Modifier.size(520.dp, 768.dp).background(Kelo.colors.bg)) { MyPaneAt(app, start) }
            }
        }
        val name = "catchup-narrow-$lang-notify"
        try {
            ui.waitText(words.get(Res.string.hours_weekend), timeout = 15_000)
            showText(ui, words.get(Res.string.hours_section))
            ui.shot("$name-hours-light")
            showText(ui, words.get(Res.string.hours_suggest_heading_weekend))
            ui.shot("$name-weekend-light")
            showText(ui, words.get(Res.string.briefing_switch))
            ui.shot("$name-briefing-light")
            ui.waitText(words.get(Res.string.briefing_sources_title), timeout = 10_000)
            showText(ui, words.get(Res.string.briefing_sources_title))
            ui.shot("$name-sources-light")
        } catch (e: Throwable) {
            runCatching { ui.shot("$name-zz-failed") }
            throw e
        }
    }

    /** The chat list's row with [key] (a message "m3", a waiting card "ask:…", a payment step "step:…") scrolled into view. */
    private fun scrollTo(ui: UiSteps, key: String) {
        compose.onAllNodes(hasScrollToKeyAction()).onFirst().performScrollToKey(key)
        ui.settle(12)
    }

    private fun chatFlow(lang: String, wide: Boolean) {
        val words = Words.of(lang)
        val m = MockServer("--lang", lang, env = mapOf("KELO_MOCK_CATCHUP" to "1")).also { mock = it }
        val app = m.app(tmp.newFolder(), lang = lang)
        val chatHandle = DesktopChat(app)
        val chat = chatHandle.vm
        val ui = UiSteps(compose, parityShots())
        val size = if (wide) "wide" else "narrow"
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KeloFrame(app) {
                Box(Modifier.size(if (wide) 1024.dp else 520.dp, 768.dp)) {
                    Shell(app, chat, Page.CHAT, onPage = {}, onSignedOut = {}, connectorToOpen = null, onConnectorOpened = {}, side = chatHandle.side)
                }
            }
        }
        try {
            // the history (its newest agent message is the morning briefing) and the two waiting cards
            ui.waitUntil("the chat loaded", timeout = 15_000) {
                chat.state.value.items.any { it is ChatItem.Msg && it.briefing != null } && chat.state.value.asks.size == 2
            }
            // the setup card of a task (urgent), and a permission for many items, at the bottom
            ui.waitText(words.get(Res.string.chat_approval_list_show, 37))
            shots(ui, app, "catchup-chat-$lang-$size-asks")
            scrollTo(ui, "ask:ap-grant")
            ui.waitText(words.get(Res.string.task_grant_question))
            ui.waitText(words.get(Res.string.task_grant_once))
            shots(ui, app, "catchup-chat-$lang-$size-grant")

            // a payment step that needs the user: approved on the phone, the code and its time
            chat.refreshPaymentSteps()
            ui.waitUntil("the payment step", timeout = 15_000) { chat.state.value.paymentSteps.isNotEmpty() }
            scrollTo(ui, "step:st-app")
            ui.waitText(words.get(Res.string.pay_step_title_app))
            ui.waitText("482 913")
            ui.waitText(words.get(Res.string.desktop_pay_step_on_phone))
            shots(ui, app, "catchup-chat-$lang-$size-step")

            // the morning briefing, higher up
            scrollTo(ui, "m3")
            ui.waitText(words.get(Res.string.briefing_title))
            ui.waitText(words.get(Res.string.briefing_weather))
            shots(ui, app, "catchup-chat-$lang-$size-briefing")
            // a briefing's mail item writes a question into the composer (nothing is sent)
            val subject = if (lang == "ko") "계약서 검토 요청" else "Contract review request"
            compose.onAllNodes(hasText(subject)).onFirst().performSemanticsAction(SemanticsActions.OnClick)
            ui.waitUntil("the question in the composer") { chat.state.value.input.contains(subject) }

            // the list behind "see the list (37)"
            scrollTo(ui, "ask:ap-bulk")
            compose.onNode(hasText(words.get(Res.string.chat_approval_list_show, 37))).performSemanticsAction(SemanticsActions.OnClick)
            ui.waitText(words.get(Res.string.chat_approval_list_hide, 37))
            ui.waitText("1. deals1.example.com")
            shots(ui, app, "catchup-chat-$lang-$size-list")

            // Allow on the setup card: the server hears it and the card leaves the waiting list
            scrollTo(ui, "ask:ap-grant")
            compose.onAllNodes(hasText(words.get(Res.string.chat_approval_allow))).onFirst().performSemanticsAction(SemanticsActions.OnClick)
            ui.waitUntil("a card answered", timeout = 10_000) { chat.state.value.asks.count { it.open } < 2 }

            // "I approved it": the step goes on to the finishing click
            scrollTo(ui, "step:st-app")
            compose.onNode(hasText(words.get(Res.string.pay_step_approved))).performSemanticsAction(SemanticsActions.OnClick)
            ui.waitUntil("the step answered", timeout = 10_000) {
                chat.refreshPaymentSteps(); ui.settle(4); chat.state.value.paymentSteps.any { it.state == "finishing" }
            }
            scrollTo(ui, "step:st-app")
            ui.waitText(words.get(Res.string.pay_step_finishing))
            if (wide) shots(ui, app, "catchup-chat-$lang-$size-finishing")
        } catch (e: Throwable) {
            runCatching { ui.shot("catchup-chat-$lang-$size-zz-failed") }
            throw e
        }
    }

    /** The cards drawn on their own: folded tool chips (one open), one browser card for many sites, a payment PIN waiting for the
     *  phone, and a payment step that ended. */
    @Test fun cardsKorean() = cards("ko")

    @Test fun cardsEnglish() = cards("en")

    private fun cards(lang: String) {
        run {
            val m = MockServer("--lang", lang).also { mock = it }
            val app = m.app(tmp.newFolder(), lang = lang)
            val ui = UiSteps(compose, parityShots())
            val now = System.currentTimeMillis() / 1000
            val members = listOf(
                ChatItem.Tool("t1", "c1", "email_read", ToolState.OK, label = "메일 읽기", group = "mail", done = "메일 {n}개 읽음", detail = "청구서 · kim@example.com"),
                ChatItem.Tool("t2", "c2", "email_read", ToolState.OK, label = "메일 읽기", group = "mail", done = "메일 {n}개 읽음", detail = "주간 회의록"),
                ChatItem.Tool("t3", "c3", "email_read", ToolState.ERROR, label = "메일 읽기", group = "mail", done = "메일 {n}개 읽음", detail = "첨부 큰 메일"),
            )
            val folded = ChatItem.Tool("t1", "c1", "email_read", ToolState.OK, label = "메일 읽기", group = "mail", done = "메일 {n}개 읽음", members = members)
            val running = folded.copy(key = "t9", members = members.take(2) + members[2].copy(state = ToolState.RUNNING))
            val browser = ChatItem.Browser("b1", "c9", "Deals", "deals7.example.com", "https://deals7.example.com/unsubscribe",
                if (lang == "ko") "수신 거부 누르는 중" else "Clicking Unsubscribe", null, hosts = (1..7).map { "deals$it.example.com" })
            val pin = PinNeed("slot1", "pin1", if (lang == "ko") "생활비" else "Everyday", "shop.example.kr", if (lang == "ko") "32,000원" else "$27.00", 6, now + 95, urgent = true)
            val ended = PaymentStep("st1", PaymentStep.KIND_APP, "approved", site = "shop.example.kr")
            val otp = PaymentStep("st2", PaymentStep.KIND_OTP, "waiting", site = "shop.example.kr", summary = if (lang == "ko") "블루 머그" else "Blue mug",
                amountText = if (lang == "ko") "16,000원" else "$13.50", needsCode = true, expiresAt = now + 120, secondsTotal = 180,
                instruction = if (lang == "ko") "문자로 받은 6자리 코드를 넣어 주세요" else "Enter the 6-digit code from the text message")
            val actions = PaymentStepActions({ _, _ -> }, {}, { null }, { _, _ -> null }, { null })
            compose.mainClock.autoAdvance = false
            var open = false
            compose.setContent {
                KeloFrame(app) {
                    Row(Modifier.size(1024.dp, 768.dp).background(Kelo.colors.bg).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ToolChip(running)
                            ToolChip(folded)
                            BrowserCard(browser, onOpenLive = {})
                            PinNeedCard(pin, nowS = now)
                            PaymentStepCard(ended, actions, preview = true)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            PaymentStepCard(otp, actions, preview = true)
                        }
                    }
                }
            }
            val words = Words.of(lang)
            ui.waitText(words.get(Res.string.pin_need_title))
            compose.onAllNodes(hasText("▾", substring = true)).onFirst().performClick()
            ui.settle()
            shots(ui, app, "catchup-cards-$lang")
            m.stop()
        }
    }

    /** My → Notifications: weekday and weekend hours (a learned window, a weekend suggestion) and the morning briefing's settings. */
    @Test fun notifyKorean() = notifyPage("ko")

    @Test fun notifyEnglish() = notifyPage("en")

    private fun notifyPage(lang: String) {
        run {
            val words = Words.of(lang)
            val m = MockServer("--lang", lang, env = mapOf("KELO_MOCK_CATCHUP" to "1")).also { mock = it }
            val app = m.app(tmp.newFolder(), lang = lang)
            val ui = UiSteps(compose, parityShots())
            val start = androidx.compose.runtime.mutableStateOf<String?>(MyView.NOTIFY)
            compose.mainClock.autoAdvance = false
            compose.setContent {
                KeloFrame(app) {
                    Box(Modifier.size(1024.dp, 768.dp).background(Kelo.colors.bg)) { MyPaneAt(app, start) }
                }
            }
            ui.waitText(words.get(Res.string.hours_weekend), timeout = 15_000)
            ui.waitText(words.get(Res.string.briefing_switch))
            showText(ui, words.get(Res.string.hours_section))
            shots(ui, app, "catchup-notify-$lang-hours")
            showText(ui, words.get(Res.string.hours_suggest_heading_weekend))
            shots(ui, app, "catchup-notify-$lang-weekend")
            showText(ui, words.get(Res.string.briefing_switch))
            shots(ui, app, "catchup-notify-$lang-briefing")
            showText(ui, words.get(Res.string.briefing_sources_title))
            shots(ui, app, "catchup-notify-$lang-sources")
            if (lang == "ko") {
                // apply the learned weekday window: the page reads the hours again
                compose.onAllNodes(hasText(words.get(Res.string.hours_learned_apply))).onFirst().performSemanticsAction(SemanticsActions.OnClick)
                ui.waitUntil("the learned hours applied") {
                    runBlocking { app.sessions.requireApi().get<JsonObject>("/api/proactive") }["wake_start"]?.jsonPrimitive?.content == "08:00"
                }
                // a section switched off reaches the server
                compose.onAllNodes(hasText(words.get(Res.string.briefing_missed))).onFirst().performSemanticsAction(SemanticsActions.OnClick)
                ui.waitUntil("the section off") {
                    val b = runBlocking { app.sessions.requireApi().get<BriefingSettings>("/api/briefing") }
                    !BriefingSettingsRules.sectionOn(b, "missed")
                }
                // the briefing off: "what it reads" stays (the heads-ups read the same accounts)
                compose.onNode(hasContentDescription(words.get(Res.string.briefing_switch))).performSemanticsAction(SemanticsActions.OnClick)
                ui.waitUntil("the briefing off") {
                    !runBlocking { app.sessions.requireApi().get<BriefingSettings>("/api/briefing") }.enabled
                }
                ui.waitUntil("the briefing's settings folded") { !ui.has(words.get(Res.string.briefing_provider)) }
                showText(ui, words.get(Res.string.briefing_sources_title))
                ui.waitText(words.get(Res.string.briefing_sources_about))
                shots(ui, app, "catchup-notify-$lang-sources-briefing-off")
            }
            m.stop()
        }
    }

    /** A watcher's and a background job's page list what the task may do without asking (a purchase, a message, a change). */
    @Test fun taskGrants() {
        val words = Words.of("ko")
        val m = MockServer("--lang", "ko", env = mapOf("KELO_MOCK_CATCHUP" to "1")).also { mock = it }
        val app = m.app(tmp.newFolder(), lang = "ko")
        val ui = UiSteps(compose, parityShots())
        compose.mainClock.autoAdvance = false
        compose.setContent { KeloFrame(app) { Box(Modifier.size(1100.dp, 1400.dp)) { WorkPane(app, topInset = 16.dp) } } }
        ui.waitText(words.get(Res.string.work_asks), timeout = 15_000)   // questions wait: their section is at the top
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("sec:now")
        ui.waitText(words.get(Res.string.work_now), timeout = 15_000)
        // a background job, from the running task's card: a message and a change it may make without asking
        ui.waitText("항공사 세 곳 가격 확인")
        compose.onNode(hasText("항공사 세 곳 가격 확인")).performClick()
        ui.waitText(words.get(Res.string.grant_section_title), timeout = 10_000)
        ui.waitText(words.get(Res.string.task_grant_row_send, "slack, #travel"))
        shots(ui, app, "catchup-grants-job-ko")
        // revoke one: asks first, then it is gone on the server
        compose.onAllNodes(hasText(words.get(Res.string.grant_revoke))).onFirst().performClick()
        ui.settle()
        val buttons = compose.onAllNodes(hasText(words.get(Res.string.grant_revoke)))
        buttons[buttons.fetchSemanticsNodes().size - 1].performClick()
        ui.waitUntil("the grant revoked") {
            runBlocking { app.sessions.requireApi().get<ScheduleGrantList>("/api/workers/w-fares/grants") }.items.size == 1
        }
        compose.onAllNodes(hasContentDescription(words.get(Res.string.back))).onFirst().performClick()
        compose.mainClock.advanceTimeBy(200)
        // a watcher, from a notification: a purchase it may make once
        app.workRequests.value = OpenTarget.Watchers
        ui.waitText("제주 항공권 가격")
        ui.settle(30)
        compose.onNode(hasText("제주 항공권 가격")).performClick()
        ui.waitText(words.get(Res.string.grant_section_title), timeout = 10_000)
        ui.waitText(words.get(Res.string.task_grant_row_once), timeout = 10_000)
        shots(ui, app, "catchup-grants-watcher-ko")
    }

    /** Scrolls the page (one ScrollBy at a time) until the first node with [text] is inside the window, near its top. */
    private fun showText(ui: UiSteps, text: String) {
        // positionInRoot is the node's place even when it is scrolled out of view (boundsInRoot is clipped to the view then)
        fun top(): Float? = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().firstOrNull()?.positionInRoot?.y
        repeat(30) {
            val y = top() ?: return
            if (y in 60f..400f) return
            val lists = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes()
            val i = lists.indices.maxByOrNull { lists[it].boundsInRoot.height } ?: return
            compose.onAllNodes(hasScrollAction())[i].performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, (y - 160f).coerceIn(-500f, 500f)) }
            ui.settle(10)
            if (top() == y) return   // the end of the page
        }
    }

    private fun shots(ui: UiSteps, app: AppContainer, name: String) {
        for (theme in listOf("light", "dark")) {
            app.settings.setTheme(theme)
            ui.shot("$name-$theme")
        }
        app.settings.setTheme("light")
        ui.settle()
    }
}
