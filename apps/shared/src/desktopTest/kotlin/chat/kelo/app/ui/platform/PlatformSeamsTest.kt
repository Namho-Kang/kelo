package chat.kelo.app.ui.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import chat.kelo.app.ui.components.ChoiceChip
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SubPage
import chat.kelo.app.ui.text.LocalTextLocale
import chat.kelo.app.ui.theme.KeloTheme
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The seams the app roots fill (form factor, haptics, page frame, back handler, text locale) and what the shared components do with them. */
@OptIn(ExperimentalTestApi::class)
class PlatformSeamsTest {
    @Composable
    private fun Themed(form: FormFactor, content: @Composable () -> Unit) =
        CompositionLocalProvider(LocalFormFactor provides form) { KeloTheme(dark = false, content = content) }

    @Test fun theMainButtonFillsAPhoneColumnButOnlyAsWideAsItsTextOnTheDesktop() = runComposeUiTest {
        var form by mutableStateOf(FormFactor.Phone)
        setContent { Themed(form) { Box(Modifier.width(600.dp)) { PrimaryButton("Save", onClick = {}) } } }
        val phone = onNode(hasClickAction()).fetchSemanticsNode().size.width
        form = FormFactor.Desktop
        waitForIdle()
        val desktop = onNode(hasClickAction()).fetchSemanticsNode().size.width
        assertTrue(phone > desktop, "phone $phone, desktop $desktop")
    }

    @Test fun aChipTickGoesToTheHapticsOfTheRoot() = runComposeUiTest {
        val seen = mutableListOf<Haptic>()
        val haptics = object : Haptics { override fun perform(kind: Haptic) { seen += kind } }
        setContent { CompositionLocalProvider(LocalHaptics provides haptics) { Themed(FormFactor.Phone) { ChoiceChip("Mail", selected = false, onClick = {}) } } }
        onNodeWithText("Mail").performClick()
        assertEquals(listOf(Haptic.TICK), seen)
        // without a root's haptics (the desktop, a test) nothing happens and nothing fails
        Haptics.None.confirm(); Haptics.None.reject(); Haptics.None.longPress(); Haptics.None.tick()
    }

    @Test fun theThemeSetsTheLanguageOfTheTextFromTheRootsLocale() = runComposeUiTest {
        var tag: String? = null
        setContent {
            CompositionLocalProvider(LocalTextLocale provides Locale.forLanguageTag("ko")) {
                KeloTheme(dark = false) { tag = MaterialTheme.typography.bodyLarge.localeList?.get(0)?.language }
            }
        }
        assertEquals("ko", tag)
    }

    @Test fun aSubPageAsksTheRootsBackHandlerAndSitsBelowTheDesktopHeader() = runComposeUiTest {
        var backs = 0
        var handlerSeen: (() -> Unit)? = null
        setContent {
            CompositionLocalProvider(
                LocalBackHandler provides { onBack -> handlerSeen = onBack },
                LocalPageFrame provides PageFrame(topInset = 80.dp, maxWidth = 400.dp),
            ) { Themed(FormFactor.Desktop) { SubPage("Title", onBack = { backs++ }) { androidx.compose.material3.Text("Body") } } }
        }
        handlerSeen!!.invoke()
        assertEquals(1, backs)
        val top = onNode(hasText("Title")).fetchSemanticsNode().boundsInRoot.top
        assertTrue(top >= 80f, "the page starts below the header: $top")
    }

    @Test fun aShortQuestionIsASheetOnThePhoneAndADialogOnTheDesktopWithTheSameContent() = runComposeUiTest {
        var form by mutableStateOf(FormFactor.Phone)
        var closed = 0
        var agreed = 0
        setContent {
            Themed(form) {
                chat.kelo.app.ui.components.KeloModal(onDismiss = { closed++ }) {
                    PrimaryButton("Agree", onClick = { agreed++ })
                }
            }
        }
        onNodeWithText("Agree").performClick()
        assertEquals(1, agreed)
        form = FormFactor.Desktop
        waitForIdle()
        onNodeWithText("Agree").performClick()
        assertEquals(2, agreed)
        assertEquals(0, closed)
    }

    @Test fun theShareActionIsOnlyOfferedWhereTheRootGivesAWayToShare() {
        val all = listOf(chat.kelo.app.chat.MsgAction.COPY, chat.kelo.app.chat.MsgAction.SHARE, chat.kelo.app.chat.MsgAction.REPORT)
        assertEquals(all, chat.kelo.app.chat.MessageActions.offered(all, canShare = true))
        assertEquals(listOf(chat.kelo.app.chat.MsgAction.COPY, chat.kelo.app.chat.MsgAction.REPORT), chat.kelo.app.chat.MessageActions.offered(all, canShare = false))
        // no root gives one by default; a root that does hands the text to it
        var shared: String? = null
        runComposeUiTest {
            var seen: TextSharer? = TextSharer { }
            setContent {
                seen = LocalTextSharer.current
                CompositionLocalProvider(LocalTextSharer provides TextSharer { shared = it }) { LocalTextSharer.current!!.share("hello") }
            }
            assertEquals(null, seen)
        }
        assertEquals("hello", shared)
    }
}
