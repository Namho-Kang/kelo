package chat.kelo.app.platform

import androidx.compose.runtime.staticCompositionLocalOf

/** A short message that fades by itself (Android: Toast). The app roots provide it; the default shows nothing. */
fun interface Toaster {
    fun show(text: String)
}

val LocalToaster = staticCompositionLocalOf { Toaster {} }

/** Shows each text a view model sends through its notices flow as a toast: `ShowNotices(vm.notices)`. */
@androidx.compose.runtime.Composable
fun ShowNotices(notices: kotlinx.coroutines.flow.Flow<String>) {
    val toaster = LocalToaster.current
    androidx.compose.runtime.LaunchedEffect(notices) { notices.collect { toaster.show(it) } }
}
