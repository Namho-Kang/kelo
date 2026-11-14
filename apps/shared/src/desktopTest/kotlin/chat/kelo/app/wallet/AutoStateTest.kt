package chat.kelo.app.wallet

import chat.kelo.app.agent.ScheduleGrant
import chat.kelo.app.chat.vault.VaultInfo
import chat.kelo.app.my.VaultEntry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whether a card pays on its own, why not, how many cards do, and what a schedule may do without asking, as the server sends them. */
class AutoStateTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun card(extra: String) = json.decodeFromString<WalletCard>(
        """{"id":"c1","label":"Visa ••4242","brand":"visa","last4":"4242","usable":true,"expired":false,$extra}""",
    )

    @Test fun aCardSavedToAskEachTimeSaysSoEvenWhenTheWalletIsNotPaused() {
        val c = card(""""auto_pay":false,"server_copy":false,"pays_automatically":false,"ask_reason":"not_auto"""")
        assertFalse(c.autoNow)
        assertEquals(WalletCard.REASON_NOT_AUTO, c.reason)
        val info = json.decodeFromString<WalletInfo>("""{"paused":false,"auto_cards":0,"cards":[]}""")
        assertEquals(0, info.autoCount)                     // the list never says "on" while no card pays on its own
    }

    @Test fun pausedAndLockedAreTheReasonsTheServerGives() {
        val c = card(""""auto_pay":true,"server_copy":true,"pays_automatically":false,"ask_reason":"paused"""")
        assertFalse(c.autoNow)
        assertEquals(WalletCard.REASON_PAUSED, c.reason)
        assertTrue(card(""""auto_pay":true,"server_copy":true,"pays_automatically":true,"ask_reason":""""").autoNow)
    }

    @Test fun anOlderServerWithoutTheFieldsFallsBackToTheSwitchAndThePause() {
        val on = card(""""auto_pay":true,"server_copy":true""")
        val off = card(""""auto_pay":false""")
        assertTrue(on.autoNow)
        assertEquals("", on.reason)
        assertEquals(WalletCard.REASON_NOT_AUTO, off.reason)
        assertEquals(1, WalletInfo(paused = false, cards = listOf(on, off)).autoCount)
        assertEquals(0, WalletInfo(paused = true, cards = listOf(on, off)).autoCount)
    }

    @Test fun onlyACardSavedToAskEachTimeOffersTurningAutoPayOnInItsRow() {
        assertTrue(card(""""auto_pay":false,"server_copy":false,"pays_automatically":false,"ask_reason":"not_auto"""").offersTurnOn)
        assertFalse(card(""""auto_pay":true,"server_copy":true,"pays_automatically":true""").offersTurnOn)
        assertFalse(card(""""auto_pay":true,"server_copy":true,"pays_automatically":false,"ask_reason":"paused"""").offersTurnOn)
        assertFalse(json.decodeFromString<WalletCard>("""{"id":"c","auto_pay":false,"usable":true,"expired":true}""").offersTurnOn)
    }

    private fun named(id: String, default: Boolean = false, usable: Boolean = true) = json.decodeFromString<WalletCard>(
        """{"id":"$id","label":"$id","brand":"visa","last4":"4242","usable":$usable,"expired":false,"default":$default}""",
    )

    @Test fun theDefaultCardAndWhatDeletingItAsks() {
        val a = named("a", default = true)
        val b = named("b", usable = false)
        val c = named("c")
        assertEquals("a", DefaultCardRules.default(listOf(b, a, c))?.id)
        assertEquals(null, DefaultCardRules.choicesOnDelete(c, listOf(a, b, c)))            // not the default: just delete
        assertEquals(null, DefaultCardRules.choicesOnDelete(a, listOf(a, c)))               // one left: the server makes it the default
        assertEquals(listOf("b", "c"), DefaultCardRules.choicesOnDelete(a, listOf(a, b, c))?.map { it.id })
        assertEquals("c", DefaultCardRules.suggested(listOf(b, c))?.id)                      // a card that can pay is offered first
        assertFalse(json.decodeFromString<WalletCard>("""{"id":"x"}""").isDefault)          // an older server: no default
    }

    @Test fun theNicknameIsTheTitleWithBrandAndLast4Under() {
        assertEquals("Visa ••4242", CardMask.subtitle("Everyday card", "Visa", "4242"))
        assertEquals(null, CardMask.subtitle("Visa ••4242", "Visa", "4242"))                 // saved without a nickname
    }

    @Test fun aCardsHistoryPagesNewestFirstAndLinksToItsChatOrTask() {
        fun pay(id: Long, source: String = "", task: Long? = null) =
            WalletPayment(id = id, source = source, taskId = task)
        val first = WalletPayments(listOf(pay(9), pay(8)), nextBeforeId = 8)
        val shown = CardHistoryRules.merge(emptyList(), first)
        val more = CardHistoryRules.merge(shown, WalletPayments(listOf(pay(8), pay(7))))
        assertEquals(listOf(9L, 8L, 7L), more.map { it.id })
        assertEquals(PaymentLink.Chat, CardHistoryRules.link(pay(1, "chat", 4)))
        assertEquals(PaymentLink.Task(5), CardHistoryRules.link(pay(1, "schedule:3", 5)))
        assertEquals(PaymentLink.None, CardHistoryRules.link(pay(1)))
        val parsed = json.decodeFromString<WalletPayments>("""{"items":[{"id":3,"task_id":7,"source":"task:ab"}],"next_before_id":null}""")
        assertEquals(PaymentLink.Task(7), CardHistoryRules.link(parsed.items.single()))
    }

    @Test fun anExpiredOrUnfinishedCardNeverCountsAsPayingOnItsOwn() {
        assertFalse(json.decodeFromString<WalletCard>("""{"id":"c","auto_pay":true,"pays_automatically":true,"usable":true,"expired":true}""").autoNow)
        assertFalse(json.decodeFromString<WalletCard>("""{"id":"c","auto_pay":true,"pays_automatically":true,"usable":false}""").autoNow)
    }

    @Test fun scheduleGrantsComeWithTheWalletAndTheLogin() {
        val info = json.decodeFromString<WalletInfo>(
            """{"paused":false,"cards":[],"auto_cards":0,"schedule_grants":[{"id":3,"schedule_id":4,"schedule_name":"Weekly order",
               "kind":"purchase","site":"example.com","what":"water","max_amount":20000,"currency":"KRW","monthly_cap":80000,
               "runs_per_month":4,"max_amount_text":"20,000원","monthly_cap_text":"80,000원","spent_this_month":20000}]}""",
        )
        val g = info.scheduleGrants.single()
        assertTrue(g.isPurchase && !g.isLogin)
        assertEquals(4L, g.scheduleId)
        assertEquals("80,000원", g.monthlyCapText)
        val vault = json.decodeFromString<VaultInfo>("""{"items":[],"pending":[]}""")
        assertTrue(vault.items.isEmpty())
        val entry = json.decodeFromString<VaultEntry>(
            """{"name":"login-shop","kind":"login","schedule_grants":[{"id":5,"schedule_id":4,"kind":"login","site":"example.com",
               "login":"login-shop","login_label":"Shop"}]}""",
        )
        assertEquals(listOf(ScheduleGrant.KIND_LOGIN), entry.scheduleGrants.map { it.kind })
    }
}
