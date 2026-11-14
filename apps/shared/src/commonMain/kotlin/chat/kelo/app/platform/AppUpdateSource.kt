package chat.kelo.app.platform

import kotlinx.coroutines.flow.StateFlow

/** The app's own updates, as the My page needs them: state, a check now, and the main button. Null in AppServices = the app has none (desktop: its own). */
interface AppUpdateSource {
    val state: StateFlow<chat.kelo.app.update.UpdateState>
    fun check(force: Boolean = false)
    fun get()
    fun install()
}
