package chat.kelo.app.chat

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import chat.kelo.app.ui.theme.Kelo

@Composable
fun errorText(e: ChatError): String = when (e) {
    ChatError.StepLimit -> string(Res.string.chat_error_step_limit)
    is ChatError.Message -> e.text
    is ChatError.PickModel -> string(Res.string.chat_error_pick_model)
    ChatError.Network -> string(Res.string.chat_error_network)
    is ChatError.Unsupported -> string(Res.string.share_unsupported, e.name)
    ChatError.ShareFailed -> string(Res.string.share_failed)
    ChatError.CameraDenied -> string(Res.string.chat_camera_denied)
    ChatError.CameraMissing -> string(Res.string.chat_camera_missing)
}

private val BubbleShape = RoundedCornerShape(20.dp)

/**
 * The user's own message; a long press opens its menu (복사, 공유, 텍스트 선택, 수정해서 다시 보내기). [reaction]: the emoji Kelo put on
 * it ([Reactions]), a chip over the bubble's bottom edge near its end corner ([BubbleWithReaction]); the row keeps room for the part
 * that hangs below.
 */
@Composable
fun UserBubble(text: String, reaction: String, actions: List<MsgAction>, onAction: (MsgAction) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalArrangement = Arrangement.End) {
        BubbleWithReaction(reaction) {
            ActionBubble(actions, copyText = { text }, shape = BubbleShape, fromEnd = true, onAction = onAction) {
                JoinedText(
                    AnnotatedString(text), color = Kelo.colors.ink, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.clip(BubbleShape).background(Kelo.colors.mine).padding(horizontal = 15.dp, vertical = 11.dp),
                )
            }
        }
    }
}

/**
 * A reply, one bubble per <message_break/>. While it is [streaming] the text is revealed smoothly ([rememberRevealed]) instead of
 * jumping by a chunk at each poll; only the block being written is parsed again as text arrives ([StreamingMarkdown]).
 */
@Composable
fun AssistantBubbles(text: String, streaming: Boolean, actions: List<MsgAction>, onAction: (String, MsgAction) -> Unit) {
    val c = Kelo.colors
    val parser = remember(c) { StreamingMarkdown(MdStyles.of(c)) }
    val bubbles = remember(text, parser) { parser.bubbles(text) }
    // each bubble's own text, without the Markdown marks, for 복사 / 공유 / 텍스트 선택: worked out when one is used
    val plain: (Int) -> String = remember(text) { { i -> Markdown.plain(Markdown.splitBubbles(text).getOrElse(i) { "" }) } }
    val total = remember(bubbles) { bubbles.sumOf { b -> b.sumOf { it.length } } }
    val revealed by rememberRevealed(total, streaming)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var before = 0
        for ((i, blocks) in bubbles.withIndex()) {
            val visible = revealed - before
            before += blocks.sumOf { it.length }
            if (visible <= 0f) break
            Row(Modifier.fillMaxWidth().padding(end = 48.dp)) {
                // no menu while the reply is still being written
                ActionBubble(if (streaming) emptyList() else actions, copyText = { plain(i) }, shape = BubbleShape, fromEnd = false,
                    onAction = { a -> onAction(plain(i), a) }) {
                    StyledMarkdown(
                        blocks,
                        Modifier.clip(BubbleShape).background(c.bubble).padding(horizontal = 15.dp, vertical = 11.dp),
                        visible = if (revealed >= total) null else visible,
                    )
                }
            }
        }
    }
}

/** An error; a long press offers 복사 and (when it is after the user's last message) 다시 시도. */
@Composable
fun ErrorBubble(text: String, actions: List<MsgAction>, onAction: (MsgAction) -> Unit) {
    val c = Kelo.colors
    Row(Modifier.fillMaxWidth().padding(end = 48.dp)) {
        ActionBubble(actions, copyText = { text }, shape = BubbleShape, fromEnd = false, onAction = onAction) {
            Text(
                text, style = MaterialTheme.typography.bodyLarge,
                color = c.dangerInk,
                modifier = Modifier.clip(BubbleShape).background(c.dangerSoft)
                    .padding(horizontal = 15.dp, vertical = 11.dp),
            )
        }
    }
}
