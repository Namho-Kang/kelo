package chat.kelo.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.update.Release
import chat.kelo.app.update.UpdatePhase
import chat.kelo.app.update.UpdateState
import chat.kelo.app.update.checkValue
import chat.kelo.app.res.*
import chat.kelo.app.ui.text.string
import chat.kelo.desktop.core.AppContainer
import chat.kelo.desktop.core.UpdateState as DesktopUpdateState
import kotlinx.coroutines.launch

/*
 * The update screens are the shared ones (chat.kelo.app.update.UpdateUi); this file only maps the desktop updater's own state
 * (download, check and open the installer, see core/Updates.kt) onto the shared [UpdateState] and wires the buttons to it.
 */

/** The updater's state as the shared screens read it: an opened installer is "ready", a failed download or open is "failed". */
internal fun DesktopUpdateState.toShared(running: String, bannerSeen: String, required: Boolean, quitNote: String? = null, openFailed: String? = null): UpdateState = UpdateState(
    enabled = true,
    running = running,
    checking = phase == DesktopUpdateState.Phase.CHECKING,
    checked = phase !in setOf(DesktopUpdateState.Phase.IDLE, DesktopUpdateState.Phase.CHECKING),
    checkFailed = phase == DesktopUpdateState.Phase.CHECK_FAILED,
    offer = if (newer) build?.let { Release(version = it.version, size = it.size) } else null,
    phase = when (phase) {
        DesktopUpdateState.Phase.DOWNLOADING -> UpdatePhase.DOWNLOADING
        DesktopUpdateState.Phase.VERIFYING -> UpdatePhase.VERIFYING
        DesktopUpdateState.Phase.OPENED -> UpdatePhase.READY
        DesktopUpdateState.Phase.MISMATCH -> UpdatePhase.MISMATCH
        DesktopUpdateState.Phase.DOWNLOAD_FAILED, DesktopUpdateState.Phase.OPEN_FAILED -> UpdatePhase.FAILED
        else -> UpdatePhase.IDLE
    },
    progress = (progress * 100).toInt(),
    bannerSeen = bannerSeen,
    required = required,
    installNote = if (phase == DesktopUpdateState.Phase.OPENED) quitNote else null,
    failureDetail = if (phase == DesktopUpdateState.Phase.OPEN_FAILED) openFailed else null,
)

/** The updater's state for the shared screens, kept up to date. */
@Composable
private fun AppContainer.sharedUpdateState(): UpdateState {
    val raw by updates.state.collectAsStateWithLifecycle()
    val seen by settings.dismissedUpdate.collectAsStateWithLifecycle()
    val required by updates.required.collectAsStateWithLifecycle()
    val quitNote = string(Res.string.desktop_update_quit_note)
    val openFailed = string(Res.string.desktop_update_open_failed, raw.path.orEmpty())
    return remember(raw, seen, required, quitNote, openFailed) { raw.toShared(updates.currentVersion.orEmpty(), seen, required, quitNote, openFailed) }
}

/** The value under My's "업데이트 확인" row. */
@Composable
fun updateRowValue(s: DesktopUpdateState): String? = checkValue(s.toShared("", "", false))

/** The one-time banner over the page: 받기 starts the download and opens My, where the progress shows; either button puts it away. */
@Composable
fun UpdateBanner(app: AppContainer, onOpenMy: () -> Unit, modifier: Modifier = Modifier) {
    val s = app.sharedUpdateState()
    val raw by app.updates.state.collectAsStateWithLifecycle()
    val version = s.offer?.version ?: return
    if (!s.showBanner || raw.phase != DesktopUpdateState.Phase.AVAILABLE) return
    chat.kelo.app.update.UpdateBanner(
        s, modifier = modifier,
        onGet = { app.settings.setDismissedUpdate(version); app.scope.launch { app.updates.download() }; onOpenMy() },
        onInstall = {},
        onClose = { app.settings.setDismissedUpdate(version) },
    )
}

/** My's card while a newer build is known. */
@Composable
fun UpdateCard(app: AppContainer, modifier: Modifier = Modifier) {
    val s = app.sharedUpdateState()
    chat.kelo.app.update.UpdateCard(s, onGet = { app.scope.launch { app.updates.download() } }, onInstall = { app.scope.launch { app.updates.reopen() } }, modifier = modifier)
}

/** The server says this version is too old: this covers the window until Kelo is updated (the button starts this app's own update). */
@Composable
fun UpdateRequiredScreen(app: AppContainer, modifier: Modifier = Modifier) {
    val s = app.sharedUpdateState()
    chat.kelo.app.update.UpdateRequiredScreen(
        s, modifier = modifier,
        onGet = { app.scope.launch { app.updates.updateNow() } },
        onInstall = { app.scope.launch { app.updates.reopen() } },
        onLeave = {},
    )
}
