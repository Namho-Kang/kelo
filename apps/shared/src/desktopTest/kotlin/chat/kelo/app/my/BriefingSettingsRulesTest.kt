package chat.kelo.app.my

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The morning briefing's settings: reading GET /api/briefing (and an older server without it), the choices and the bodies sent. */
class BriefingSettingsRulesTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    @Test fun readsTheSettings() {
        val b = json.decodeFromString<BriefingSettings>("""{"enabled": true, "time_mode": "auto", "fixed_time": "07:30", "offset_min": 20,
            "early_open": true, "sections": {"weather": true, "calendar": false, "missed": true}, "quiet_days": "short", "next_at": 1794522000.0,
            "due_source": "learned", "last": null,
            "weather": {"provider": "auto",
              "here": {"name": "부산", "lat": 35.1796, "lon": 129.0756, "country_code": "KR", "source": "phone", "at": 1794520000.0, "accuracy_m": 30},
              "keys": {"kma": true, "weatherapi": false},
              "providers": [{"id": "open_meteo", "name": "Open-Meteo", "needs_key": false, "attribution": "Weather data by Open-Meteo.com",
                             "attribution_url": "https://open-meteo.com/", "max_days": 7}]}}""")
        assertEquals(20, b.offsetMin)
        assertFalse(BriefingSettingsRules.sectionOn(b, "calendar"))
        assertTrue(BriefingSettingsRules.sectionOn(b, "weather"))
        assertEquals("부산", b.weather.here!!.name)
        assertEquals(true, b.weather.keys["kma"])
        val defaults = json.decodeFromString<BriefingSettings>("{}")
        assertTrue(defaults.enabled)
        assertEquals("auto", defaults.weather.provider)
    }

    @Test fun choicesAndBodies() {
        assertEquals(listOf(0, 10, 20, 30, 45, 60), BriefingSettingsRules.offsets(0))
        assertEquals(listOf(0, 10, 15, 20, 30, 45, 60), BriefingSettingsRules.offsets(15))
        assertEquals(listOf(0, 10, 20, 30, 45, 60), BriefingSettingsRules.offsets(90))        // out of range: clamped into the list
        assertEquals("false", BriefingSettingsRules.sectionBody("missed", false)["sections"]!!.jsonObject["missed"]!!.jsonPrimitive.content)
        assertTrue(BriefingSettingsRules.keyOk("abcd1234efgh"))
        assertFalse(BriefingSettingsRules.keyOk("short"))
        assertFalse(BriefingSettingsRules.keyOk("has a space inside"))
    }

    @Test fun theLocationLineShowsOnlyWhileTheWeatherHasNoPlace() {
        val none = BriefingSettings()                                                   // weather on, no phone sent a location yet
        val known = none.copy(weather = BriefingWeatherSettings(here = BriefingPlaceInfo("서울", 37.5, 127.0, "KR")))
        assertTrue(BriefingSettingsRules.needsLocation(none, granted = false))
        assertTrue(BriefingSettingsRules.needsLocation(none, granted = true))           // allowed, nothing sent yet
        assertTrue(BriefingSettingsRules.needsLocation(known, granted = false))         // this phone is not allowed
        assertFalse(BriefingSettingsRules.needsLocation(known, granted = true))
        assertFalse(BriefingSettingsRules.needsLocation(none.copy(sections = mapOf("weather" to false)), granted = false))   // no weather wanted
    }
}
