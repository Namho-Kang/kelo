package chat.kelo.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Makes view models the way a screen does and clears them at the end of the test (`@After fun stop() { vms.clear() }`, before the
 * Main dispatcher is reset): each viewModelScope is cancelled and waited for, so nothing keeps running (or finishes on a Main
 * dispatcher that is gone) in the next test.
 */
class TestViewModels {
    val store = ViewModelStore()
    @PublishedApi internal var count = 0
    @PublishedApi internal val made = mutableListOf<ViewModel>()

    inline fun <reified T : ViewModel> make(crossinline create: () -> T): T =
        ViewModelProvider.create(store, viewModelFactory { initializer { create() } })["vm${count++}", T::class].also { made += it }

    fun clear() {
        store.clear()
        runBlocking { made.forEach { vm -> withTimeoutOrNull(5_000) { vm.viewModelScope.coroutineContext[Job]?.join() } } }
        made.clear()
    }
}
