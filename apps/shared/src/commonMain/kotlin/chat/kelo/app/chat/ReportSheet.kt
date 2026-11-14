package chat.kelo.app.chat

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.core.ApiError
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 신고하기: tell the Kelo developer about a reply that was offensive, harmful, wrong or exposed someone's private details. The sheet
 * says exactly what will be sent before anything is. [draft] is what was left from last time; [onDraft] keeps it when the sheet
 * closes without sending, [onSent] after the developer's server took it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(reply: String, asked: String?, draft: ReportDraft, onDraft: (ReportDraft) -> Unit, onSent: () -> Unit, onClose: () -> Unit) {
    val c = Kelo.colors
    val services = LocalAppServices.current
    val sessions = services.sessions
    val appVersion = services.appVersion
    val scope = rememberCoroutineScope()
    var d by remember { mutableStateOf(draft) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    val offline = string(Res.string.report_failed_offline)
    ModalBottomSheet(
        onDismissRequest = { if (!busy) { onDraft(d); onClose() } },
        sheetState = chat.kelo.app.ui.components.rememberKeloSheetState(),
        containerColor = c.card,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(string(Res.string.report_title), style = MaterialTheme.typography.titleMedium, color = c.ink,
                modifier = Modifier.semantics { heading() })
            MutedText(string(Res.string.report_intro), small = true)
            Text(string(Res.string.report_reason_label), color = c.ink, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Column {
                for (r in ReportReason.entries) {
                    Row(
                        Modifier.fillMaxWidth().selectable(d.reason == r, role = Role.RadioButton, onClick = { d = d.copy(reason = r) })
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = d.reason == r, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = c.accent, unselectedColor = c.muted))
                        Text(chat.kelo.app.ui.text.string(r.label), color = c.ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp).weight(1f))
                    }
                }
            }
            KeloTextField(
                d.comment, { d = d.copy(comment = it) }, string(Res.string.report_comment_hint),
                singleLine = false, maxLength = ReportRules.MAX_COMMENT,
            )
            if (asked != null) {
                Row(
                    Modifier.fillMaxWidth().toggleable(d.includeAsked, role = Role.Checkbox, onValueChange = { d = d.copy(includeAsked = it) })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = d.includeAsked, onCheckedChange = null,
                        colors = CheckboxDefaults.colors(checkedColor = c.accent, checkmarkColor = c.onAccent, uncheckedColor = c.muted))
                    Text(string(Res.string.report_include_asked), color = c.ink, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp).weight(1f))
                }
            }
            // exactly what leaves the phone, following the tick above
            Column(
                Modifier.fillMaxWidth().background(c.bubble, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(string(Res.string.report_sent_title), color = c.ink, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                val lines = buildList {
                    add(Res.string.report_sent_reply)
                    if (asked != null && d.includeAsked) add(Res.string.report_sent_asked)
                    add(Res.string.report_sent_reason)
                    add(Res.string.report_sent_model)
                    add(Res.string.report_sent_version)
                }
                for (l in lines) Text(string(Res.string.report_bullet, string(l)), color = c.ink, style = MaterialTheme.typography.bodySmall)
                Text(string(Res.string.report_sent_not), color = c.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            if (err.isNotEmpty()) ErrorText(err, small = true)
            PrimaryButton(
                string(if (busy) Res.string.report_sending else Res.string.report_send),
                enabled = !busy && ReportRules.canSend(d),
                onClick = {
                    busy = true; err = ""
                    scope.launch {
                        try {
                            sessions.requireApi().request("POST", "/api/reports", ReportRules.body(d, reply, asked, appVersion))
                            onSent()
                        } catch (e: ApiError) {
                            err = if (e.isNetwork) offline else e.detail
                            onDraft(d)   // kept: the next try starts from here
                        } finally {
                            busy = false
                        }
                    }
                },
            )
        }
    }
}
