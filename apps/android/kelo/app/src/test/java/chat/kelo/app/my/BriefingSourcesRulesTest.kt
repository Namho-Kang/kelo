package chat.kelo.app.my

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "What it reads" in the briefing settings: reading GET /api/briefing/sources, what each row says, and the bodies the switches send. */
class BriefingSourcesRulesTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    private val body = """{"calendar": {"accounts": [
          {"id": "phone", "kind": "calendar", "provider": "phone", "name": "Phone calendar", "address": "", "label": "Phone calendar", "on": true, "problem": null},
          {"id": "caldav:mail-naver", "kind": "calendar", "provider": "naver", "name": "Naver Calendar", "address": "me@naver.com", "label": "Naver", "on": true,
           "problem": "Could not read Naver Calendar: CalDAV is not turned on for this account, or the provider does not offer it."},
          {"id": "google:google-calendar", "kind": "calendar", "provider": "google", "name": "Google Calendar", "address": "me@gmail.com", "label": "Google", "on": false, "problem": null}]},
        "mail": {"accounts": [
          {"id": "mail:mail-naver", "kind": "mail", "provider": "naver", "name": "Naver Mail", "address": "me@naver.com", "label": "Naver", "on": true, "problem": null},
          {"id": "mail:mail-icloud", "kind": "mail", "provider": "icloud", "name": "iCloud Mail", "address": "me@icloud.com", "label": "iCloud", "on": false, "problem": null}]}}"""

    @Test fun readsTheListGroupedByCalendarsAndMail() {
        val s = json.decodeFromString<BriefingSources>(body)
        assertEquals(listOf("phone", "caldav:mail-naver", "google:google-calendar"), s.calendar.accounts.map { it.id })
        assertEquals(listOf(true, true, false), s.calendar.accounts.map { it.on })
        assertEquals(listOf("calendar", "mail"), BriefingSourcesRules.shown(s))
    }

    @Test fun anOlderServerOrNothingConnectedShowsNothing() {
        val none = json.decodeFromString<BriefingSources>("{}")
        assertEquals(emptyList<String>(), BriefingSourcesRules.shown(none))
        val mailOnly = json.decodeFromString<BriefingSources>("""{"mail": {"accounts": [{"id": "mail:mail-naver", "kind": "mail", "name": "Naver Mail"}]}}""")
        assertEquals(listOf("mail"), BriefingSourcesRules.shown(mailOnly))
        assertTrue(mailOnly.mail.accounts.single().on)            // a row without "on" counts as read: everything is read by default
        assertNull(mailOnly.mail.accounts.single().problem)
    }

    @Test fun eachRowNamesTheAccountThenTheProvider() {
        val s = json.decodeFromString<BriefingSources>(body)
        val phone = s.calendar.accounts[0]
        assertEquals("Phone calendar", BriefingSourcesRules.title(phone))      // no address: the provider is the title
        assertEquals("", BriefingSourcesRules.subtitle(phone))
        val naver = s.calendar.accounts[1]
        assertEquals("me@naver.com", BriefingSourcesRules.title(naver))
        assertEquals("Naver Calendar", BriefingSourcesRules.subtitle(naver))
    }

    @Test fun anOutlookMailboxAndANestAccountReadCleanly() {
        val s = json.decodeFromString<BriefingSources>("""{"mail": {"accounts": [
          {"id": "mail:outlook", "kind": "mail", "provider": "outlook", "name": "Outlook Mail", "address": "me@outlook.com", "label": "Outlook", "on": true, "problem": null},
          {"id": "mail:nest-acme", "kind": "mail", "provider": "nest:acme", "name": "Acme Mail Package", "address": "", "label": "Acme", "on": true, "problem": null}]},
          "calendar": {"accounts": [{"id": "nest:acme", "kind": "calendar", "provider": "nest:acme", "name": "Acme Calendar", "address": "", "on": true}]}}""")
        val (outlook, nest) = s.mail.accounts
        assertEquals("me@outlook.com", BriefingSourcesRules.title(outlook))
        assertEquals("Outlook Mail", BriefingSourcesRules.subtitle(outlook))
        assertEquals("Acme Mail Package", BriefingSourcesRules.title(nest))     // an unknown provider and no address: the name only
        assertEquals("", BriefingSourcesRules.subtitle(nest))
        assertEquals("Acme Calendar", BriefingSourcesRules.title(s.calendar.accounts.single()))
    }

    @Test fun aSwitchSendsTheOneAccount() {
        val on = BriefingSourcesRules.toggleBody("mail", "mail:mail-icloud", true)
        assertEquals("true", on["mail"]!!.jsonObject["accounts"]!!.jsonObject["mail:mail-icloud"]!!.jsonPrimitive.content)
        assertFalse(on.containsKey("calendar"))
        // an older server's "mode" field is ignored: each account is just on or off
        val old = json.decodeFromString<BriefingSources>("""{"mail": {"mode": "only", "accounts": [{"id": "mail:a", "on": false}]}}""")
        assertEquals(listOf(false), old.mail.accounts.map { it.on })
    }

    @Test fun aSwitchShowsAtOnceBeforeTheServerAnswers() {
        val s = json.decodeFromString<BriefingSources>(body)
        val flipped = BriefingSourcesRules.flipped(s, "calendar", "google:google-calendar", true)
        assertEquals(listOf(true, true, true), flipped.calendar.accounts.map { it.on })
        assertEquals(s.mail, flipped.mail)
        val mail = BriefingSourcesRules.flipped(s, "mail", "mail:mail-naver", false)
        assertEquals(listOf(false, false), mail.mail.accounts.map { it.on })
        assertEquals(s.calendar, mail.calendar)
    }
}
