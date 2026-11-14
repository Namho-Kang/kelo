package chat.kelo.app.chat

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import chat.kelo.app.ui.platform.headerHeight
import kotlinx.coroutines.delay
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import chat.kelo.app.ui.components.ChoiceChip
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.theme.Kelo

/**
 * The agent's questions with answers to tap. A single question sends its answer with one tap; with several, the user picks one
 * answer per question (any they skip are left out) and sends them together. An answer to write ("Other", "직접 입력할게요") opens a
 * text box under its question and the typed words are sent instead ([QuickReplyRules]). Typing in the chat still works as always.
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun QuickReplies(set: ReplySet, onSend: (String, List<ReplyAnswer>) -> Unit) {
    val c = Kelo.colors
    val clearTop = with(LocalDensity.current) { (headerHeight() + 8.dp).toPx() }
    var picks by remember(set) { mutableStateOf(mapOf<Int, String>()) }
    var typed by remember(set) { mutableStateOf(mapOf<Int, String>()) }
    // what each field (a picker or a box) holds: its plain form once complete, "" while half filled; starts on the question's default
    var values by remember(set) {
        mutableStateOf(set.questions.withIndex().mapNotNull { (i, q) -> ReplyFields.start(q)?.takeIf { ReplyFields.isField(q) }?.let { i to it } }.toMap())
    }
    val show = rememberFieldWords()
    val writing = QuickReplyRules.writing(set, picks)
    val canSend = QuickReplyRules.canSend(set, picks, typed, values)
    val send = { if (canSend) onSend(QuickReplyRules.compose(set, picks, typed, values, show), QuickReplyRules.answers(set, picks, typed, values)) }
    val other = string(Res.string.reply_other)
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        set.questions.forEachIndexed { i, q ->
            // the question, its answers and its text box are brought into view together, clear of the floating header above the chat
            val bring = remember { BringIntoViewRequester() }
            var blockSize by remember { mutableStateOf(IntSize.Zero) }
            // a pick can re-wrap the answers, and the chat (drawn from the bottom up) then shifts what is above it: keep the question
            // in view below the header
            var picked by remember { mutableIntStateOf(0) }
            LaunchedEffect(picked) {
                if (picked == 0) return@LaunchedEffect
                withFrameNanos { }
                bring.bringIntoView(Rect(0f, -clearTop, blockSize.width.toFloat(), blockSize.height.toFloat()))
            }
            Column(
                Modifier.bringIntoViewRequester(bring).onSizeChanged { blockSize = it },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (q.question.isNotBlank() && (set.questions.size > 1 || ReplyFields.isField(q))) {
                    Text(q.question, style = MaterialTheme.typography.labelLarge, color = c.muted)
                }
                if (ReplyFields.isField(q)) ReplyField(q, values[i].orEmpty(), onValue = { values = values + (i to it); picked++ })
                else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuickReplyRules.choices(q).forEach { o ->
                        ChoiceChip(if (o == QuickReplyRules.OTHER) other else o, selected = picks[i] == o, onClick = {
                            if (QuickReplyRules.sendsOnTap(set, q, o)) onSend(o, listOf(ReplyAnswer(i, o)))
                            else {
                                picks = if (picks[i] == o) picks - i else picks + (i to o)
                                picked++
                            }
                        })
                    }
                }
                if (i in writing) {
                    val focus = remember { FocusRequester() }
                    var focused by remember { mutableStateOf(false) }
                    KeloTextField(
                        value = typed[i].orEmpty(), onValueChange = { typed = typed + (i to it) },
                        placeholder = QuickReplyRules.placeholder(q) ?: string(Res.string.reply_type_hint),
                        modifier = Modifier.focusRequester(focus).onFocusChanged { focused = it.isFocused },
                        singleLine = false, maxLength = 300,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    // the keyboard shrinks the chat from below and the list (drawn from the bottom up) moves its rows up: once it has
                    // settled, the question and its box go back into view below the header
                    val ime = WindowInsets.ime.getBottom(LocalDensity.current)
                    LaunchedEffect(focused, ime) {
                        if (!focused) return@LaunchedEffect
                        delay(KEYBOARD_SETTLE_MS)
                        bring.bringIntoView(Rect(0f, -clearTop, blockSize.width.toFloat(), blockSize.height.toFloat()))
                    }
                }
            }
        }
        if (QuickReplyRules.showsSend(set, picks)) {
            PrimaryButton(string(Res.string.reply_send), enabled = canSend, onClick = send)
            if (!QuickReplyRules.sendsAtOnce(set)) Text(string(Res.string.reply_pick_hint), style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
    }
}

private const val KEYBOARD_SETTLE_MS = 120L
