package chat.kelo.app.shell

import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import chat.kelo.app.ui.platform.LocalAppBackground
import chat.kelo.app.ui.platform.LocalBackHandler
import chat.kelo.app.ui.platform.formFactor
import chat.kelo.app.core.AppVersion
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.theme.Kelo

/**
 * KE2E: the server's key is not the one this device pinned (409 e2e_key_changed, or the live view's close 4409). Nothing more goes to
 * that server until the device is paired again with a new link or QR code from the server machine; this screen covers the app until then.
 */
@Composable
fun PairAgainScreen(onPairAgain: () -> Unit, modifier: Modifier = Modifier) {
    // the computer's text names the link it is joined with; the phone's names the QR code
    val body = formFactor(phone = Res.string.e2e_key_changed_body, desktop = Res.string.desktop_e2e_key_changed_body)
    CoverScreen(KeloIcons.Shield, string(Res.string.e2e_key_changed_title), string(body), extra = null,
        button = string(Res.string.e2e_pair_again), onClick = onPairAgain, modifier = modifier)
}

/**
 * The server is older than this app needs (spec 5.18): X-Kelo-Server below the compiled-in min_server, no X-Kelo-Server at all, or no
 * KE2E key (GET /api/e2e/info). This app never talks to such a server in the clear, so this screen covers the app until the server
 * is updated; "Try again" asks once more.
 */
@Composable
fun ServerTooOldScreen(seen: AppVersion.ServerSeen?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    CoverScreen(KeloIcons.Cloud, string(Res.string.e2e_server_old_title), string(Res.string.e2e_server_old_body, AppVersion.minServer),
        extra = AppVersion.versionOf(seen?.version)?.let { string(Res.string.e2e_server_old_version, it) },
        button = string(Res.string.e2e_retry), onClick = onRetry, modifier = modifier)
}

/** A screen over the whole window that takes every click (the pages under it are never hit); the back gesture leaves the app. */
@Composable
private fun CoverScreen(icon: ImageVector, title: String, body: String, extra: String?, button: String, onClick: () -> Unit, modifier: Modifier) {
    val c = Kelo.colors
    val leave = LocalAppBackground.current
    LocalBackHandler.current { leave() }
    Box(
        modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.safeDrawing)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(40.dp))
            Text(title, color = c.ink, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp).semantics { heading() })
            Text(body, color = c.muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp).padding(top = 8.dp))
            extra?.let { Text(it, color = c.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            PrimaryButton(button, onClick, modifier = Modifier.widthIn(max = 420.dp).padding(top = 24.dp))
        }
    }
}
