package chat.kelo.app.connectors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import chat.kelo.app.LocalAppServices
import chat.kelo.app.res.*
import chat.kelo.app.ui.components.ConfirmDialog
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.components.SkeletonLines
import chat.kelo.app.ui.platform.LocalReloadOnReturn
import chat.kelo.app.ui.text.string

/**
 * Google Calendar: the Google sign-in first (only calendar permissions are asked; the same Google account can also hold Gmail), each
 * connected account with its own disconnect, and the service account kept as the other way ([Connector.fallback]).
 * When the operator has not set up the Google client, the server's reason is shown in words and the other way stays open.
 * While new Google sign-ins wait for Google's review ([ConnectorRules.pendingReview] / [GoogleCalendarState.pendingReview]) there is no
 * sign-in button: the note, and the phone calendar as the other way ([onPhoneCalendar] goes to it under 이 기기에서; null where the device
 * has none). Accounts already connected stay, each with its disconnect.
 */
@Composable
internal fun GoogleConnect(c: Connector, onChanged: () -> Unit, onNotify: ((String) -> Unit)?, onPhoneCalendar: (() -> Unit)? = null) {
    val services = LocalAppServices.current
    val vm = viewModel(key = "google-${c.id}") { GoogleConnectViewModel(services.sessions, services.browser) }
    val ui by vm.state.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<GoogleCalendarAccount?>(null) }
    var other by rememberSaveable(c.id) { mutableStateOf(false) }

    // back from Google's page: the button is ready again and the accounts are read again
    LocalReloadOnReturn.current(c.id) { vm.returned() }

    removing?.let { a ->
        ConfirmDialog(
            string(Res.string.gcal_disconnect_confirm, a.address), string(Res.string.connector_disconnect),
            onYes = { vm.remove(a, onChanged) },
            onDismiss = { removing = null },
        )
    }

    val s = ui.google
    if (s == null) {
        if (ui.failed) ErrorText(string(Res.string.connect_failed), small = true) else SkeletonLines(2)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        s.accounts.forEach { a ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ShieldTitle(a.address)
                MutedText(
                    string(
                        when {
                            a.problem -> Res.string.gcal_account_problem
                            a.canWrite -> Res.string.gcal_account_note
                            else -> Res.string.gcal_account_read_only
                        },
                    ),
                    small = true,
                )
                LinkButton(string(Res.string.connector_disconnect), onClick = { removing = a }, danger = true)
            }
        }
        if (s.accounts.isNotEmpty() && onNotify != null) {
            val text = string(Res.string.chat_connect_notify, c.name)
            PrimaryButton(string(Res.string.notify_agent), onClick = { onNotify(text) })
        }
        val pending = ConnectorRules.pendingReview(c) || s.pendingReview
        if (pending) {
            if (s.accounts.isEmpty() || s.accounts.any { it.problem }) {
                ReviewNote(c, onOpened = { vm.load(); onChanged() })
                MutedText(string(Res.string.google_review_phone_calendar_help), small = true)
                if (onPhoneCalendar != null) LinkButton(string(Res.string.google_review_phone_calendar), onClick = onPhoneCalendar)
            }
        } else if (s.accounts.isEmpty()) BoldLine(string(Res.string.gcal_title))
        if (pending) {
            // no Google sign-in button until the review is done
        } else if (s.available) {
            val label = when {
                ui.busy -> string(Res.string.connector_opening)
                s.accounts.isEmpty() -> string(Res.string.gcal_sign_in)
                s.accounts.any { it.problem } -> string(Res.string.gcal_sign_in_again)
                else -> string(Res.string.gcal_add_account)
            }
            if (s.accounts.isEmpty() || s.accounts.any { it.problem }) PrimaryButton(label, enabled = !ui.busy, onClick = vm::signIn)
            else SecondaryButton(label, onClick = vm::signIn, enabled = !ui.busy)
            if (s.accounts.isEmpty()) MutedText(string(Res.string.gcal_help), small = true)
        } else if (s.accounts.isEmpty()) {
            MutedText(s.reason.ifBlank { string(Res.string.gcal_not_ready) }, small = true)
        }
        ConnectErrorLine(ui.error)

        val fb = c.fallback
        if (fb != null && fb.connect == "key" && (!pending || fb.connected)) {
            if (other || fb.connected) {
                BoldLine(string(Res.string.gcal_other_way))
                KeyConnect(c.copy(connect = "key", via = fb.via, item = fb.item, description = fb.blurb), onChanged, null)
            } else {
                LinkButton(string(Res.string.gcal_other_way_open), onClick = { other = true })
            }
        }
        OperatorSettings("google", onSaved = vm::load)
    }
}
