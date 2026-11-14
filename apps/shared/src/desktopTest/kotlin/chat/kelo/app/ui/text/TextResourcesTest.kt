package chat.kelo.app.ui.text

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import chat.kelo.app.res.Res
import chat.kelo.app.res.my_agent_tone
import chat.kelo.app.res.my_agent_tone_failed
import chat.kelo.app.res.privacy_titles
import chat.kelo.app.res.work_badge_waiting
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals

/** Step 0 of P3: what Compose resources do on the desktop JVM with the converted Android strings. */
@OptIn(ExperimentalTestApi::class, org.jetbrains.compose.resources.InternalResourceApi::class, org.jetbrains.compose.resources.ExperimentalResourceApi::class)
class TextResourcesTest {
    private val saved = Locale.getDefault()
    @Before fun before() { Locale.setDefault(Locale.ENGLISH) }
    @After fun after() { Locale.setDefault(saved) }

    @Test fun koreanDefaultGivesKorean() {
        Locale.setDefault(Locale.KOREAN)
        assertEquals("말투", runBlocking { loadString(Res.string.my_agent_tone) })
        Locale.setDefault(Locale.ENGLISH)
        assertEquals("Tone", runBlocking { loadString(Res.string.my_agent_tone) })
    }

    @Test fun apostropheAndFormat() {
        assertEquals("Couldn't change the tone. Try again.", runBlocking { loadString(Res.string.my_agent_tone_failed) })
        assertEquals("1 item waiting for you", runBlocking { loadPlural(Res.plurals.work_badge_waiting, 1, 1) })
        assertEquals("5 items waiting for you", runBlocking { loadPlural(Res.plurals.work_badge_waiting, 5, 5) })
        Locale.setDefault(Locale.KOREAN)
        assertEquals("답을 기다리는 일 5개", runBlocking { loadPlural(Res.plurals.work_badge_waiting, 5, 5) })
    }

    @Test fun arrays() {
        assertEquals("Your agent can't see the values", runBlocking { loadArray(Res.array.privacy_titles) }[1])
    }

    @Test fun indonesianAndChinese() {
        val ids = listOf(Locale("id"), Locale.forLanguageTag("id"), Locale("in"), Locale.forLanguageTag("zh-CN"), Locale.forLanguageTag("zh-TW"),
            Locale.forLanguageTag("zh-Hans"), Locale.forLanguageTag("pt-BR"))
        for (l in ids) {
            Locale.setDefault(l)
            println("SPIKE ${l.toLanguageTag()} lang=${l.language} -> " + runBlocking { loadString(Res.string.my_agent_tone) })
        }
    }

    @Test fun switchingLanguageRereadsWithoutKey() = runComposeUiTest {
        var locale by mutableStateOf(Locale.ENGLISH)
        setContent {
            remember(locale) { Locale.setDefault(locale); 0 }
            CompositionLocalProvider(LocalTextLocale provides locale) {
                Column { Text(string(Res.string.my_agent_tone)); Text(plural(Res.plurals.work_badge_waiting, 2, 2)) }
            }
        }
        onNodeWithText("Tone").assertExists()
        onNodeWithText("2 items waiting for you").assertExists()
        locale = Locale.KOREAN
        waitForIdle()
        onNodeWithText("말투").assertExists()
        onNodeWithText("답을 기다리는 일 2개").assertExists()
    }
}
