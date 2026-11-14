package chat.kelo.app.chat

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.KeloModal
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.theme.Kelo

/**
 * The one question before chats first go to an AI service ([AiConsent]): what goes where, the service chosen now, the privacy
 * policy, and Agree / Not now. Dismissing it is Not now: nothing is sent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentSheet(ask: ConsentAsk, busy: Boolean, error: String?, onAgree: () -> Unit, onNotNow: () -> Unit) {
    val c = Kelo.colors
    val uri = LocalUriHandler.current
    KeloModal(onDismiss = { if (!busy) onNotNow() }, modifier = Modifier.testTag("ai_consent")) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(string(Res.string.ai_consent_title), style = MaterialTheme.typography.titleMedium, color = c.ink,
                modifier = Modifier.semantics { heading() })
            Text(string(Res.string.ai_consent_body), style = MaterialTheme.typography.bodyMedium, color = c.ink)
            if (ask.need.company.isNotBlank()) MutedText(string(Res.string.ai_consent_now, ask.need.company), small = true)
            MutedText(string(Res.string.ai_consent_not_now_note), small = true)
            LinkButton(string(Res.string.ai_consent_privacy), onClick = { runCatching { uri.openUri(ask.need.privacyUrl) } })
            error?.let { ErrorText(it) }
            PrimaryButton(string(Res.string.ai_consent_agree), onClick = onAgree, enabled = !busy, modifier = Modifier.fillMaxWidth())
            SecondaryButton(string(Res.string.ai_consent_not_now), onClick = onNotNow, enabled = !busy, modifier = Modifier.fillMaxWidth())
        }
    }
}
