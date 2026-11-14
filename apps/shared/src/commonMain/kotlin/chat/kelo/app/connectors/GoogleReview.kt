package chat.kelo.app.connectors

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.res.*
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.text.string
import chat.kelo.app.ui.theme.Kelo

/** The neutral "준비 중" pill of a Google row whose sign-in waits for Google's review (in place of "연결하기"). */
@Composable
fun ReviewPill(modifier: Modifier = Modifier) {
    val c = Kelo.colors
    Text(
        string(Res.string.google_review_pill),
        modifier = modifier.clip(RoundedCornerShape(99.dp)).background(c.neutralSoft).padding(horizontal = 9.dp, vertical = 3.dp),
        color = c.neutralInk, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

/**
 * Why there is no Google sign-in button yet, in Kelo's words (the server's [Connector.note], else the app's own), and the support link
 * once the operator has set one ([Connector.supportUrl]; nothing is shown while it is empty). Below it, the way to ask to be let in
 * early ([BetaRequest]); [onOpened] reloads the screen once that request is approved.
 */
@Composable
fun ReviewNote(c: Connector, onOpened: () -> Unit = {}) {
    val browser = LocalAppServices.current.browser
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoldLine(string(Res.string.google_review_title))
        MutedText(c.note.ifBlank { string(Res.string.google_review_note) }, small = true)
        if (c.supportUrl.startsWith("https://")) {
            LinkButton(string(Res.string.google_review_support), onClick = { browser.open(c.supportUrl) })
        }
        BetaRequest(c, onOpened)
    }
}
