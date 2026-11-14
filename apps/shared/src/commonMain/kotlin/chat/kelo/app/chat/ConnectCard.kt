package chat.kelo.app.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import chat.kelo.app.chat.vault.CardBox
import chat.kelo.app.connectors.ConnectPanel
import chat.kelo.app.res.*
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.text.string

/**
 * The assistant asked the user to connect a service (connect_service): the connector's own connect panel, right here in the chat.
 * When it is connected, the panel's "에이전트에게 알리기" tells the agent and the card folds into one line.
 */
@Composable
fun ConnectCard(item: ChatItem.Connect, vm: ChatViewModel) {
    CardBox(Modifier.animateContentSize(FoldSpec)) {
        when (item.state) {
            CardState.OPEN -> ConnectPanel(item.service, header = true, onNotify = { text ->
                vm.settleCard(item.key, CardState.DONE)
                vm.send(text)
            })
            CardState.DONE -> ResultLine(KeloIcons.Check, string(Res.string.chat_connect_done))
            CardState.CANCELLED -> ResultLine(KeloIcons.Close, string(Res.string.chat_card_cancelled), muted = true)
            CardState.EXPIRED -> ResultLine(KeloIcons.Clock, string(Res.string.chat_card_expired), muted = true)
        }
    }
}
