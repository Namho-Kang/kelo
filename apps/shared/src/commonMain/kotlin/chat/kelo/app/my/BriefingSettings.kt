package chat.kelo.app.my

import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.chat.SelectField
import chat.kelo.app.chat.SelectOption
import chat.kelo.app.core.ApiError
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.LocalHaptics
import chat.kelo.app.ui.theme.Kelo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.ui.platform.LocalReloadOnReturn
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Where a phone of the owner's last was (weather.py "here"): what the weather is for. */
@Serializable
data class BriefingPlaceInfo(
    val name: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    @SerialName("country_code") val countryCode: String = "",
    val at: Double? = null,
)

@Serializable
data class WeatherProviderInfo(
    val id: String = "",
    val name: String = "",
    val attribution: String = "",
    @SerialName("attribution_url") val attributionUrl: String = "",
)

@Serializable
data class BriefingWeatherSettings(
    /** auto | open_meteo | kma | weatherapi */
    val provider: String = "auto",
    /** The last location a phone sent; null: none yet, so the briefing has no weather */
    val here: BriefingPlaceInfo? = null,
    val keys: Map<String, Boolean> = emptyMap(),
    val providers: List<WeatherProviderInfo> = emptyList(),
)

/** GET /api/briefing: the morning briefing's settings (server: briefing.py). */
@Serializable
data class BriefingSettings(
    val enabled: Boolean = true,
    /** auto (at the start of the day's hours, or the morning's first app open) | fixed ([fixedTime]) */
    @SerialName("time_mode") val timeMode: String = "auto",
    @SerialName("fixed_time") val fixedTime: String = "07:30",
    @SerialName("offset_min") val offsetMin: Int = 0,
    @SerialName("early_open") val earlyOpen: Boolean = true,
    val sections: Map<String, Boolean> = mapOf("weather" to true, "calendar" to true, "missed" to true),
    /** short | skip: what a day with nothing notable gets */
    @SerialName("quiet_days") val quietDays: String = "short",
    val weather: BriefingWeatherSettings = BriefingWeatherSettings(),
)

/** The briefing settings' small rules, apart from the UI. */
object BriefingSettingsRules {
    val OFFSETS = listOf(0, 10, 20, 30, 45, 60)
    val SECTIONS = listOf("weather", "calendar", "missed")
    val PROVIDERS = listOf("auto", "open_meteo", "kma", "weatherapi")
    val KEYED = listOf("kma", "weatherapi")

    /** The offset choices, with the current one kept even when it is not a preset. */
    fun offsets(current: Int): List<Int> = (OFFSETS + current.coerceIn(0, 60)).distinct().sorted()

    fun sectionOn(b: BriefingSettings, id: String): Boolean = b.sections[id] ?: true

    /** The PUT body that turns one section on or off. */
    fun sectionBody(id: String, on: Boolean): JsonObject = buildJsonObject { put("sections", buildJsonObject { put(id, on) }) }

    /** A key the owner pastes: one line, no spaces, 8..512 characters (the server checks it with a real request). */
    fun keyOk(key: String): Boolean = key.trim().let { it.length in 8..512 && it.none(Char::isWhitespace) }

    /** The quiet "weather shows once location is allowed" line: the weather is in the briefing, and there is no location to use. */
    fun needsLocation(b: BriefingSettings, granted: Boolean): Boolean = sectionOn(b, "weather") && (!granted || b.weather.here == null)
}

@Composable
private fun providerLabel(id: String): String = when (id) {
    "open_meteo" -> "Open-Meteo"
    "kma" -> string(Res.string.briefing_provider_kma)
    "weatherapi" -> "WeatherAPI.com"
    else -> string(Res.string.briefing_provider_auto)
}

@Composable
private fun offsetLabel(min: Int): String =
    if (min == 0) string(Res.string.briefing_offset_none) else string(Res.string.briefing_offset_min, min)

