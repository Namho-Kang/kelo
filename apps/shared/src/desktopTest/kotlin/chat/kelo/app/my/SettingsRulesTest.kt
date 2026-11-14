package chat.kelo.app.my

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** 마이's small rules: the usage row's number and the notification settings. */
class SettingsRulesTest {
    private fun w(p: Int) = UsageWindow(label = "x", usedPercent = p)

    @Test fun theUsageRowShowsTheGroupInUse() {
        val groups = listOf(UsageGroup("a", "A", active = false, windows = listOf(w(90))), UsageGroup("b", "B", active = true, windows = listOf(w(20), w(42))))
        assertEquals(42, UsageLogic.summaryPercent(groups))
        assertEquals(90, UsageLogic.summaryPercent(groups.map { it.copy(active = false) }))
        assertNull(UsageLogic.summaryPercent(emptyList()))
        assertEquals(100, UsageLogic.summaryPercent(listOf(UsageGroup(null, "x", true, listOf(w(130))))))
    }

    @Test fun wakingHours() {
        assertEquals(7 to 30, NotifySettingsRules.parseTime("07:30"))
        assertEquals(22 to 0, NotifySettingsRules.parseTime(" 22:00 "))
        assertNull(NotifySettingsRules.parseTime("24:00"))
        assertNull(NotifySettingsRules.parseTime("7시"))
        assertEquals("07:05", NotifySettingsRules.formatTime(7, 5))
    }

    @Test fun dailyCapsStayInRangeAndTheTotalCoversTheRegular() {
        // the page's one "at most N a day": the total, with the regular and urgent caps never above it
        assertEquals(Triple(2, 3, 3), NotifySettingsRules.perDay(3, 2, 3))
        assertEquals(Triple(1, 1, 1), NotifySettingsRules.perDay(0, 2, 3))
        assertEquals(Triple(2, 20, 3), NotifySettingsRules.perDay(99, 2, 3))
        assertEquals(listOf("우산", "택배 확인"), NotifySettingsRules.added(listOf("우산"), "  택배\n 확인 "))
        assertEquals(listOf("우산"), NotifySettingsRules.added(listOf("우산"), " 우산 "))
        assertEquals(listOf("우산"), NotifySettingsRules.added(listOf("우산"), "   "))
    }

    @Test fun theNewsAlertsSayHowOftenTheyLookFromTheServersSetting() {
        assertEquals(true to 2, NotifySettingsRules.every(120))        // the default: "2시간마다"
        assertEquals(true to 1, NotifySettingsRules.every(60))
        assertEquals(true to 24, NotifySettingsRules.every(1440))
        assertEquals(false to 90, NotifySettingsRules.every(90))       // not whole hours: in minutes
        assertEquals(false to 30, NotifySettingsRules.every(30))
    }

    @Test fun proactiveSettingsMailCheck() {
        val json = Json { ignoreUnknownKeys = true }
        val without = json.decodeFromString<ProactiveSettings>("""{"enabled": true}""")
        assertTrue(without.mailCheck)
        val withFalse = json.decodeFromString<ProactiveSettings>("""{"mail_check": false}""")
        assertFalse(withFalse.mailCheck)
    }
}

/** Reset times read from a CLI's words and said in the phone's time; long tokens never split mid-word. */
class ResetAndTokenTest {
    private val utc = java.time.ZoneOffset.UTC
    // 2026-11-07 08:00 UTC
    private val now = java.time.LocalDateTime.of(2026, 11, 7, 8, 0).toInstant(utc).toEpochMilli()
    private fun utcMillis(m: Int, d: Int, h: Int, mi: Int, y: Int = 2026) = java.time.LocalDateTime.of(y, m, d, h, mi).toInstant(utc).toEpochMilli()

    @Test fun readsTheCliWords() {
        assertEquals(utcMillis(11, 7, 11, 40), UsageLogic.parseResetText("Nov 7, 11:40am (UTC)", now))
        assertEquals(utcMillis(11, 5, 21, 0), UsageLogic.parseResetText("Nov 6 at 6am (Asia/Seoul)", now))
        assertEquals(utcMillis(11, 7, 23, 30), UsageLogic.parseResetText("11:30pm (UTC)", now))
        assertEquals(utcMillis(11, 8, 7, 0), UsageLogic.parseResetText("7am (UTC)", now))           // passed today: tomorrow
        assertEquals(utcMillis(1, 3, 9, 0, 2027), UsageLogic.parseResetText("Jan 3 at 9:00 AM (UTC)", now)) // next year
        assertEquals(now + 3 * 3600_000, UsageLogic.parseResetText("in 3 hours", now))
        assertEquals(utcMillis(11, 7, 14, 0), UsageLogic.parseResetText("Nov 7, 14:00 (Mars/Base)", now))  // unknown zone: UTC
        assertNull(UsageLogic.parseResetText("soon-ish", now))
    }

    @Test fun nearIsRelativeFurtherIsADate() {
        fun ph(text: String) = UsageLogic.phrase(UsageLogic.reset(UsageWindow(resetsText = text), now, utc)!!, now)
        assertEquals(UsageLogic.Phrase.InHours(4), ph("Nov 7, 11:40am (UTC)"))
        assertEquals(UsageLogic.Phrase.InMinutes(30), ph("in 30 minutes"))
        assertEquals(UsageLogic.Phrase.On(utcMillis(11, 9, 9, 0)), ph("Nov 9 at 9am (UTC)"))
        assertEquals(UsageLogic.Phrase.Raw("whenever"), ph("whenever"))
        val iso = UsageLogic.phrase(UsageLogic.reset(UsageWindow(resetsAt = "2026-11-07T10:00:00Z", resetsText = "ignored"), now, utc)!!, now)
        assertEquals(UsageLogic.Phrase.InHours(2), iso)
    }

    @Test fun longTokensAreCutInTheMiddle() {
        val id = "claude-subscription-directsdk-experimental"
        val cut = chat.kelo.app.ui.components.TokenText.middle(id, 20)
        assertEquals(20, cut.length)
        assertEquals("claude-sub…erimental", cut)
        assertEquals("short", chat.kelo.app.ui.components.TokenText.fit("short"))
        assertEquals("메일 · " + chat.kelo.app.ui.components.TokenText.middle("a-very-long-file-name-without-any-spaces.pdf"),
            chat.kelo.app.ui.components.TokenText.fit("메일 · a-very-long-file-name-without-any-spaces.pdf"))
        assertTrue(chat.kelo.app.ui.components.TokenText.isToken(id))
        assertFalse(chat.kelo.app.ui.components.TokenText.isToken("Claude 구독"))
    }

    @Test fun providersNeverShowTheirRawId() {
        val list = listOf(chat.kelo.app.chat.ProviderInfo("claude-subscription-directsdk-experimental", "Claude subscription"),
            chat.kelo.app.chat.ProviderInfo("zai", "zai"))
        assertEquals("Claude subscription", chat.kelo.app.chat.ProviderLabels.displayName("claude-subscription-directsdk-experimental", list))
        assertNull(chat.kelo.app.chat.ProviderLabels.displayName("zai", list))      // only the id again: not a name
        assertNull(chat.kelo.app.chat.ProviderLabels.displayName("unknown", list))
        assertEquals(chat.kelo.app.chat.ConsentService.CLAUDE_SUBSCRIPTION, chat.kelo.app.chat.ProviderConsentRules.service("claude-subscription-directsdk-experimental"))
    }
}
