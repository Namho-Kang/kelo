package chat.kelo.app.offers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.agent.relText
import chat.kelo.app.res.*
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.text.string
import chat.kelo.app.ui.theme.Kelo
import chat.kelo.app.work.EntryBox

@Composable
fun OfferCard(
    o: Offer,
    starting: Boolean,
    problem: OfferStart?,
    onStart: () -> Unit,
    onDismiss: (OfferReason) -> Unit,
) {
    val c = Kelo.colors
    EntryBox {
        Text(o.sentence, style = MaterialTheme.typography.bodyLarge, color = c.ink)
        if (o.needs.isNotEmpty()) {
            Text(string(Res.string.offer_needs, o.needs.joinToString(", ")), style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        if (o.hasNumbers && o.checkedAt != null) {
            Text(string(Res.string.offer_checked, relText(o.checkedAt)), style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        if (problem is OfferStart.Busy) {
            ErrorText(string(Res.string.offer_busy), small = true)
        } else if (problem is OfferStart.Failed) {
            ErrorText(string(Res.string.offer_failed), small = true)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryButton(string(Res.string.offer_start), onClick = onStart, enabled = !starting)
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                LinkButton(string(Res.string.offer_dismiss) + " ▾", onClick = { menuOpen = true })
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(string(Res.string.offer_reason_not_interested)) },
                        onClick = { menuOpen = false; onDismiss(OfferReason.NOT_INTERESTED) },
                    )
                    DropdownMenuItem(
                        text = { Text(string(Res.string.offer_reason_done)) },
                        onClick = { menuOpen = false; onDismiss(OfferReason.DONE) },
                    )
                    DropdownMenuItem(
                        text = { Text(string(Res.string.offer_reason_later)) },
                        onClick = { menuOpen = false; onDismiss(OfferReason.LATER) },
                    )
                }
            }
        }
    }
}

@Composable
fun OffersList(vm: OffersViewModel, onStarted: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (state.items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.items.forEach { o ->
            OfferCard(
                o = o,
                starting = state.starting == o.id,
                problem = state.problem?.takeIf { it.first == o.id }?.second,
                onStart = { vm.start(o, onStarted) },
                onDismiss = { reason -> vm.dismiss(o, reason) },
            )
        }
    }
}
