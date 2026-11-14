package chat.kelo.app.my

import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.core.ApiError
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.platform.LocalHaptics
import chat.kelo.app.ui.theme.Kelo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One account the briefing and the heads-ups can read (GET /api/briefing/sources). [problem]: the quiet line when it could not be read. */
@Serializable
data class SourceAccount(
    val id: String = "",
    /** calendar | mail */
    val kind: String = "calendar",
    val provider: String = "",
    /** the provider's name in the app's language ("Naver Calendar") */
    val name: String = "",
    val address: String = "",
    /** the short name on the briefing card */
    val label: String = "",
    val on: Boolean = true,
    val problem: String? = null,
)

/** One kind of account (calendars or mail): every account on unless the owner switched it off; a new one starts on. */
@Serializable
data class SourceGroup(val accounts: List<SourceAccount> = emptyList())

@Serializable
data class BriefingSources(val calendar: SourceGroup = SourceGroup(), val mail: SourceGroup = SourceGroup())

/** The "what it reads" list's small rules, apart from the UI. */
object BriefingSourcesRules {
    val KINDS = listOf("calendar", "mail")

    fun group(s: BriefingSources, kind: String): SourceGroup = if (kind == "mail") s.mail else s.calendar

    /** The line that names the account: its address, or the provider's name when it has none (the phone's calendar). */
    fun title(a: SourceAccount): String = a.address.ifBlank { a.name }

    /** The line under it: the provider, when the title is the address. */
    fun subtitle(a: SourceAccount): String = if (a.address.isBlank()) "" else a.name

    /** Groups worth showing: one with no account has nothing to choose. */
    fun shown(s: BriefingSources): List<String> = KINDS.filter { group(s, it).accounts.isNotEmpty() }

    /** The PUT body that switches one account on or off. */
    fun toggleBody(kind: String, id: String, on: Boolean): JsonObject =
        buildJsonObject { put(kind, buildJsonObject { put("accounts", buildJsonObject { put(id, on) }) }) }

    /** The group as it will be after one switch is flipped (shown at once, before the server answers). */
    fun flipped(s: BriefingSources, kind: String, id: String, on: Boolean): BriefingSources {
        val g = group(s, kind)
        val next = g.copy(accounts = g.accounts.map { if (it.id == id) it.copy(on = on) else it })
        return if (kind == "mail") s.copy(mail = next) else s.copy(calendar = next)
    }
}

/**
 * "What it reads" (마이 → 알림, its own section, shown whether the briefing is on or not: the heads-ups and the chat's calendar read the
 * same accounts): every connected calendar and mailbox, each with one switch, in two groups. All are on by default and a newly
 * connected account starts on. An account that could not be read shows one quiet line with what to do. The same switches can be
 * flipped by telling Kelo in the chat.
 */
@Composable
fun BriefingSourcesBlock(vm: BriefingSettingsViewModel, header: @Composable () -> Unit = {}) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val c = Kelo.colors
    val s = ui.sources ?: return
    val kinds = BriefingSourcesRules.shown(s)
    if (kinds.isEmpty()) return
    val haptics = LocalHaptics.current
    header()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MutedText(string(Res.string.briefing_sources_about), small = true)
        kinds.forEach { kind ->
            val g = BriefingSourcesRules.group(s, kind)
            val name = string(if (kind == "mail") Res.string.briefing_sources_mail else Res.string.briefing_sources_calendars)
            Text(name, style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.padding(top = 6.dp))
            g.accounts.forEachIndexed { i, a ->
                if (i > 0) HorizontalDivider(color = c.line)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        // one line each: a long address is cut in its middle, a long name at its end (never wrapped mid-word)
                        Text(chat.kelo.app.ui.components.TokenText.fit(BriefingSourcesRules.title(a)), style = MaterialTheme.typography.bodyLarge, color = c.ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BriefingSourcesRules.subtitle(a).takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        a.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 2.dp)) }
                    }
                    val title = BriefingSourcesRules.title(a)
                    Switch(
                        checked = a.on, modifier = Modifier.semantics { contentDescription = title },
                        onCheckedChange = { on ->
                            haptics.tick()
                            vm.flipSource(kind, a.id, on)
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent),
                    )
                }
            }
        }
        MutedText(string(Res.string.briefing_sources_chat), small = true)
        SecondaryButton(
            string(if (ui.checkingSources) Res.string.briefing_sources_checking else Res.string.briefing_sources_check),
            enabled = !ui.checkingSources,
            onClick = vm::checkSources,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
