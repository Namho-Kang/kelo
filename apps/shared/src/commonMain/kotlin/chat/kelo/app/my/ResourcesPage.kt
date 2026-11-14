package chat.kelo.app.my

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.DisposableEffect

import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import chat.kelo.app.ui.components.MyCard
import chat.kelo.app.ui.components.KeyBox
import chat.kelo.app.ui.components.SubPage
import chat.kelo.app.ui.components.Bar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.components.SkeletonRows
import chat.kelo.app.ui.components.emphasized
import chat.kelo.app.ui.theme.Kelo
import chat.kelo.app.ui.platform.keloViewModel
import org.jetbrains.compose.resources.StringResource

/** The size profile in words. */
fun profileText(profile: String, long: Boolean): StringResource? = when (profile) {
    "recommended" -> Res.string.resources_recommended
    "minimum" -> Res.string.resources_minimum
    "unlimited" -> if (long) Res.string.resources_unlimited_long else Res.string.resources_unlimited
    "custom" -> Res.string.resources_custom
    else -> null
}

private val PARTS = mapOf(
    "database" to Res.string.resources_part_database, "uploads" to Res.string.resources_part_uploads,
    "images" to Res.string.resources_part_images,
    "notes" to Res.string.resources_part_notes, "history" to Res.string.resources_part_history, "other" to Res.string.resources_part_other,
)

/**
 * What this assistant may use on its computer (RAM, CPUs, storage). Where nothing was set, the ceiling is the
 * computer itself, so that is what is shown (never just "no limit"). Reloaded every 10 seconds while the page is in view.
 * Until the first numbers arrive it shows placeholder lines, or, when reading them failed, why with "try again" (never a blank page).
 * A storage warning (80% / 95% of the cap, or the server's own disk almost full) shows on top with what takes the most room; the
 * owner gets 정리하기 ([onCleanup]: the cleanup screen) there and under the storage numbers.
 */
