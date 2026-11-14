package chat.kelo.app.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Runs [work] only while somebody collects this state (a screen that is in view: `collectAsStateWithLifecycle` stops collecting when the
 * app is stopped), cancels it when the last collector goes, and starts it again when one comes back. For polling a view model must not
 * do in the background.
 */
fun MutableStateFlow<*>.whileObserved(scope: CoroutineScope, work: suspend () -> Unit): Job = scope.launch {
    subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { observed -> if (observed) work() }
}
