package chat.kelo.app.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** A view model store of its own for one page, as a [ViewModelStoreOwner]. */
class PageViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

/**
 * The owner of the view models a page makes: they are kept while the page stays in the composition (per [key]) and cleared when it
 * leaves, so a page that is opened again makes new ones. Provide it with `LocalViewModelStoreOwner provides` around the page.
 */
@Composable
fun rememberPageViewModelStoreOwner(key: Any): ViewModelStoreOwner {
    val owner = remember(key) { PageViewModelStoreOwner() }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    return owner
}
