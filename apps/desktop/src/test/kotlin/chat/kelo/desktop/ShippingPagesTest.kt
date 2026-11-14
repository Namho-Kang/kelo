package chat.kelo.desktop

import chat.kelo.desktop.chat.DesktopChat
import chat.kelo.app.chat.ChatViewModel

import chat.kelo.app.chat.AttachmentWords

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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import chat.kelo.app.chat.ChatItem
import chat.kelo.app.chat.ShippingFormState
import chat.kelo.desktop.core.AppContainer
import chat.kelo.desktop.core.MY_PAGE_WALLET
import chat.kelo.app.wallet.WalletPage
import chat.kelo.desktop.ui.KeloFrame
import chat.kelo.desktop.ui.Page
import chat.kelo.desktop.ui.Shell
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.performTextReplacement
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * Shipping addresses on the wallet page and the in-chat address form and final order card, against tools/mock_server.py
 * (tools/mock_parts/shipping.py, which serves the contract's sample JSON), in Korean and English, light and dark, wide and
 * narrow. Screenshots go to $KELO_SHOTS (else build/parity) as shipping-*.png:
 *   shipping-list-<lang>[-narrow]-<theme>        the wallet's address list next to the cards
 *   shipping-edit-kr-ko / -us-en [-narrow]       an address being changed (Korea's form in Korean, the US form in English)
 *   shipping-edit-problems-<lang>                 what the local checks say (a required field, the postal code's pattern)
 *   shipping-country-ko                           the country list, names in Korean
 *   shipping-form-kr-ko / -us-en [-narrow]        the chat's address form
 *   shipping-order-ko / -en [-narrow]             the one final order card (with the folded address form above it)
 */
@OptIn(ExperimentalTestApi::class)
class ShippingPagesTest {
    private val shots = parityShots().also { it.mkdirs() }

    private fun run(lang: String, w: Int, h: Int, page: @Composable (AppContainer) -> Unit, steps: Steps.(AppContainer, MockServer) -> Unit) {
        val server = MockServer("--lang", lang)
        try {
            runDesktopComposeUiTest(w, h) {
                val app = server.app(Files.createTempDirectory("kelo-ship").toFile(), lang = lang)
                mainClock.autoAdvance = false
                setContent { KeloFrame(app) { Box(Modifier.fillMaxSize()) { page(app) } } }
                Steps(this, shots).steps(app, server)
            }
        } finally {
            server.stop()
        }
    }

    class Steps(val t: ComposeUiTest, private val dir: File) {
        fun settle(frames: Int = 12) = repeat(frames) { t.mainClock.advanceTimeBy(64); Thread.sleep(10) }
        fun has(text: String): Boolean = t.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        fun hasDesc(desc: String): Boolean = t.onAllNodes(hasContentDescription(desc)).fetchSemanticsNodes().isNotEmpty()

        fun waitUntil(what: String, timeout: Long = 15_000, ok: () -> Boolean) {
            val end = System.currentTimeMillis() + timeout
            while (!ok()) {
                t.mainClock.advanceTimeBy(64)
                if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
                Thread.sleep(25)
            }
        }

        fun waitText(text: String, timeout: Long = 15_000) = waitUntil("\"$text\"", timeout) { has(text) }
        fun waitGone(text: String, timeout: Long = 15_000) = waitUntil("\"$text\" to go", timeout) { !has(text) }

        /** Presses the first clickable node whose text contains [part] (the address rows show "name · phone" in one line). */
        fun clickContaining(part: String) {
            val nodes = t.onAllNodes(hasText(part, substring = true) and hasClickAction())
            if (nodes.fetchSemanticsNodes().isEmpty()) throw AssertionError("nothing to click with \"$part\"")
            nodes[0].performSemanticsAction(SemanticsActions.OnClick)
            settle(4)
        }

        /** Presses the first (or [last]) clickable node with [text]. */
        fun click(text: String, last: Boolean = false) {
            val nodes = t.onAllNodes(hasText(text) and hasClickAction())
            val n = nodes.fetchSemanticsNodes().size
            if (n == 0) throw AssertionError("nothing to click with \"$text\"")
            nodes[if (last) n - 1 else 0].performSemanticsAction(SemanticsActions.OnClick)
            settle(4)
        }

        /** Presses the clickable node described as [desc] (a picker box). */
        fun clickDesc(desc: String) {
            t.onAllNodes(hasContentDescription(desc) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
            settle(4)
        }

        /** Replaces what the text field described as [desc] holds with [text]. */
        fun type(desc: String, text: String) {
            val node = t.onAllNodes((hasContentDescription(desc) or hasText(desc)) and hasSetTextAction())[0]
            node.performTextClearance()
            if (text.isNotEmpty()) node.performTextInput(text)
            settle(2)
        }

        /** The window and any open popup or dialog, light then dark, as [name]-light.png / [name]-dark.png. */
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

    private fun mockState(server: MockServer): JsonObject {
        val c = URI("${server.base}/mock/shipping").toURL().openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer mock-desktop-token")
        return Json.parseToJsonElement(c.inputStream.readBytes().decodeToString()).jsonObject
    }

    // ---- the wallet: the list next to the cards, wide and narrow

    @Test fun listNextToTheCards() {
        for (lang in listOf("ko", "en")) {
            val s = Words.of(lang)
            for ((w, h, tag) in listOf(Triple(760, 1700, ""), Triple(400, 2200, "-narrow"))) {
                run(lang, w, h, wallet) { app, _ ->
                    waitText(s.get(Res.string.shipping_section))
                    waitText(if (lang == "ko") "서울특별시 강남구 테헤란로 152, 10층 1004호, 06236" else "1600 Amphitheatre Pkwy, Bldg 43, MOUNTAIN VIEW, CA 94043")
                    assertTrue(has(if (lang == "ko") "집" else "Home"))
                    assertTrue(has(if (lang == "ko") "홍길동" else "Jane Roe"))
                    assertTrue(has("山田太郎"))
                    assertTrue(has(s.get(Res.string.wallet_tag_default)))
                    assertTrue(has(s.get(Res.string.shipping_add)))
                    shots(app, "shipping-list-$lang$tag")
                }
            }
        }
    }

    // ---- changing an address: Korea's form in Korean

    @Test fun editKoreanAddressInKorean() {
        val s = Words.of("ko")
        for ((w, h, tag) in listOf(Triple(760, 1700, ""), Triple(400, 2200, "-narrow"))) {
            run("ko", w, h, wallet) { app, server ->
                waitText("서울특별시 강남구 테헤란로 152, 10층 1004호, 06236")
                clickContaining("홍길동")
                waitText("우편번호")
                // the server's labels, in its order; the optional one says so; the phone without its calling code beside the chip
                assertTrue(has("이름")); assertTrue(has("주소")); assertTrue(has(s.get(Res.string.shipping_label_optional, "상세주소", "선택")))
                assertTrue(has("+82")); assertTrue(has("1012345678")); assertTrue(has("문 앞에 놓아 주세요"))
                assertTrue(has("대한민국"))
                assertTrue(has(s.get(Res.string.shipping_default_title)))
                shots(app, "shipping-edit-kr-ko$tag")
                if (tag.isNotEmpty()) return@run
                // the local checks: an empty required field and a postal code that does not fit Korea's pattern
                type("우편번호", "123")
                type("이름", "")
                click(s.get(Res.string.save))
                waitText(s.get(Res.string.shipping_err_pattern, "03051"))
                assertTrue(has(s.get(Res.string.shipping_err_required)))
                shots(app, "shipping-edit-problems-ko")
                // fixed: saved, back to the list, the server has the new values (the phone as E.164)
                type("우편번호", "06236")
                type("이름", "홍길순")
                click(s.get(Res.string.save))
                waitText(s.get(Res.string.shipping_section))
                waitText("홍길순")
                val saved = mockState(server)["profiles"]!!.jsonArray.map { it.jsonObject }.first { it["country"]!!.jsonPrimitive.content == "KR" }
                assertEquals("+821012345678", saved["phone"]!!.jsonPrimitive.content)
                assertEquals("06236", saved["postal_code"]!!.jsonPrimitive.content)
                // the country list: names in Korean, searchable
                clickContaining("홍길순")
                waitText("우편번호")
                settle(40)   // the page slides in (the phone's push animation)
                clickDesc("국가")
                settle(40)
                waitText(s.get(Res.string.select_search))
                assertTrue(has("네덜란드"))   // sorted by the Korean names (가나다 order): the Netherlands first
                val search = s.get(Res.string.select_search)
                type(search, "미")
                settle(6)
                assertTrue(has("미국")); assertFalse(has("일본"))
                fun retype(text: String) { t.onAllNodes(hasSetTextAction()).let { it[it.fetchSemanticsNodes().size - 1] }.performTextReplacement(text); settle(6) }   // the search field no longer shows its hint once it holds a text
                retype("일")   // the phone's search matches the name shown (and the code), not the English name
                assertTrue(has("일본")); assertFalse(has("미국"))
                retype("")
                shots(app, "shipping-country-ko")
            }
        }
    }

    // ---- the US form in English: change one, add one (with a country switch on the way), delete it

    @Test fun usAddressInEnglish() {
        val s = Words.of("en")
        for ((w, h, tag) in listOf(Triple(760, 1700, ""), Triple(400, 2200, "-narrow"))) {
            run("en", w, h, wallet) { app, server ->
                waitText("1600 Amphitheatre Pkwy, Bldg 43, MOUNTAIN VIEW, CA 94043")
                clickContaining("Jane Roe")
                waitText("ZIP code")
                assertTrue(has("Full name")); assertTrue(has("City")); assertTrue(has("State"))
                assertTrue(has(s.get(Res.string.shipping_label_optional, "Address line 2 (apt, suite, unit)", "optional")))
                assertTrue(has("California"))        // the state's name shows, its key (CA) is kept
                assertTrue(has("+1")); assertTrue(has("6502530000"))
                assertTrue(has("United States"))
                shots(app, "shipping-edit-us-en$tag")
                if (tag.isNotEmpty()) return@run
                // back, then a new address
                t.onAllNodes(hasContentDescription(s.get(Res.string.back)) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
                settle(4)
                waitText(s.get(Res.string.shipping_add))
                settle(40)                           // the page slides away (the phone's push animation); its fields must be gone before typing
                click(s.get(Res.string.shipping_add))
                waitText("ZIP code")                 // the server's default country (the default address's) is the US
                settle(40)
                assertTrue(has(s.get(Res.string.shipping_home)))
                type("Full name", "Sam Lee")
                type(s.get(Res.string.shipping_label_optional, "Address line 2 (apt, suite, unit)", "optional"), "Unit 5")
                // a trip to Japan's form keeps the keys both have (the name, line 2)
                clickDesc("Country")
                settle(40)
                waitText(s.get(Res.string.select_search))
                type(s.get(Res.string.select_search), "Japan")
                settle(6)
                click("Japan", last = true)
                waitText("〒 郵便番号")
                settle(40)
                assertTrue(has("Sam Lee")); assertTrue(has("Unit 5")); assertTrue(has("+81"))
                clickDesc("国")
                settle(40)
                waitText(s.get(Res.string.select_search))
                type(s.get(Res.string.select_search), "United States")
                settle(6)
                click("United States", last = true)
                waitText("ZIP code")
                settle(40)
                assertTrue(has("Sam Lee"))
                // the checks first: nothing filled for line 1, city, state; a bad ZIP
                type("ZIP code", "9404")
                click(s.get(Res.string.save))
                waitUntil("the format hint") { t.onAllNodes(hasText(s.get(Res.string.shipping_err_pattern, "").substringBefore("("), substring = true)).fetchSemanticsNodes().isNotEmpty() }
                shots(app, "shipping-edit-problems-en")
                t.onAllNodes(hasText(s.get(Res.string.shipping_home)) and hasSetTextAction())[0].performTextReplacement("Studio"); settle(2)
                type("Address line 1", "500 Main St")
                type("City", "Springfield")
                clickDesc("State")
                waitText("Illinois")
                click("Illinois", last = true)
                type("ZIP code", "62701")
                type("Phone number", "217 555 0100")
                click(s.get(Res.string.save))
                waitText(s.get(Res.string.shipping_section))
                waitText("Studio")
                waitText("500 Main St, Unit 5, SPRINGFIELD, IL 62701")
                val studio = mockState(server)["profiles"]!!.jsonArray.map { it.jsonObject }.first { it["label"]!!.jsonPrimitive.content == "Studio" }
                assertEquals("IL", studio["admin_area"]!!.jsonPrimitive.content)
                assertEquals("+12175550100", studio["phone"]!!.jsonPrimitive.content)
                // delete it, after the question
                click("Studio")
                waitText(s.get(Res.string.shipping_delete))
                click(s.get(Res.string.shipping_delete))
                waitText(s.get(Res.string.shipping_delete_confirm, "Studio"))
                click(s.get(Res.string.delete), last = true)
                waitText(s.get(Res.string.shipping_section))
                waitGone("Studio")
            }
        }
    }

    // ---- the chat: the address form, then the one final order card

    private fun chatRun(lang: String, w: Int, h: Int, steps: Steps.(AppContainer, ChatViewModel, MockServer) -> Unit) {
        // no background job's questions at the bottom of the chat (tools/mock_parts/chat.py), so the pictures show only this
        val server = MockServer("--lang", lang, env = mapOf("KELO_MOCK_NO_ASKS" to "1"))
        try {
            runDesktopComposeUiTest(w, h) {
                val app = server.app(Files.createTempDirectory("kelo-ship-chat").toFile(), lang = lang)
                val chatHandle = DesktopChat(app)
                val chat = chatHandle.vm
                mainClock.autoAdvance = false
                setContent {
                    KeloFrame(app) {
                        Box(Modifier.fillMaxSize()) {
                            Shell(app, chat, Page.CHAT, onPage = {}, onSignedOut = {}, connectorToOpen = null, onConnectorOpened = {}, side = chatHandle.side)
                        }
                    }
                }
                try { Steps(this, shots).steps(app, chat, server) } finally { chatHandle.close() }
            }
        } finally {
            server.stop()
        }
    }

    private fun ChatViewModel.say(text: String) {
        setInput(text)
        send(words = AttachmentWords({ it }, { it }, ", "))
    }

    @Test fun chatFormAndOrderInKorean() {
        val s = Words.of("ko")
        for ((w, h, tag) in listOf(Triple(900, 1800, ""), Triple(400, 2400, "-narrow"))) {
            chatRun("ko", w, h) { app, chat, server ->
                waitUntil("the chat") { chat.state.value.loaded }
                chat.say("블루 머그 2개 주문해 줘")
                waitText(s.get(Res.string.shipping_ask_title))
                waitText("우편번호")
                // prefilled from what Kelo remembers; the postal code is still empty
                assertTrue(has("홍길동")); assertTrue(has("010-1234-5678")); assertTrue(has("서울특별시 강남구 테헤란로 152"))
                assertTrue(has(s.get(Res.string.shipping_ask_save)))
                assertTrue(has(s.get(Res.string.shipping_ask_pay)))
                assertTrue(has("집"))
                type("우편번호", "06236")
                type(s.get(Res.string.shipping_label_optional, "상세주소", "선택"), "10층 1004호")
                shots(app, "shipping-form-kr-ko$tag")
                // "add a card": sent, folded into one line, and the wallet opens (cards are added on the phone)
                click(s.get(Res.string.shipping_pay_card))
                click(s.get(Res.string.shipping_ask_send))
                waitText(s.get(Res.string.shipping_ask_done, "집 · 서울특별시 강남구 테헤란로 152, 10층 1004호, 06236"))
                assertEquals(MY_PAGE_WALLET, app.myPageRequests.value)
                val answer = mockState(server)["answers"]!!.jsonObject.values.single().jsonObject
                assertEquals("card", answer["payment"]!!.jsonPrimitive.content)
                assertEquals("true", answer["save"]!!.jsonPrimitive.content)
                assertEquals("06236", answer["address"]!!.jsonObject["postal_code"]!!.jsonPrimitive.content)
                // the one final card: site, items, where it goes, the card, the coupon, the total
                waitText(s.get(Res.string.order_total))
                for (text in listOf("블루 머그", "색상: 네이비 · 용량: 350ml", s.get(Res.string.order_quantity, 2), "집 · 홍길동 · +821012345678",
                    "10층 1004호", "생활비 카드", "••4242", "WELCOME10", "24,000원", s.get(Res.string.order_ship_to), s.get(Res.string.order_pay_with),
                    s.get(Res.string.order_coupon), s.get(Res.string.chat_approval_deny))) {
                    assertTrue("\"$text\" on the order card", has(text))
                }
                shots(app, "shipping-order-ko$tag")
                if (tag.isNotEmpty()) return@chatRun
                // this card is kept end-to-end on a phone: the card says so (as on the phone; only that phone can send it)
                assertTrue(has(s.get(Res.string.chat_purchase_needs_card)))
                click(s.get(Res.string.chat_approval_deny), last = true)
                waitText("주문하지 않았어요.")
            }
        }
    }

    @Test fun chatFormAndOrderInEnglish() {
        val s = Words.of("en")
        for ((w, h, tag) in listOf(Triple(900, 1800, ""), Triple(400, 2400, "-narrow"))) {
            chatRun("en", w, h) { app, chat, server ->
                waitUntil("the chat") { chat.state.value.loaded }
                chat.say("Order two blue mugs")
                waitText(s.get(Res.string.shipping_ask_title))
                waitText("ZIP code")
                assertTrue(has("Jane Roe")); assertTrue(has("(650) 253-0000")); assertTrue(has("+1"))
                assertFalse(has(s.get(Res.string.shipping_ask_pay)))   // this one does not ask how to pay
                // sent half filled: the form says what is missing and sends nothing
                click(s.get(Res.string.shipping_ask_send))
                waitText(s.get(Res.string.shipping_err_required))
                assertTrue(mockState(server)["answers"]!!.jsonObject.isEmpty())
                type("Address line 1", "1600 Amphitheatre Pkwy")
                type("City", "Mountain View")
                clickDesc("State")
                waitText("California")
                click("California", last = true)
                type("ZIP code", "94043")
                settle(6)
                assertFalse(has(s.get(Res.string.shipping_err_required)))   // all filled: the hints are gone
                shots(app, "shipping-form-us-en$tag")
                click(s.get(Res.string.shipping_ask_send))
                waitText(s.get(Res.string.shipping_ask_done, "Home · 1600 Amphitheatre Pkwy, MOUNTAIN VIEW, CA 94043"))
                waitText(s.get(Res.string.order_total))
                for (text in listOf("Blue mug", "Color: Navy · 12 oz", "× 2", "Office · Jane Roe · +16502530000", "MOUNTAIN VIEW, CA 94043",
                    "Everyday card", "••4242", "24.00 USD")) {
                    assertTrue("\"$text\" on the order card", has(text))
                }
                assertFalse(has(s.get(Res.string.order_coupon)))       // no coupon, no coupon line
                shots(app, "shipping-order-en$tag")
                if (tag.isNotEmpty()) return@chatRun
                click(s.get(Res.string.chat_approval_deny), last = true)
                waitText("I didn't order it.")
            }
        }
    }

    /** A form cancelled here folds to its line; one whose reply stopped is only a record (no send). */
    @Test fun cancelledAndExpiredForms() {
        val s = Words.of("en")
        chatRun("en", 900, 1600) { app, chat, _ ->
            chat.say("Order a mug")
            waitText(s.get(Res.string.shipping_ask_send))
            click(s.get(Res.string.cancel), last = true)
            waitText(s.get(Res.string.shipping_ask_cancelled))
            waitUntil("the reply to end") { !chat.state.value.busy }
            chat.say("Order a mug again")
            waitUntil("the second form") { chat.state.value.items.count { it is ChatItem.ShippingForm } == 2 }
            waitText(s.get(Res.string.shipping_ask_send))
            chat.stop()
            waitUntil("the form to expire") {
                chat.state.value.items.filterIsInstance<ChatItem.ShippingForm>().last().state == ShippingFormState.EXPIRED
            }
            waitText(s.get(Res.string.shipping_ask_expired))
            assertFalse(has(s.get(Res.string.shipping_ask_send)))
            shots(app, "shipping-form-ended-en")
        }
    }
}
