package chat.kelo.app.chat

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.delay

@Composable
fun noticeText(n: ChatNotice): String = when (n) {
    is ChatNotice.Fallback -> string(Res.string.chat_notice_fallback, ModelNames.display(n.model, null), n.reason)
    ChatNotice.Retry -> string(Res.string.chat_notice_retry)
    ChatNotice.Reconnecting -> string(Res.string.chat_notice_reconnecting)
    is ChatNotice.Uploaded -> string(Res.string.chat_notice_uploaded, n.name)
}

@Composable
fun NoticeChip(text: String) {
    val c = Kelo.colors
    Row(Modifier.fillMaxWidth()) {
        Text(
            text, fontSize = 12.sp, lineHeight = 16.sp, color = c.muted,
            modifier = Modifier.border(1.dp, c.line, RoundedCornerShape(99.dp)).padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun ToolChip(t: ChatItem.Tool) {
    if (t.folded) {
        FoldedToolChip(t)
        return
    }
    // a tool still running is already told by the status line under the messages: one line, not two
    if (t.state == ToolState.RUNNING) return
    val res = when (t.state) {
        ToolState.RUNNING -> Res.string.chat_tool_running
        ToolState.OK -> Res.string.chat_tool_ok
        ToolState.ERROR -> Res.string.chat_tool_error
        ToolState.DENIED -> Res.string.chat_tool_denied
    }
    NoticeChip(string(res, t.shown))
}

/**
 * Several calls of one kind in a row as ONE chip that updates in place: "메일 읽는 중 · 3/6" while they run, "✓ 메일 6개 읽음" once done
 * (failures counted after it). Tapping it shows each call with what it was about.
 */
@Composable
private fun FoldedToolChip(t: ChatItem.Tool) {
    val c = Kelo.colors
    var open by remember(t.key) { mutableStateOf(false) }
    val m = t.members
    val ok = ChatReducer.doneCount(t)
    val failed = m.count { it.state == ToolState.ERROR || it.state == ToolState.DENIED }
    val running = m.any { it.state == ToolState.RUNNING }
    val text = when {
        running -> string(Res.string.chat_tool_progress, t.shown, m.size - m.count { it.state == ToolState.RUNNING }, m.size)
        ok == 0 -> string(if (m.all { it.state == ToolState.DENIED }) Res.string.chat_tool_denied else Res.string.chat_tool_error, t.shown) + " · ${m.size}"
        failed > 0 -> string(Res.string.chat_tool_ok_failed, ChatReducer.foldedText(t, ok), failed)
        else -> string(Res.string.chat_tool_ok, ChatReducer.foldedText(t, ok))
    }
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                text + if (open) "  ▴" else "  ▾", fontSize = 12.sp, lineHeight = 16.sp, color = c.muted,
                modifier = Modifier.clip(RoundedCornerShape(99.dp)).border(1.dp, c.line, RoundedCornerShape(99.dp))
                    .clickable(role = Role.Button, onClick = { open = !open }).padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        if (open) {
            Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                m.forEach { one ->
                    val mark = when (one.state) {
                        ToolState.RUNNING -> "⏳"
                        ToolState.OK -> "✓"
                        ToolState.ERROR -> "✗"
                        ToolState.DENIED -> "⛔"
                    }
                    Text("$mark ${one.detail.ifEmpty { one.shown }}", fontSize = 12.sp, lineHeight = 16.sp, color = c.muted)
                }
            }
        }
    }
}

@Composable
fun StatusLine(p: Phase) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(p.since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val phase = string(
        when (p.kind) {
            PhaseKind.WAITING -> Res.string.chat_phase_waiting
            PhaseKind.THINKING -> if (ChatReducer.longThinking(p, now)) Res.string.chat_phase_thinking_long else Res.string.chat_phase_thinking
            PhaseKind.WRITING -> Res.string.chat_phase_writing
            PhaseKind.TOOL -> Res.string.chat_phase_tool
            PhaseKind.APPROVAL -> Res.string.chat_phase_approval
            PhaseKind.SWITCHING -> Res.string.chat_phase_switching
        },
    )
    val text = when {
        p.label.isNullOrEmpty() -> string(Res.string.chat_status, phase)
        // a tool's own words already say what is going on ("사이트 여는 중")
        p.kind == PhaseKind.TOOL -> string(Res.string.chat_status, p.label!!)
        // the fallback's model, readable rather than its id
        p.kind == PhaseKind.SWITCHING -> string(Res.string.chat_status_label, phase, ModelNames.display(p.label!!, null))
        else -> string(Res.string.chat_status_label, phase, p.label!!)
    }
    val seconds = ((now - p.since) / 1000).coerceAtLeast(0).toInt()
    val c = Kelo.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dots()
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Text(string(Res.string.chat_seconds, seconds), style = MaterialTheme.typography.bodySmall, color = c.muted)
    }
}

@Composable
private fun Dots() {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(
                initialValue = 0.25f, targetValue = 1f, label = "dot$i",
                animationSpec = infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse),
            )
            Box(Modifier.size(6.dp).alpha(a).background(Kelo.colors.muted, CircleShape))
        }
    }
}
