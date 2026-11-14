package chat.kelo.app.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.chat.ShippingOutcome
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Sessions
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the address page shows. [country] is the one the form is for (the draft's, else the server's suggestion). */
data class ShippingEditUiState(
    val edit: ShippingEditDraft,
    val countries: AddressCountries = AddressCountries(),
    val form: FormState = FormState(),
    val tried: Boolean = false,
    val busy: Boolean = false,
    val msg: String = "",
) {
    val country: String get() = edit.address.country.ifEmpty { countries.defaultCountry }
    /** The loaded form, when it is the one for [country]. */
    val shownForm: AddressForm? get() = form.form?.takeIf { it.country.equals(country, ignoreCase = true) }
    val problems: Map<String, AddressProblem> get() = shownForm?.let { AddressRules.problems(it, edit.address) } ?: emptyMap()
}

/**
 * 배송지 추가 / 수정: the draft (kept through the wallet's lock by [WalletPlaces], memory only), the country's form, save and delete.
 * [home] is the default nickname in the app's language, [lang] the language tag the forms are asked in.
 */
class ShippingEditViewModel(
    private val sessions: Sessions, private val places: WalletPlaces,
    val profile: ShippingProfile?, private val firstOne: Boolean, private val home: String, lang: String,
) : ViewModel() {
    private val forms = AddressForms(sessions, viewModelScope, lang)
    private val _state = MutableStateFlow(
        ShippingEditUiState(
            places.shipping(profile?.id) ?: ShippingEditDraft(
                profile?.id, profile?.label ?: home,
                profile?.let { AddressRules.draftOf(it) } ?: AddressDraft(),
                makeDefault = profile?.isDefault ?: firstOne,
            ),
        ),
    )
    val state: StateFlow<ShippingEditUiState> = _state.asStateFlow()
    private val _events = Channel<WalletEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        forms.start { c ->
            // a new address starts in the server's suggested country (the last address, else the server's own) once the list is here
            _state.update { s ->
                val e = if (s.edit.address.country.isEmpty() && c.defaultCountry.isNotEmpty()) s.edit.copy(address = s.edit.address.copy(country = c.defaultCountry)) else s.edit
                s.copy(edit = e, countries = c)
            }
            forms.show(_state.value.country)
        }
        viewModelScope.launch { forms.countries.collect { c -> _state.update { it.copy(countries = c) } } }
        viewModelScope.launch {
            forms.form.collect { f ->
                _state.update { s -> s.copy(form = f) }
                f.form?.let { onFormShown(it) }
            }
        }
        forms.show(_state.value.country)
    }

    /** The form of the country on show arrived: show a saved number its country's way, and keep only what this form asks for. */
    private fun onFormShown(form: AddressForm) {
        val s = _state.value
        if (!form.country.equals(s.country, ignoreCase = true)) return
        var e = s.edit
        // a saved number shows in the country's own way next to its calling code, once the form (and so the code) is known
        if (profile != null && !e.touched && e.address.phone == profile.phone) e = e.copy(address = e.address.copy(phone = phoneForField(profile.phone, form)))
        val carried = AddressRules.carry(e.address, form)
        if (carried != e.address) e = e.copy(address = carried)
        if (e != s.edit) _state.update { it.copy(edit = e) }
    }

    fun retryForm() = forms.retry()

    private fun set(d: ShippingEditDraft) {
        _state.update { it.copy(edit = d) }
        places.saveShipping(d)
    }

    fun setLabel(v: String) = set(_state.value.edit.copy(label = v.take(40), touched = true))
    fun setAddress(d: AddressDraft) = set(_state.value.edit.copy(address = d, touched = true))
    fun setMakeDefault(on: Boolean) = set(_state.value.edit.copy(makeDefault = on, touched = true))

    fun setCountry(code: String) {
        val s = _state.value
        if (code.equals(s.country, ignoreCase = true)) return
        // the next country's form: what both forms ask for stays, once it is loaded
        set(s.edit.copy(address = s.edit.address.copy(country = code), touched = true))
        forms.show(code)
    }

    fun save() {
        val s = _state.value
        val f = s.shownForm ?: return
        _state.update { it.copy(tried = true) }
        if (s.problems.isNotEmpty()) { _events.trySend(WalletEvent.Reject); return }
        _state.update { it.copy(busy = true, msg = "") }
        viewModelScope.launch {
            try {
                val calls = ShippingCalls(sessions.requireApi())
                val e = s.edit
                val wantDefault = e.makeDefault && profile?.isDefault != true
                val body = AddressRules.profileBody(f, e.address, e.label.ifBlank { home }, if (wantDefault || profile == null) e.makeDefault else null)
                if (profile == null) calls.create(body) else calls.update(profile.id, body)
                places.saveShipping(null)
                _events.trySend(WalletEvent.Confirm)
                _events.trySend(WalletEvent.Saved)
            } catch (e: ApiError) {
                _state.update { it.copy(msg = e.detail) }
                _events.trySend(WalletEvent.Reject)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun delete() {
        val p = profile ?: return
        _state.update { it.copy(msg = "") }
        viewModelScope.launch {
            try {
                ShippingCalls(sessions.requireApi()).delete(p.id)
                places.saveShipping(null)
                _events.trySend(WalletEvent.Confirm)
                _events.trySend(WalletEvent.Deleted)
            } catch (e: ApiError) {
                _state.update { it.copy(msg = e.detail) }
                _events.trySend(WalletEvent.Reject)
            }
        }
    }
}

/** What the in-chat address card shows from the server: the country list and the form of the country on show, and the send state. */
data class ShippingAskUiState(
    val countries: AddressCountries = AddressCountries(),
    val form: FormState = FormState(),
    val busy: Boolean = false,
    val msg: String = "",
)

/**
 * The in-chat address question (ask_shipping): the country forms and the two answers (send, cancel). What is typed stays in the
 * card (saved state per chat row, so scrolling away keeps it); this holds the server side. [outcomes] gets what the chat folds the card into.
 */
class ShippingAskViewModel(private val sessions: Sessions, private val ask: ShippingAsk, lang: String) : ViewModel() {
    private val forms = AddressForms(sessions, viewModelScope, lang)
    private val _state = MutableStateFlow(ShippingAskUiState())
    val state: StateFlow<ShippingAskUiState> = _state.asStateFlow()
    private val _events = Channel<WalletEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private val _outcomes = Channel<ShippingOutcome>(Channel.BUFFERED)
    val outcomes = _outcomes.receiveAsFlow()

    init {
        forms.start()
        viewModelScope.launch { forms.countries.collect { c -> _state.update { it.copy(countries = c) } } }
        viewModelScope.launch { forms.form.collect { f -> _state.update { it.copy(form = f) } } }
    }

    fun showCountry(country: String) = forms.show(country)
    fun retryForm() = forms.retry()

    /** Sends [draft] for the loaded [form]; [home] names a saved address that got no nickname. */
    fun send(form: AddressForm, draft: AddressDraft, save: Boolean, label: String, payment: String?, home: String) {
        _state.update { it.copy(busy = true, msg = "") }
        val name = if (save) label.ifBlank { home } else ""
        viewModelScope.launch {
            try {
                val answer = ShippingCalls(sessions.requireApi()).answer(ask.formId, AddressRules.answerBody(form, draft, save, name, payment))
                _events.trySend(WalletEvent.Confirm)
                val line = answer.profile?.oneLine?.ifBlank { null } ?: AddressRules.oneLine(answer.formatted)
                _outcomes.trySend(ShippingOutcome.Sent(answer.profile?.label?.ifBlank { null } ?: name, line, payment))
            } catch (e: ApiError) {
                _events.trySend(WalletEvent.Reject)
                if (e.status == 404) _outcomes.trySend(ShippingOutcome.Gone) else _state.update { it.copy(msg = e.detail) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun cancel() {
        _state.update { it.copy(busy = true, msg = "") }
        viewModelScope.launch {
            try {
                ShippingCalls(sessions.requireApi()).cancel(ask.formId)
                _outcomes.trySend(ShippingOutcome.Cancelled)
            } catch (e: ApiError) {
                if (e.status == 404) _outcomes.trySend(ShippingOutcome.Gone) else _state.update { it.copy(msg = e.detail) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
