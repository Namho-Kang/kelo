package chat.kelo.desktop

import chat.kelo.app.res.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import chat.kelo.app.notify.OpenTarget
import chat.kelo.desktop.core.AppContainer
import chat.kelo.app.my.VaultPage
import chat.kelo.app.wallet.WalletPage
import chat.kelo.desktop.pages.WorkPane
import chat.kelo.desktop.ui.KeloFrame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * Whether each card pays on its own (or why it asks), the auto-pay row's count, and what a schedule may do without asking (on the
 * wallet, the schedule's page and the login's page) against tools/mock_server.py (tools/mock_parts/ops.py), in Korean and English,
 * light and dark, in a wide and a narrow window. KELO_MOCK_WALLET=ask: no card pays on its own. Screenshots go to $KELO_SHOTS (else
 * build/parity) as wallet-auto-*.png and grants-*.png. The windows are tall so a whole page fits one picture.
 */
@OptIn(ExperimentalTestApi::class)
class GrantsPagesTest {
    private val shots = parityShots().also { it.mkdirs() }

    /** A window of [w]×[h] dp with [page] on a fresh mock in [lang] (`ask`: KELO_MOCK_WALLET=ask). */
    private fun run(
        lang: String, ask: Boolean, w: Int, h: Int, page: @Composable (AppContainer) -> Unit,
        env: Map<String, String> = emptyMap(), steps: Steps.(AppContainer) -> Unit,
    ) {
        val server = MockServer("--lang", lang, env = env + if (ask) mapOf("KELO_MOCK_WALLET" to "ask") else emptyMap())
        try {
            runDesktopComposeUiTest(w, h) {
                val app = server.app(Files.createTempDirectory("kelo-grants").toFile(), lang = lang)
                mainClock.autoAdvance = false
                setContent { KeloFrame(app) { Box(Modifier.fillMaxSize()) { page(app) } } }
                Steps(this, shots).steps(app)
            }
        } finally {
            server.stop()
        }
    }

    class Steps(private val t: ComposeUiTest, private val dir: File) {
        fun settle(frames: Int = 12) = repeat(frames) { t.mainClock.advanceTimeBy(64); Thread.sleep(10) }

        fun has(text: String): Boolean = t.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

        /** How many nodes show exactly [text]. */
        fun count(text: String): Int = t.onAllNodesWithText(text).fetchSemanticsNodes().size

        fun waitText(text: String, timeout: Long = 10_000) {
            val end = System.currentTimeMillis() + timeout
            while (!has(text)) {
                t.mainClock.advanceTimeBy(64)
                if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for \"$text\"")
                Thread.sleep(25)
            }
        }

        fun waitGone(text: String, timeout: Long = 10_000) {
            val end = System.currentTimeMillis() + timeout
            while (has(text)) {
                t.mainClock.advanceTimeBy(64)
                if (System.currentTimeMillis() > end) throw AssertionError("\"$text\" is still there")
                Thread.sleep(25)
            }
        }

        /** Presses the first clickable node with [text], or the [last] one (a dialog's button comes after the row's). */
        fun click(text: String, last: Boolean = false) {
            val nodes = t.onAllNodes(hasText(text) and hasClickAction())
            val i = if (last) nodes.fetchSemanticsNodes().size - 1 else 0
            nodes[i].performSemanticsAction(SemanticsActions.OnClick)
            settle(4)
        }