@Composable
fun ResourcesPage(onBack: () -> Unit, onCleanup: (() -> Unit)? = null) {
    val sessions = LocalAppServices.current.sessions
    val vm = keloViewModel("my-resources") { ResourcesViewModel(sessions) }
    val load by vm.state.collectAsStateWithLifecycle()
    SubPage(string(Res.string.resources_title), onBack) {
        val res = load.data
        if (res == null) {
            if (load.showError) {
                ErrorText(string(Res.string.resources_load_failed))
                SecondaryButton(string(Res.string.retry), onClick = vm::retry)
            } else {
                SkeletonRows(3)
            }
            return@SubPage
        }
        val memory = res.memory
        val disk = res.disk
        val host = res.host
        // the real ceiling of each: the limit that was set, else what the computer has
        val ramCeiling = memory.limit ?: host.memory
        val cpuCeiling = res.cpus ?: host.cpus
        val diskCeiling = disk.limit ?: host.diskFree?.let { disk.used + it }
        val state = StorageRules.state(res.alert)
        val clean = onCleanup?.takeIf { res.canClean }
        // a server from before the storage warnings sends no alert: its old 90% line still shows
        val full = state == StorageState.OK && disk.limit != null && disk.used >= disk.limit!! * 0.9

        if (state != StorageState.OK) StorageWarning(state, res, clean)

        MyCard {
            Text(
                profileText(res.profile, long = true)?.let { string(it) } ?: res.profile,
                style = MaterialTheme.typography.titleSmall, color = Kelo.colors.ink,
            )
            KeyBox {
                val used = memory.used?.let { string(Res.string.resources_used, storageSize(it)) }.orEmpty()
                val ceiling = ramCeiling?.let {
                    string(if (memory.limit != null) Res.string.resources_limit else Res.string.resources_whole_computer, storageSize(it))
                }.orEmpty()
                Text(string(Res.string.resources_ram, used, ceiling).trimEnd(), color = Kelo.colors.ink)
                if (memory.used != null) UseBar(memory.used!!, ramCeiling)
                Hint(storageSize(res.minimum.memory), storageSize(res.recommended.memory), memory.limit != null && memory.limit!! < res.minimum.memory)
            }
            KeyBox(first = false) {
                Text(
                    when {
                        cpuCeiling == null -> string(Res.string.resources_cpu_whole)
                        res.cpus != null -> string(Res.string.resources_cpu_max, cpuCount(cpuCeiling))
                        else -> string(Res.string.resources_cpu_all, cpuCount(cpuCeiling))
                    },
                    color = Kelo.colors.ink,
                )
                Hint(
                    string(Res.string.resources_count, cpuCount(res.minimum.cpus)),
                    string(Res.string.resources_count, cpuCount(res.recommended.cpus)),
                    res.cpus != null && res.cpus!! < res.minimum.cpus,
                )
            }
            KeyBox(first = false) {
                val ceiling = diskCeiling?.let {
                    string(if (disk.limit != null) Res.string.resources_limit else Res.string.resources_disk_with_free, storageSize(it))
                }.orEmpty()
                Text(string(Res.string.resources_disk, storageSize(disk.used), ceiling).trimEnd(), color = Kelo.colors.ink)
                UseBar(disk.used, diskCeiling)
                Hint(storageSize(res.minimum.disk), storageSize(res.recommended.disk), disk.limit != null && disk.limit!! < res.minimum.disk)
                val parts = disk.parts.filter { it.value > 0 }.map { (k, n) ->
                    string(Res.string.resources_part, PARTS[k]?.let { string(it) } ?: k, storageSize(n))
                }
                MutedText(parts.joinToString(string(Res.string.usage_group_joiner)).ifEmpty { string(Res.string.resources_empty) }, small = true)
                if (full) ErrorText(string(Res.string.resources_full), small = true)
                if (clean != null) SecondaryButton(string(Res.string.storage_clean_up), onClick = clean)
            }
            val q = string(Res.string.resources_unknown)
            Text(
                emphasized(
                    string(
                        Res.string.resources_host,
                        host.memory?.let { storageSize(it) } ?: q,
                        host.cpus?.let { cpuCount(it) } ?: q,
                        host.diskFree?.let { storageSize(it) } ?: q,
                    ),
                    "assistant limits --choose", code = true,
                ),
                color = Kelo.colors.muted, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The warning box: red for 95% and a nearly full server disk, quieter for 80%; the biggest part; 정리하기 for the owner. */
@Composable
private fun StorageWarning(state: StorageState, res: Resources, onCleanup: (() -> Unit)?) {
    val c = Kelo.colors
    val urgent = StorageRules.urgent(state)
    val percent = res.alert.percent ?: 0
    val text = when (state) {
        StorageState.HOST_LOW -> string(Res.string.storage_host_low, storageSize(res.host.diskFree ?: 0))
        StorageState.CRITICAL -> string(Res.string.storage_critical, percent)
        else -> string(Res.string.storage_warn, percent)
    }
    val top = res.alert.top?.let { k -> res.disk.parts[k]?.let { n -> k to n } }
    androidx.compose.foundation.layout.Column(
        androidx.compose.ui.Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (urgent) c.dangerSoft else c.neutralSoft).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, color = if (urgent) c.dangerInk else c.warn, style = MaterialTheme.typography.bodyMedium)
        if (top != null) {
            Text(
                string(Res.string.storage_top, PARTS[top.first]?.let { string(it) } ?: top.first, storageSize(top.second)),
                color = if (urgent) c.dangerInk else c.neutralInk, style = MaterialTheme.typography.bodySmall,
            )
        }
        if (onCleanup != null) PrimaryButton(string(Res.string.storage_clean_up), onClick = onCleanup)
    }
}

@Composable
private fun UseBar(used: Long, of: Long?) {
    if (of == null || of <= 0) return
    val f = (used.toFloat() / of).coerceIn(0f, 1f)
    Bar(f, hot = f > 0.9f)
}

/** "최소 4GB · 권장 8GB", red with "최소보다 작아요 · " in front when the set limit is under the minimum. */
@Composable
private fun Hint(min: String, rec: String, under: Boolean) {
    val text = string(Res.string.resources_hint, min, rec)
    if (under) ErrorText(string(Res.string.resources_under_min, text), small = true) else MutedText(text, small = true)
}
