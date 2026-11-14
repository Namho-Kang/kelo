package chat.kelo.app.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel

/**
 * Where a screen's ViewModel lives. The Android root provides the activity's own store (the state survives a rotation and a visit to
 * another page); the default keeps it as long as the screen is in the composition, which is what the desktop window needs.
 */
interface ViewModelHost {
    @Composable fun <T : ViewModel> obtain(key: String, create: () -> T): T
}

private object RememberedViewModels : ViewModelHost {
    @Composable override fun <T : ViewModel> obtain(key: String, create: () -> T): T = remember(key) { create() }
}

val LocalViewModelHost = staticCompositionLocalOf<ViewModelHost> { RememberedViewModels }

/** The screen's ViewModel: `val vm = keloViewModel("vault") { VaultViewModel(sessions) }`. A different [key] is a different ViewModel. */
@Composable
fun <T : ViewModel> keloViewModel(key: String, create: () -> T): T = LocalViewModelHost.current.obtain(key, create)
