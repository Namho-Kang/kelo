package chat.kelo.app.update

import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.theme.Kelo

/** "새 버전 0.1.x가 있어요 · 27.6 MB"; from Google Play (no version or size to show): "새 버전이 있어요"; ready: "다시 시작해서 적용" */
@Composable
fun offerText(s: UpdateState): String {
    val r = s.offer ?: return ""
    return when {
        s.play && s.phase == UpdatePhase.READY -> string(Res.string.update_downloaded)
        s.play -> string(Res.string.update_available_store)
        else -> string(Res.string.update_available, r.version, UpdateRules.size(r.size))
    }
}

/** The main button's word while nothing is downloading: 받기 (file builds) / 업데이트 (Play) / 다시 시작 or 설치 when ready. */
@Composable
private fun actionWord(s: UpdateState): String = when {
    s.phase == UpdatePhase.READY -> string(if (s.play) Res.string.update_restart else Res.string.update_install)
    s.phase == UpdatePhase.FAILED || s.phase == UpdatePhase.MISMATCH -> string(Res.string.update_retry)
    s.play -> string(Res.string.update_now)
    else -> string(Res.string.update_get)
}

/** The value line of 마이 → 업데이트 확인. */
@Composable
fun checkValue(s: UpdateState): String? = when {
    s.checking -> string(Res.string.update_checking)
    s.offer != null && s.play -> string(Res.string.update_available_store)
    s.offer != null -> string(Res.string.update_new_value, s.offer.version)
    s.checkFailed -> string(Res.string.update_check_failed)
    s.checked -> string(Res.string.update_latest)
    else -> null
}

/**
 * The small one-time banner over the tabs when a newer build is out: the version and size, 받기 and close. Shown once per version
 * ([UpdateState.showBanner]); either button marks it seen, and 받기 also starts the download (followed in 마이). On Play it comes
 * back once more when the download is done, with 다시 시작 ([onInstall]: completeUpdate).
 */
@Composable
fun UpdateBanner(s: UpdateState, onGet: () -> Unit, onInstall: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = Kelo.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .shadow(4.dp, shape, clip = false)
            .clip(shape)
            .background(c.card)
            .border(1.dp, c.line, shape)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(KeloIcons.ArrowDown, contentDescription = null, tint = c.accent, modifier = Modifier.size(20.dp))
        Text(offerText(s), color = c.ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 6.dp))
        TextButton(onClick = if (s.phase == UpdatePhase.READY) onInstall else onGet) { Text(actionWord(s), color = c.accent) }
        IconButton(onClick = onClose) { Icon(KeloIcons.Close, contentDescription = string(Res.string.close), tint = c.muted, modifier = Modifier.size(18.dp)) }
    }
}

/**
 * The quiet card at the top of 마이 while a newer build is offered: its version and size, then 받기 → the download with progress →
 * checking the file → Android's installer. A file that does not match the published checksum is deleted and offered again. On Play:
 * 업데이트 → Play's own download with progress → 다시 시작 (completeUpdate).
 */
