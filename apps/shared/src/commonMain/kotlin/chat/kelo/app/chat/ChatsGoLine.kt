package chat.kelo.app.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import chat.kelo.app.res.*
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.text.string

/** "Your chats go to Google" under the chosen provider (model picker, Model page); nothing when it cannot be told. */
@Composable
fun ChatsGoLine(provider: String?, company: String?, modifier: Modifier = Modifier) {
    val text = when (val go = AiConsent.chatsGo(provider, company)) {
        is ChatsGo.To -> string(Res.string.ai_chats_go_to, go.company)
        ChatsGo.Local -> string(Res.string.ai_chats_stay)
        null -> return
    }
    MutedText(text, small = true, modifier = modifier.testTag("chats_go"))
}
