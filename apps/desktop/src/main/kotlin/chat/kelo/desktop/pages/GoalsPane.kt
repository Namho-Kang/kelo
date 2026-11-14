package chat.kelo.desktop.pages

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import chat.kelo.app.goals.GoalsScreen
import chat.kelo.desktop.core.AppContainer

/** 다짐 is the shared screen (chat.kelo.app.goals.GoalsScreen); in a wide window the open goal sits beside the list. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun GoalsPane(app: AppContainer, topInset: Dp) {
    GoalsScreen()
}