        /** The window and any open dialog drawn one over the other, light then dark, as [name]-light.png / [name]-dark.png. */
        fun shots(app: AppContainer, name: String) {
            for (theme in listOf("light", "dark")) {
                app.settings.setTheme(theme)
                settle(20)
                val roots = t.onAllNodes(isRoot())
                val layers = roots.fetchSemanticsNodes().indices.map { roots[it].captureToImage().toAwtImage() }
                val out = java.awt.image.BufferedImage(layers[0].getWidth(null), layers[0].getHeight(null), java.awt.image.BufferedImage.TYPE_INT_ARGB)
                out.createGraphics().apply { layers.forEach { drawImage(it, 0, 0, null) }; dispose() }
                ImageIO.write(out, "png", File(dir, "$name-$theme.png"))
            }
            app.settings.setTheme("light")
            settle(4)
        }
    }

    private val wallet: @Composable (AppContainer) -> Unit = { app -> WalletPage(onBack = {}, onSeen = {}) }

    /** Default mock: one card pays on its own, one asks (saved that way), one expired; the schedule's purchase can be revoked. */
    @Test fun walletCountsTheCardsThatPayOnTheirOwn() {
        for (lang in listOf("ko", "en")) {
            val s = Words.of(lang)
            val card = if (lang == "ko") "회사 카드" else "Work card"
            for ((w, h, tag) in listOf(Triple(760, 1500, ""), Triple(400, 2000, "-narrow"))) {
                run(lang, ask = false, w, h, wallet) { app ->
                    waitText(card)
                    waitText(s.get(Res.string.wallet_schedule_section))
                    assertTrue(has(s.get(Res.string.wallet_row_pays_auto)))
                    assertTrue(has(s.get(Res.string.wallet_reason_not_auto)))
                    assertTrue(has(s.get(Res.string.wallet_tag_asks)))
                    assertTrue(has(s.plural(Res.plurals.wallet_auto_cards_value, 1, 1)))
                    assertTrue(has(s.get(Res.string.wallet_first_site_rule)))
                    assertTrue(has(s.get(Res.string.grant_for_schedule, if (lang == "ko") "주간 생수 주문" else "Weekly water order")))
                    shots(app, "wallet-auto-list-$lang$tag")
                    if (tag.isNotEmpty()) return@run
                    // the card saved to ask each time: its reason, the way to turn auto-pay on (the phone), the first-site rule
                    click(card)
                    waitText(s.get(Res.string.wallet_auto_tip))
                    assertTrue(has(s.get(Res.string.wallet_reason_not_auto)))
                    assertTrue(has(s.get(Res.string.wallet_first_site_rule)))
                    shots(app, "wallet-auto-card-asks-$lang")
                }
            }
            run(lang, ask = false, 400, 1500, wallet) { app ->
                waitText(card)
                click(card)
                waitText(s.get(Res.string.wallet_auto_tip))
                shots(app, "wallet-auto-card-asks-$lang-narrow")
            }
        }
        // revoking the schedule's purchase asks first, then the section goes away
        run("ko", ask = false, 760, 1500, wallet) { _ ->
            val s = Words.of("ko")
            waitText(s.get(Res.string.wallet_schedule_section))
            click(s.get(Res.string.grant_revoke))
            waitText(s.get(Res.string.grant_revoke_confirm))
            click(s.get(Res.string.grant_revoke), last = true)
            waitGone(s.get(Res.string.wallet_schedule_section))
        }
    }

    /**
     * The nickname is each card's title with brand and last 4 under it, the default card has its badge, and a card's page shows the
     * payments made with it (older ones a page at a time), its default-card setting and no limits row. Deleting the default card
     * asks which card takes its place.
     */
    /**
     * Keyguard off on the server (KELO_MOCK_KEYGUARD=off): the auto-pay card is paused (AutoPayGate, as on the phone): its row says why
     * and carries a red "Paused" tag, the auto-pay summary says "Paused", and the first-payment hint is not shown.
     */
    @Test fun autoPayCardsPauseWhileKeyguardIsOff() {
        for (lang in listOf("ko", "en")) {
            val s = Words.of(lang)
            val everyday = if (lang == "ko") "생활비 카드" else "Everyday card"
            for ((w, h, tag) in listOf(Triple(760, 1500, ""), Triple(400, 2100, "-narrow"))) {
                run(lang, ask = false, w, h, wallet, env = mapOf("KELO_MOCK_KEYGUARD" to "off")) { app ->
                    waitText(everyday)
                    waitText(s.get(Res.string.wallet_auto_paused_keyguard))
                    val paused = s.get(Res.string.wallet_auto_paused_value)
                    assertTrue("the tag and the summary", count(paused) >= 2)
                    assertFalse(has(s.get(Res.string.wallet_row_pays_auto)))
                    assertFalse(has(s.get(Res.string.wallet_first_site_rule)))
                    assertFalse(has(s.plural(Res.plurals.wallet_auto_cards_value, 1, 1)))
                    shots(app, "wallet-keyguard-paused-$lang$tag")
                }
            }
        }
    }

    @Test fun cardsShowNicknameDefaultAndHistory() {
        for (lang in listOf("ko", "en")) {
            val s = Words.of(lang)
            val everyday = if (lang == "ko") "생활비 카드" else "Everyday card"
            val work = if (lang == "ko") "회사 카드" else "Work card"
            val old = if (lang == "ko") "예전 카드" else "Old card"
            for ((w, h, tag) in listOf(Triple(760, 1500, ""), Triple(400, 2100, "-narrow"))) {
                run(lang, ask = false, w, h, wallet) { app ->
                    waitText(everyday)
                    assertTrue(has(s.get(Res.string.wallet_tag_default)))
                    assertTrue(has("Visa ••4242") && has("Mastercard ••5100"))
                    shots(app, "wallet-cards-list-$lang$tag")
                    click(everyday)
                    waitText(s.get(Res.string.wallet_card_history))
                    waitText(s.get(Res.string.wallet_card_history_more))
                    assertTrue(has(s.get(Res.string.wallet_default_on)) && has(s.get(Res.string.wallet_nickname)))
                    assertFalse(has(s.get(Res.string.wallet_make_default)))                 // it is the default already
                    assertTrue(has(s.get(Res.string.wallet_open_chat)) && has(s.get(Res.string.wallet_open_task)))
                    shots(app, "wallet-card-history-$lang$tag")
                    if (tag.isNotEmpty()) return@run
                    click(s.get(Res.string.wallet_card_history_more))
                    waitGone(s.get(Res.string.wallet_card_history_more))                   // 26 payments: 20, then the last 6
                    click(s.get(Res.string.wallet_delete))
                    waitText(s.get(Res.string.wallet_delete_default_title))
                    assertTrue(has(s.get(Res.string.wallet_delete_default_body, everyday)))
                    shots(app, "wallet-card-delete-default-$lang")
                }
            }
            run(lang, ask = false, 400, 1500, wallet) { app ->
                waitText(work)
                click(work)
                waitText(s.get(Res.string.wallet_make_default))
                shots(app, "wallet-card-not-default-$lang-narrow")
                click(s.get(Res.string.wallet_make_default))
                waitGone(s.get(Res.string.wallet_make_default))
            }
            run(lang, ask = false, 400, 1500, wallet) { app ->
                waitText(old)
                click(old)
                waitText(s.get(Res.string.wallet_card_history_empty))
                shots(app, "wallet-card-history-empty-$lang-narrow")
            }
        }
    }

    /** KELO_MOCK_WALLET=ask: no card pays on its own, so the row says so and a note says how to change it. */
    @Test fun walletSaysWhenNoCardPaysOnItsOwn() {
        for (lang in listOf("ko", "en")) {
            val s = Words.of(lang)
            val card = if (lang == "ko") "생활비 카드" else "Everyday card"
            for ((w, h, tag) in listOf(Triple(760, 1500, ""), Triple(400, 2100, "-narrow"))) {
                run(lang, ask = true, w, h, wallet) { app ->
                    waitText(card)
                    assertTrue(has(s.get(Res.string.wallet_auto_none_value)))
                    assertFalse(has(s.get(Res.string.wallet_row_pays_auto)))
                    assertFalse(has(s.get(Res.string.wallet_first_site_rule)))
                    shots(app, "wallet-auto-list-ask-$lang$tag")
                }
            }
        }
    }

    /** Schedule 4 (the weekly order) lists its login and its purchase; revoking one asks first and takes it off. */
    @Test fun scheduleShowsWhatItMayDoWithoutAsking() {
        for (lang in listOf("ko", "en")) {
            val s = Words.of(lang)
            for ((w, h, tag) in listOf(Triple(1100, 1400, ""), Triple(520, 1900, "-narrow"))) {
                run(lang, ask = false, w, h, { app -> WorkPane(app, topInset = 16.dp) }) { app ->
                    waitText(s.get(Res.string.tab_work))   // the page is up (답이 필요해요 may push 지금 하는 일 below)
                    app.workRequests.value = OpenTarget.Schedule(4)
                    waitText(s.get(Res.string.grant_section_title))
                    assertTrue(has(s.get(Res.string.grant_purchase_title, if (lang == "ko") "생수 2L 12병" else "12 bottles of water (2 L)")))
                    assertTrue(has(s.get(Res.string.grant_login_title, if (lang == "ko") "쿠팡" else "Coupang")))
                    assertTrue(has(s.get(Res.string.grant_section_note)))
                    shots(app, "grants-schedule-$lang$tag")
                    if (lang != "ko" || tag.isNotEmpty()) return@run
                    click(s.get(Res.string.grant_revoke))
                    waitText(s.get(Res.string.grant_revoke_confirm))
                    click(s.get(Res.string.grant_revoke), last = true)
                    waitGone(s.get(Res.string.grant_purchase_title, "생수 2L 12병"))
                    assertTrue(has(s.get(Res.string.grant_login_title, "쿠팡")))
                }
            }
        }
    }

    /** The Coupang login names the schedule allowed to sign in with it. */
    @Test fun loginShowsTheScheduleThatUsesIt() {
        for (lang in listOf("ko", "en")) {
            val s = Words.of(lang)
            val login = if (lang == "ko") "쿠팡" else "Coupang"
            for ((w, h, tag) in listOf(Triple(760, 1500, ""), Triple(400, 2000, "-narrow"))) {
                run(lang, ask = false, w, h, { app -> VaultPage(onBack = {}, onGoModel = {}) }) { app ->
                    waitText("OpenWeather")
                    click(login)
                    waitText(s.get(Res.string.grant_login_section))
                    assertTrue(has(s.get(Res.string.grant_for_schedule, if (lang == "ko") "주간 생수 주문" else "Weekly water order")))
                    shots(app, "grants-login-$lang$tag")
                    if (lang != "ko" || tag.isNotEmpty()) return@run
                    click(s.get(Res.string.grant_revoke))
                    waitText(s.get(Res.string.grant_revoke_confirm))
                    click(s.get(Res.string.grant_revoke), last = true)
                    waitGone(s.get(Res.string.grant_login_section))
                }
            }
        }
    }
}

/** A schedule's purchase paid from the site's own balance comes as payment_auto without a card (and with its grant): no crash, no card. */
class ScheduleGrantEventsTest {
    @Test fun anAutomaticPaymentWithoutACardIsShownWithoutOne() {
        val ev = chat.kelo.app.chat.TurnEvent.parse(kotlinx.serialization.json.Json.parseToJsonElement(
            """{"type":"payment_auto","id":"call9","schedule_grant":{"id":1,"schedule_id":4,"via":"site_balance"},
               "payment":{"id":8,"at":1.0,"kind":"auto","site":"coupang.com","summary":"water","amount":12900,"currency":"KRW","amount_text":"12,900원"}}""",
        )) as chat.kelo.app.chat.TurnEvent.PaymentAuto
        org.junit.Assert.assertNull(ev.card)
        val items = chat.kelo.app.chat.ChatReducer.apply(emptyList(), ev) { "k1" }
        val row = items.filterIsInstance<chat.kelo.app.chat.ChatItem.PaymentAuto>().single()
        org.junit.Assert.assertEquals("", row.cardLabel)
        org.junit.Assert.assertEquals("12,900원", row.amountText)
    }
}
