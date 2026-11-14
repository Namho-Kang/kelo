package chat.kelo.app.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import chat.kelo.app.chat.vault.CardBox
import chat.kelo.app.chat.vault.SecureCard
import chat.kelo.app.res.*
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.text.string

/** The secure form for a login or a key; it folds into one line once the agent was told, or when a newer request replaced it. */
@Composable
fun VaultCardRow(item: ChatItem.Vault, vm: ChatViewModel) {
    // one box around both forms, so the change of size between them animates
    Box(Modifier.fillMaxWidth().animateContentSize(FoldSpec)) {
        when (item.state) {
            CardState.OPEN -> SecureCard(item.name, onNotify = { text ->
                vm.settleCard(item.key, CardState.DONE)
                vm.send(text)
            })
            CardState.DONE -> CardBox { ResultLine(KeloIcons.Shield, string(Res.string.chat_vault_done)) }
            CardState.CANCELLED -> CardBox { ResultLine(KeloIcons.Close, string(Res.string.chat_card_cancelled), muted = true) }
            CardState.EXPIRED -> CardBox { ResultLine(KeloIcons.Clock, string(Res.string.chat_card_expired), muted = true) }
        }
    }
}
