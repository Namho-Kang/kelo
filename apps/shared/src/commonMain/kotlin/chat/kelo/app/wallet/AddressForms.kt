package chat.kelo.app.wallet

import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Sessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The form of one country as it is now: loaded, loading, or failed with the server's words. */
data class FormState(val form: AddressForm? = null, val failed: String? = null)

/**
 * The address country list and the form of the country on show, loaded through the session (and cached for the process, see
 * [AddressCache]). The shipping view models own one; the pages read [countries] and [form].
 */
internal class AddressForms(private val sessions: Sessions, private val scope: CoroutineScope, private val lang: String) {
    private val _countries = MutableStateFlow(AddressCountries())
    val countries: StateFlow<AddressCountries> = _countries.asStateFlow()
    private val _form = MutableStateFlow(FormState())
    val form: StateFlow<FormState> = _form.asStateFlow()
    private var job: Job? = null
    private var wanted = ""

    /** Loads the country list; [onCountries] hears of it (the owner may pick a starting country). */
    fun start(onCountries: (AddressCountries) -> Unit = {}) {
        scope.launch { runCatching { AddressCache.countries(sessions.requireApi()) }.onSuccess { c -> _countries.value = c; onCountries(c) } }
    }

    /** The form of [country] is wanted: from the cache, else from the server. */
    fun show(country: String) {
        wanted = country
        val shown = _form.value.form
        if (country.isEmpty() || shown?.country.equals(country, ignoreCase = true)) return
        AddressCache.known(country, lang)?.let { known -> _form.update { FormState(known) }; return }
        load(country)
    }

    fun retry() { if (wanted.isNotEmpty()) load(wanted) }

    private fun load(country: String) {
        job?.cancel()
        _form.update { it.copy(failed = null) }
        job = scope.launch {
            try {
                val f = AddressCache.form(sessions.requireApi(), country, lang)
                if (wanted.equals(country, ignoreCase = true)) _form.value = FormState(f)
            } catch (e: ApiError) {
                _form.update { it.copy(failed = e.detail) }
            } catch (e: IllegalStateException) {
                _form.update { it.copy(failed = e.message.orEmpty()) }
            }
        }
    }
}