@Composable
fun UpdateCard(s: UpdateState, onGet: () -> Unit, onInstall: () -> Unit, modifier: Modifier = Modifier) {
    if (s.offer == null) return
    val c = Kelo.colors
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(c.infoSoft).semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(KeloIcons.ArrowDown, contentDescription = null, tint = c.infoInk, modifier = Modifier.size(20.dp))
            Text(offerText(s), color = c.infoInk, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 10.dp, end = 4.dp))
            when (s.phase) {
                UpdatePhase.IDLE, UpdatePhase.FAILED, UpdatePhase.MISMATCH -> TextButton(onClick = onGet) { Text(actionWord(s), color = c.accent) }
                UpdatePhase.READY -> TextButton(onClick = onInstall) { Text(actionWord(s), color = c.accent) }
                else -> {}
            }
        }
        val line = when (s.phase) {
            UpdatePhase.DOWNLOADING -> string(Res.string.update_downloading, s.progress)
            UpdatePhase.VERIFYING -> string(Res.string.update_verifying)
            UpdatePhase.READY -> if (s.play) null else string(Res.string.update_ready)   // Play: the first line already says it
            UpdatePhase.MISMATCH -> string(Res.string.update_mismatch)
            UpdatePhase.FAILED -> s.failureDetail ?: string(Res.string.update_failed)
            UpdatePhase.IDLE -> null
        }
        if (s.phase == UpdatePhase.DOWNLOADING || s.phase == UpdatePhase.VERIFYING) {
            if (s.phase == UpdatePhase.DOWNLOADING) {
                LinearProgressIndicator(progress = { s.progress / 100f }, color = c.accent, trackColor = c.line,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, end = 8.dp))
            } else {
                LinearProgressIndicator(color = c.accent, trackColor = c.line, modifier = Modifier.fillMaxWidth().padding(top = 10.dp, end = 8.dp))
            }
        }
        if (line != null) {
            val bad = s.phase == UpdatePhase.MISMATCH || s.phase == UpdatePhase.FAILED
            Text(line, color = if (bad) c.danger else c.infoInk, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp, end = 8.dp))
        }
        // an app that must be left before its installer runs says so (null on the phone)
        if (s.phase == UpdatePhase.READY) s.installNote?.let { note ->
            Text(note, color = c.infoInk, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp, end = 8.dp))
        }
    }
}

/** Android does not yet let Kelo hand an APK to the installer: say why, then open that one switch in the system settings. */
@Composable
fun InstallPermissionDialog(onOpen: () -> Unit, onDismiss: () -> Unit) {
    val c = Kelo.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = { Text(string(Res.string.update_allow_title), color = c.ink, style = MaterialTheme.typography.titleMedium) },
        text = { Text(string(Res.string.update_allow_body), color = c.muted, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onOpen) { Text(string(Res.string.update_allow_open), color = c.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(string(Res.string.cancel), color = c.ink) } },
    )
}

/**
 * The server says this build is too old (`min_app_version`): this screen covers the app until it is updated. Play starts its own
 * full-screen update first (an immediate update); this screen is what stays when that is closed or Play cannot offer one (the button
 * then opens Kelo in the Play Store). [onLeave] is what back does. The file builds show their own download here, with the card's progress.
 */
@Composable
fun UpdateRequiredScreen(s: UpdateState, onGet: () -> Unit, onInstall: () -> Unit, onLeave: () -> Unit, modifier: Modifier = Modifier) {
    val c = Kelo.colors
    chat.kelo.app.ui.platform.LocalBackHandler.current(onLeave)   // back leaves the app instead of moving underneath
    androidx.compose.material3.Surface(modifier.fillMaxSize(), color = c.bg) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(KeloIcons.ArrowDown, contentDescription = null, tint = c.accent, modifier = Modifier.size(40.dp))
            Text(string(Res.string.update_required_title), color = c.ink, style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp).semantics { heading() })
            Text(string(Res.string.update_required_body), color = c.muted, style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp).padding(top = 8.dp))
            if (s.running.isNotBlank()) {
                Text(string(Res.string.my_version, s.running), color = c.muted, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp))
            }
            // a download under way (or done, failed) shows the same card as 마이; otherwise one plain button
            if (s.offer != null && s.phase != UpdatePhase.IDLE) {
                UpdateCard(s, onGet = onGet, onInstall = onInstall, modifier = Modifier.widthIn(max = 420.dp).padding(top = 24.dp))
            } else {
                chat.kelo.app.ui.components.PrimaryButton(
                    string(if (s.checking) Res.string.update_checking else Res.string.update_now), onGet, enabled = !s.checking,
                    modifier = Modifier.widthIn(max = 420.dp).padding(top = 24.dp),
                )
                if (s.checkFailed) {
                    Text(string(Res.string.update_check_failed), color = c.danger, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}
