package chat.kelo.app.chat

import chat.kelo.app.res.*
import chat.kelo.app.agent.ServerNotification
import chat.kelo.app.notify.NoteTypes
import chat.kelo.app.notify.NotifyRules
import chat.kelo.app.notify.OpenTarget
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The morning briefing's card: reading it from GET /api/messages, where each item opens, its sections and its notification. */
class BriefingCardTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    private val full = """[{"id": 41, "role": "assistant", "content": "Morning briefing — Rain after 3", "created_at": 1794520800.0,
        "briefing": {"day": "2026-11-09", "summary": "Rain this afternoon · 2 events · 1 waiting on you", "line": "Rain after 3, take an umbrella.",
          "quiet": false, "place": {"name": "서울특별시", "source": "home"},
          "weather": {"high": 11, "low": 4, "unit": "C", "sky": "partly", "headline": "Partly cloudy · High 11° · Low 4°",
            "notes": [{"kind": "rain_start", "text": "Rain from 15:00", "at": 1794549600.0}], "provider": "open_meteo",
            "attribution": "Weather data by Open-Meteo.com", "attribution_url": "https://open-meteo.com/", "fallback": false},
          "events": [{"title": "Design review", "start": 1794531600.0, "where": "Gangnam station", "open": {"kind": "event", "start": 1794531600.0}}],
          "missed": [{"kind": "followup", "title": "Which hotel?", "detail": "", "open": {"kind": "followup", "id": 4, "message_id": 77}},
                     {"kind": "job", "title": "Price watch", "detail": "", "open": {"kind": "job", "worker_id": "w1", "task_id": 9}},
                     {"kind": "goal", "title": "Visa form", "detail": "Tomorrow", "open": {"kind": "goal", "id": 3}},
                     {"kind": "mail", "title": "Contract to sign", "detail": "Kim", "open": {"kind": "mail", "account": "me@x", "uid": "11", "subject": "Contract to sign"}},
                     {"kind": "promise", "title": "call the landlord back", "detail": "Today", "open": null}],
          "hours": {"id": "w1", "day_type": "weekend", "wake_start": "10:00", "wake_end": "23:30", "current_start": "07:00", "current_end": "22:00"}}},
        {"id": 42, "role": "user", "content": "thanks", "created_at": 1794520900.0}]"""

    @Test fun readsTheCardOnItsMessageAndPlainMessagesHaveNone() {
        val ms = json.decodeFromString<List<StoredMessage>>(full)
        val card = ms[0].briefing!!
        assertEquals("Rain this afternoon · 2 events · 1 waiting on you", card.summary)
        assertEquals(11, card.weather?.high)
        assertEquals("Rain from 15:00", card.weather?.notes?.single()?.text)
        assertEquals("https://open-meteo.com/", card.weather?.attributionUrl)
        assertEquals(listOf("headline", "weather", "today", "missed", "hours"), BriefingRules.sections(card))
        assertEquals("weekend", card.hours?.dayType)
        assertTrue(BriefingRules.hoursShown(card.hours))
        assertNull(ms[1].briefing)
        // a quiet day: only the weather
        val quiet = json.decodeFromString<BriefingCard>("""{"day": "2026-11-10", "summary": "Clear · High 9° · Low 1°", "quiet": true,
            "weather": {"headline": "Clear · High 9° · Low 1°", "notes": []}, "events": [], "missed": [], "hours": null, "line": null}""")
        assertEquals(listOf("weather"), BriefingRules.sections(quiet))   // null line, null hours
        assertFalse(BriefingRules.hoursShown(quiet.hours))
        assertFalse(BriefingRules.hoursShown(BriefingHours(id = "x", wakeStart = "bad")))
    }

    @Test fun bigEventsThisWeekMakeOneComingUpLine() {
        val card = json.decodeFromString<BriefingCard>("""{"day": "2026-11-09", "events": [], "missed": [],
            "ahead": [{"title": "상하이 여행", "start": 1794700000.0, "kind": "trip", "first": "2026-11-12", "last": "2026-11-14"},
                      {"title": "Flight KE893", "start": 1794900000.0, "kind": "flight", "first": "2026-11-15", "last": "2026-11-15"}]}""")
        assertEquals(listOf("ahead"), BriefingRules.sections(card))
        val day: (java.time.LocalDate) -> String = { "${it.monthValue}/${it.dayOfMonth}" }
        assertEquals("상하이 여행 11/12 – 11/14 · Flight KE893 11/15", BriefingRules.aheadLine(card, day))
        // an older card has no line
        assertEquals(emptyList<BriefingAhead>(), json.decodeFromString<BriefingCard>("""{"day": "2026-11-09"}""").ahead)
    }

    @Test fun eachEventAndMailNamesItsAccountOnlyWhenSeveralAreRead() {
        val card = json.decodeFromString<BriefingCard>("""{"day": "2026-11-09", "summary": "x",
            "events": [{"title": "Dentist", "start": 1794531600.0, "where": "", "source": "Phone calendar"},
                       {"title": "Lunch", "start": 1794538800.0, "where": "Gangnam station", "source": "Naver"},
                       {"title": "Board", "start": 1794546000.0, "where": "", "source": "me@work.example"}],
            "missed": [{"kind": "mail", "title": "Contract to sign", "detail": "Kim", "source": "iCloud"},
                       {"kind": "followup", "title": "Which hotel?", "detail": ""}]}""")
        assertEquals(listOf("Phone calendar", "Naver", "me@work.example"), card.events.map { it.source })
        assertEquals(listOf("iCloud", ""), card.missed.map { it.source })
        // one account read: the server sends no source, and an older server never did
        val single = json.decodeFromString<BriefingCard>("""{"events": [{"title": "Dentist", "start": 1794531600.0}], "missed": [{"kind": "mail", "title": "A"}]}""")
        assertEquals("", single.events.single().source)
        assertEquals("", single.missed.single().source)
    }

    @Test fun eachItemOpensItsPlace() {
        val card = json.decodeFromString<List<StoredMessage>>(full)[0].briefing!!
        val taps = card.missed.map { BriefingRules.tap(it.open) }
        assertEquals(listOf(BriefingTap.ChatAt(77), BriefingTap.Task(9), BriefingTap.Goals, BriefingTap.Mail("Contract to sign"), null), taps)
        assertEquals(BriefingTap.Event(1794531600000L), BriefingRules.tap(card.events[0].open))
        assertEquals(BriefingTap.Waiting, BriefingRules.tap(BriefingOpen(kind = "followup", id = 5)))   // no message of its own
        assertEquals(BriefingTap.Waiting, BriefingRules.tap(BriefingOpen(kind = "job", workerId = "w2")))
        assertNull(BriefingRules.tap(BriefingOpen(kind = "mail", subject = "")))
        assertNull(BriefingRules.tap(BriefingOpen(kind = "something-new")))
        assertEquals(Res.string.briefing_kind_promise, BriefingRules.kindLabel("promise"))
        assertEquals(Res.string.briefing_kind_followup, BriefingRules.kindLabel("unknown"))
    }

    @Test fun itsNotificationOpensTheChat() {
        val n = ServerNotification(id = 5, type = NoteTypes.BRIEFING, title = "아침 브리핑", body = "오후 비 · 일정 2개")
        assertEquals(OpenTarget.Chat, NotifyRules.target(n))
        assertTrue(NoteTypes.BRIEFING in NoteTypes.ALL)
        assertEquals(NotifyRules.channel(NoteTypes.PROACTIVE, null), NotifyRules.channel(NoteTypes.BRIEFING, null))
    }

    @Test fun aCardOnScreenTakesItsNewStateWhenTheBriefingChangesElsewhere() {
        val hours = BriefingHours(id = "h1", dayType = "weekend", wakeStart = "10:00", wakeEnd = "01:00")
        val card = BriefingCard(day = "2026-11-09", hours = hours)
        val items = listOf(
            ChatItem.Msg("k1", mine = true, text = "hi", id = 40),
            ChatItem.Msg("k2", mine = false, text = "Morning briefing", id = 41, briefing = card),
            ChatItem.Msg("k3", mine = false, text = "later", id = 42),
        )
        // the hours line was answered on another device: GET /api/messages now sends the card without it
        val after = ChatReducer.withBriefings(items, mapOf(41L to card.copy(hours = null), 42L to null))
        assertNull((after[1] as ChatItem.Msg).briefing?.hours)
        assertEquals(items.map { it.key }, after.map { it.key })                 // nothing moves
        assertEquals(items[0], after[0]); assertEquals(items[2], after[2])        // other rows untouched
        // the card's message is not in the page asked for: it stays as it is
        assertEquals(items, ChatReducer.withBriefings(items, mapOf(40L to null)))
        val rev = json.decodeFromString<AgentStatus>("""{"busy": false, "briefing_rev": "|h1"}""")
        assertEquals("|h1", rev.briefingRev)
        assertNull(json.decodeFromString<AgentStatus>("""{"busy": false}""").briefingRev)   // an older server
    }

    private val smart = """{"day": "2026-11-10", "summary": "x", "line": "Rain after 3, and the design review is at 10.",
        "headline": "Rain after 3, and the design review is at 10.",
        "connections": [{"text": "Rain at 15:00 while the review is at the far end of town", "refs": ["w", "e1", "zz"]},
                        {"text": "Kim's contract and the visa form are both due tomorrow", "refs": ["m1", "m2", "m1"]},
                        {"text": "  ", "refs": ["w"]}],
        "suggestions": [{"text": "Shall I draft a reply to Kim?", "refs": ["m1"]}, {"text": "Move the visa form reminder to tonight?", "refs": []}, {"text": "", "refs": []}],
        "future_key": {"x": 1},
        "weather": {"id": "w", "headline": "Rain"},
        "events": [{"id": "e1", "title": "Design review", "start": 1794531600.0, "open": {"kind": "event", "start": 1794531600.0}}],
        "ahead": [{"id": "a1", "title": "상하이 여행", "first": "2026-11-12", "last": "2026-11-14"}],
        "missed": [{"id": "m1", "kind": "mail", "title": "Contract to sign", "open": {"kind": "mail", "subject": "Contract to sign"}},
                   {"id": "m2", "kind": "promise", "title": "Visa form", "open": null}]}"""

    @Test fun anOldCardWithoutTheNewFieldsStillReadsAndShowsItsLineAsTheHeadline() {
        val old = json.decodeFromString<BriefingCard>("""{"day": "2026-11-09", "line": "Rain after 3.", "events": [{"title": "A", "start": 1.0}]}""")
        assertNull(old.headline); assertEquals(emptyList<BriefingLink>(), old.connections); assertEquals(emptyList<BriefingLink>(), old.suggestions)
        assertEquals("", old.events.single().id)
        assertEquals("Rain after 3.", BriefingRules.headline(old))
        assertEquals(listOf("headline", "today"), BriefingRules.sections(old))
        // nulls everywhere, and an empty headline
        val nulls = json.decodeFromString<BriefingCard>("""{"day": "d", "line": null, "headline": null, "connections": null, "suggestions": null,
            "weather": {"id": null}, "events": [{"title": "A", "start": 1.0, "id": null}]}""")
        assertNull(BriefingRules.headline(nulls)); assertEquals(listOf("weather", "today"), BriefingRules.sections(nulls))
        assertNull(BriefingRules.headline(BriefingCard(headline = "  ", line = "")))
    }

    @Test fun theHeadlineIsOneTextAndTheNotesAndStepsDropEmptyTexts() {
        val card = json.decodeFromString<BriefingCard>(smart)
        assertEquals("Rain after 3, and the design review is at 10.", BriefingRules.headline(card))
        assertEquals("From the model", BriefingRules.headline(card.copy(headline = " From the model ", line = "old")))
        assertEquals(2, BriefingRules.connections(card).size)
        assertEquals(listOf("Shall I draft a reply to Kim?", "Move the visa form reminder to tonight?"), BriefingRules.suggestions(card).map { it.text })
        assertEquals(emptyList<String>(), BriefingRules.suggestions(card)[1].refs)
        assertEquals(listOf("headline", "notes", "weather", "today", "ahead", "missed", "steps"), BriefingRules.sections(card))
        val withHours = card.copy(hours = BriefingHours(id = "h", wakeStart = "10:00"))
        assertEquals("hours", BriefingRules.sections(withHours).last())
        assertEquals("steps", BriefingRules.sections(withHours).let { it[it.size - 2] })
    }

    @Test fun chipsNameTheItemsTheirNoteIsAboutAndDoWhatATapOnThemDoes() {
        val card = json.decodeFromString<BriefingCard>(smart)
        val clock: (Double) -> String = { "10:00" }
        val first = BriefingRules.chips(card, card.connections[0].refs, "Weather", clock)
        // "zz" points at nothing on the card: skipped
        assertEquals(listOf("Weather", "10:00 Design review"), first.map { it.label })
        assertNull(first[0].tap)                                                    // the weather has no place of its own
        assertEquals(BriefingTap.Event(1794531600000L), first[1].tap)
        val second = BriefingRules.chips(card, card.connections[1].refs, "Weather", clock)
        assertEquals(listOf("Contract to sign", "Visa form"), second.map { it.label })     // "m1" twice: once
        assertEquals(BriefingTap.Mail("Contract to sign"), second[0].tap)
        assertNull(second[1].tap)                                                   // a promise: a chip you cannot press
        assertEquals(listOf("상하이 여행"), BriefingRules.chips(card, listOf("a1"), "Weather", clock).map { it.label })
        assertEquals(emptyList<BriefingChip>(), BriefingRules.chips(card, listOf("", " ", "e9"), "Weather", clock))
        // a card from before ids: nothing matches, even an empty ref
        val old = BriefingCard(weather = BriefingWeather(), events = listOf(BriefingEvent(title = "A", start = 1.0)))
        assertEquals(emptyList<BriefingChip>(), BriefingRules.chips(old, listOf("w", "e1", ""), "Weather", clock))
    }

    @Test fun aLongSpacelessLabelIsKeptWhole() {
        val long = "Donaudampfschifffahrtsgesellschaftskapitänsbesprechung".repeat(3)
        val card = BriefingCard(missed = listOf(BriefingItem(kind = "followup", title = long, id = "m1")))
        assertEquals(long, BriefingRules.chips(card, listOf("m1"), "Weather") { "" }.single().label)
        assertEquals(BriefingTap.Suggest(long), BriefingTap.Suggest(long))
    }
}
