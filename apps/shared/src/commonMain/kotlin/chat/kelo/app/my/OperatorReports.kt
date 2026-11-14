package chat.kelo.app.my

import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import chat.kelo.app.ui.components.ConfirmDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.chat.ReportReason
import chat.kelo.app.core.ApiError
import chat.kelo.app.ui.components.Card
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.components.SkeletonLines
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.DateFormat
import java.util.Date

/** One report another copy (or this one) sent, as GET /api/operator/reports lists it. */
@Serializable
data class ReceivedReport(
    val id: Long = 0,
    @SerialName("created_at") val createdAt: Double = 0.0,
    val reason: String = "",
    val comment: String = "",
    val reply: String = "",
    val asked: String = "",
    val provider: String = "",
    val model: String = "",
    @SerialName("app_version") val appVersion: String = "",
    val handled: Boolean = false,
)

@Serializable
data class ReceivedReports(
    val reports: List<ReceivedReport> = emptyList(),
    val open: Int = 0,
    @SerialName("keep_days") val keepDays: Int = 365,
    val receiving: Boolean = false,
)

object OperatorReportRules {
    /** The list request: open reports only, or all of them. */
    fun path(showHandled: Boolean): String = if (showHandled) "/api/operator/reports" else "/api/operator/reports?handled=false"

    /** "provider / model", or just one of them, or null when neither is known. */
    fun modelName(r: ReceivedReport): String? = listOf(r.provider, r.model).filter { it.isNotBlank() }.joinToString(" / ").ifBlank { null }
}

/** 운영자 → 받은 신고: what users reported from inside the app, newest first; mark handled or delete. */
@Composable
fun ReportsCard(ui: ReportsUiState, vm: OperatorViewModel) {
    var deleting by remember { mutableStateOf<Long?>(null) }
    Card {
        MutedText(string(Res.string.operator_reports_note), small = true)
        val d = ui.data ?: run {
            if (ui.failed) ErrorText(string(Res.string.connect_failed), small = true) else SkeletonLines(2)
            return@Card
        }
        if (!d.receiving) MutedText(string(Res.string.operator_reports_not_receiving), small = true)
        Text(string(Res.string.operator_reports_open, d.open), color = Kelo.colors.ink, fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium)
        if (d.reports.isEmpty()) MutedText(string(Res.string.operator_reports_empty), small = true)
        for (r in d.reports) ReportRow(r, onMark = { handled -> vm.markReport(r.id, handled) }, onDelete = { deleting = r.id })
        LinkButton(string(if (ui.showHandled) Res.string.operator_reports_hide_handled else Res.string.operator_reports_show_handled),
            onClick = vm::toggleHandledShown)
        if (ui.error.isNotEmpty()) ErrorText(ui.error, small = true)
    }
    deleting?.let { id ->
        ConfirmDialog(string(Res.string.operator_reports_delete_confirm), string(Res.string.operator_reports_delete), onDismiss = { deleting = null }, onYes = {
            deleting = null
            vm.deleteReport(id)
        })
    }
}

@Composable
private fun ReportRow(r: ReceivedReport, onMark: (Boolean) -> Unit, onDelete: () -> Unit) {
    val c = Kelo.colors
    val reason = ReportReason.of(r.reason)?.let { chat.kelo.app.ui.text.string(it.label) } ?: r.reason
    val unknown = string(Res.string.operator_reports_unknown)
    val whenText = remember(r.createdAt) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date((r.createdAt * 1000).toLong())) }
    Column(
        Modifier.fillMaxWidth().background(c.bubble, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(reason, color = c.ink, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (r.handled) Text(string(Res.string.operator_reports_handled), color = c.success, style = MaterialTheme.typography.bodySmall)
        }
        Text(string(Res.string.operator_reports_meta, whenText, OperatorReportRules.modelName(r) ?: unknown, r.appVersion.ifBlank { unknown }),
            color = c.muted, style = MaterialTheme.typography.bodySmall)
        Labeled(string(Res.string.operator_reports_reply), r.reply)
        if (r.asked.isNotBlank()) Labeled(string(Res.string.operator_reports_asked), r.asked)
        if (r.comment.isNotBlank()) Labeled(string(Res.string.operator_reports_comment), r.comment)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(string(if (r.handled) Res.string.operator_reports_unmark else Res.string.operator_reports_mark),
                onClick = { onMark(!r.handled) }, modifier = Modifier.weight(1f))
            LinkButton(string(Res.string.operator_reports_delete), onClick = onDelete, danger = true)
        }
    }
}

@Composable
private fun Labeled(label: String, text: String) {
    val c = Kelo.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = c.muted, style = MaterialTheme.typography.bodySmall)
        Text(text, color = c.ink, style = MaterialTheme.typography.bodySmall)
    }
}
