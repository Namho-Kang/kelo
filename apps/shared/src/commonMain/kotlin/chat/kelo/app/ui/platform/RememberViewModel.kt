package chat.kelo.app.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * A page's view model that lives as long as the page is composed (it is cleared, and its scope cancelled, when the page leaves);
 * `key` makes a new one. Pages that must survive a re-created window keep their view model in the app instead.
 */
@Composable
inline fun <reified T : ViewModel> rememberViewModel(key: Any? = null, crossinline create: () -> T): T {
    val store = remember(key) { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    return remember(store) { ViewModelProvider.create(store, viewModelFactory { initializer { create() } })[T::class] }
}