/**
 * 마이 → 알림 → 아침 브리핑: on or off; when (at the start of the day's hours plus a few minutes, earlier on the morning's first app
 * open, or a fixed time); which sections (turning the weather on asks for location: the weather is always where the phone last was,
 * sent when the app is opened; without any location the briefing leaves the weather out and one quiet line offers to allow it);
 * quiet days; the weather service and the owner's own keys.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BriefingBlock(vm: BriefingSettingsViewModel) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val b = ui.settings ?: return
    val c = Kelo.colors
    var picking by remember { mutableStateOf(false) }
    var keyFor by remember { mutableStateOf<String?>(null) }
    val granted = ui.granted
    val put = vm::put
    val canAsk = LocalAppServices.current.phoneLocation != null
    // back from the app's settings: location may be allowed now
    LocalReloadOnReturn.current(Unit) { vm.recheckPlace() }

    Toggle(string(Res.string.briefing_switch), string(Res.string.briefing_about), b.enabled) { on -> put(buildJsonObject { put("enabled", on) }) }
    if (!b.enabled) return
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(string(Res.string.briefing_when), style = MaterialTheme.typography.labelLarge, color = c.muted)
        SelectField(
            string(Res.string.briefing_when),
            listOf(SelectOption("auto", string(Res.string.briefing_when_auto)), SelectOption("fixed", string(Res.string.briefing_when_fixed))),
            b.timeMode,
        ) { v -> put(buildJsonObject { put("time_mode", v) }) }
        if (b.timeMode == "fixed") {
            SecondaryButton(string(Res.string.briefing_fixed_at, NotifySettingsRules.parseTime(b.fixedTime)?.let { (h, m) -> chat.kelo.app.agent.clockText(h, m) } ?: b.fixedTime),
                onClick = { picking = true }, modifier = Modifier.fillMaxWidth())
        } else {
            Text(string(Res.string.briefing_offset), style = MaterialTheme.typography.bodySmall, color = c.muted)
            SelectField(
                string(Res.string.briefing_offset),
                BriefingSettingsRules.offsets(b.offsetMin).map { SelectOption(it.toString(), offsetLabel(it)) },
                b.offsetMin.toString(),
            ) { v -> v.toIntOrNull()?.let { put(buildJsonObject { put("offset_min", it) }) } }
        }
    }
    if (b.timeMode != "fixed") {
        Toggle(string(Res.string.briefing_early_open), string(Res.string.briefing_early_open_about), b.earlyOpen) { on ->
            put(buildJsonObject { put("early_open", on) })
        }
    }

    // what
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(string(Res.string.briefing_sections), style = MaterialTheme.typography.labelLarge, color = c.muted)
        BriefingSettingsRules.SECTIONS.forEach { id ->
            val label = string(when (id) { "weather" -> Res.string.briefing_weather; "calendar" -> Res.string.briefing_today; else -> Res.string.briefing_missed })
            CheckLine(label, BriefingSettingsRules.sectionOn(b, id)) { on ->
                put(BriefingSettingsRules.sectionBody(id, on))
                if (id == "weather" && on && canAsk && !granted) vm.allowPlace()   // the weather needs to know where the phone is
            }
            if (id == "weather" && BriefingSettingsRules.sectionOn(b, id)) {
                // lined up with the check box's label above
                Column(Modifier.padding(start = 32.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (canAsk && BriefingSettingsRules.needsLocation(b, granted)) {
                        MutedText(string(if (ui.servicesOn) Res.string.briefing_weather_needs_location else Res.string.location_services_off), small = true)
                        SecondaryButton(string(Res.string.briefing_location_allow), onClick = vm::allowPlace)
                    }
                    MutedText(string(Res.string.briefing_location_privacy), small = true)
                }
            }
        }
        CheckLine(string(Res.string.briefing_quiet_skip), b.quietDays == "skip") { on -> put(buildJsonObject { put("quiet_days", if (on) "skip" else "short") }) }
    }

    // the weather service
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(string(Res.string.briefing_provider), style = MaterialTheme.typography.labelLarge, color = c.muted)
        SelectField(
            string(Res.string.briefing_provider),
            BriefingSettingsRules.PROVIDERS.map { SelectOption(it, providerLabel(it)) },
            b.weather.provider, wrap = true,
        ) { v -> put(buildJsonObject { put("provider", v) }) }
        MutedText(string(Res.string.briefing_provider_about), small = true)
        BriefingSettingsRules.KEYED.forEach { id ->
            val saved = b.weather.keys[id] == true
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(KeloIcons.Key, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(providerLabel(id), style = MaterialTheme.typography.bodyMedium, color = c.ink)
                    Text(string(if (saved) Res.string.briefing_key_saved else Res.string.briefing_key_none), style = MaterialTheme.typography.bodySmall,
                        color = c.muted)
                }
                if (saved) {
                    SecondaryButton(string(Res.string.briefing_key_delete), onClick = { vm.deleteKey(id) })
                } else SecondaryButton(string(Res.string.briefing_key_add), onClick = { keyFor = id })
            }
        }
        val credit = b.weather.providers.firstOrNull { it.id == "open_meteo" }?.attribution ?: "Weather data by Open-Meteo.com"
        MutedText(credit, small = true)   // the data's licence (CC BY 4.0) asks for this credit
        SecondaryButton(
            string(if (ui.sent == true) Res.string.briefing_sent else Res.string.briefing_try),
            enabled = ui.sent == null,
            onClick = vm::tryNow,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (picking) {
        val start = NotifySettingsRules.parseTime(b.fixedTime) ?: (7 to 30)
        val t = rememberTimePickerState(start.first, start.second, is24Hour = chat.kelo.app.agent.uses24Hour())
        AlertDialog(
            onDismissRequest = { picking = false }, containerColor = c.card,
            text = { TimePicker(t) },
            confirmButton = {
                TextButton(onClick = { put(buildJsonObject { put("fixed_time", NotifySettingsRules.formatTime(t.hour, t.minute)) }); picking = false }) {
                    Text(string(Res.string.ok), color = c.accent)
                }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(string(Res.string.cancel), color = c.ink) } },
        )
    }
    keyFor?.let { id ->
        KeyDialog(id, providerLabel(id), vm, onDismiss = { keyFor = null }, onSaved = { keyFor = null })
    }
}

@Composable
private fun Toggle(title: String, body: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    val c = Kelo.colors
    val haptics = LocalHaptics.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            Text(body, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        Switch(
            checked = on, onCheckedChange = { haptics.tick(); onToggle(it) }, modifier = Modifier.semantics { contentDescription = title },
            colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent),
        )
    }
}

@Composable
private fun CheckLine(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Kelo.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { onChange(!on) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(on, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = c.accent, checkmarkColor = c.onAccent))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.padding(start = 8.dp).weight(1f))
    }
}

/** The owner's own key for a weather service: pasted, checked by the server with one real request, sealed in the vault. */
@Composable
private fun KeyDialog(id: String, name: String, vm: BriefingSettingsViewModel, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val c = Kelo.colors
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.card,
        title = { Text(name, color = c.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MutedText(string(if (id == "kma") Res.string.briefing_key_help_kma else Res.string.briefing_key_help_weatherapi), small = true)
                KeloTextField(key, { key = it; error = "" }, string(Res.string.briefing_key_placeholder), password = true, maxLength = 512)
                if (error.isNotEmpty()) ErrorText(error, small = true)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && BriefingSettingsRules.keyOk(key), onClick = {
                busy = true
                vm.saveKey(id, key) { problem -> busy = false; if (problem == null) onSaved() else error = problem }
            }) { Text(string(Res.string.briefing_key_save), color = c.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(string(Res.string.cancel), color = c.ink) } },
    )
}
