package chat.kelo.app.core

import androidx.compose.runtime.Composable
import chat.kelo.app.ui.text.string
import org.jetbrains.compose.resources.StringResource

/** Text decided in a ViewModel: a string resource, or text the server sent (its "detail"). */
sealed interface UiText {
    data class Res(val res: StringResource, val args: List<Any?> = emptyList()) : UiText
    data class Raw(val text: String) : UiText

    @Composable
    fun resolve(): String = when (this) {
        is Res -> string(res, *args.toTypedArray())
        is Raw -> text
    }
}
