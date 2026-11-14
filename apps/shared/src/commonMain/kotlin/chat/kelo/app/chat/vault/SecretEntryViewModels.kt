package chat.kelo.app.chat.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Sessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why a secure entry did not go through: the server's words, or no answer at all ([network]: the card says its own line). */
data class SecretProblem(val detail: String, val network: Boolean)

/** What came of saving a secure entry. */
sealed interface SecretOutcome {
    data class Saved(val answer: SaveAnswer, val result: SecretResult) : SecretOutcome
    /** The server no longer has it open (answered on another phone, or it ran out). */
    data object Gone : SecretOutcome
    data class Failed(val problem: SecretProblem) : SecretOutcome
}

data class SecretEntryUiState(val busy: Boolean = false, val problem: SecretProblem? = null)

/** The chat's secure entry (a key, a login or a card the agent asked for): the calls that save it. The typed value never leaves the composable's field except into these calls. */
class SecretEntryViewModel(private val sessions: Sessions) : ViewModel() {
    private val _state = MutableStateFlow(SecretEntryUiState())
    val state: StateFlow<SecretEntryUiState> = _state.asStateFlow()

    fun saveKey(name: String, value: String, onOutcome: (SecretOutcome) -> Unit) = run(true, onOutcome) {
        val answer = VaultCalls.putSecret(sessions.requireApi(), name, value.trim())
        SecretOutcome.Saved(answer, SecretResult(checked = if (answer.checked) "ok" else "unchecked"))
    }

    fun saveLogin(name: String, user: String, pass: String, mode: VaultMode, onOutcome: (SecretOutcome) -> Unit) = run(true, onOutcome) {
        val answer = VaultCalls.putLogin(sessions.requireApi(), name, user.trim(), pass, mode)
        SecretOutcome.Saved(answer, SecretResult(mode = mode.wire))
    }

    /** The card was saved in the wallet (its own form sealed it): answer the request with it. */
    fun cardSaved(requestId: String, card: SecretCard, onOutcome: (SecretOutcome) -> Unit) = run(false, onOutcome) {
        SecretOutcome.Saved(SecretCalls.cardSaved(sessions.requireApi(), requestId, card.id), SecretResult(card = card))
    }

    private fun run(showBusy: Boolean, onOutcome: (SecretOutcome) -> Unit, call: suspend () -> SecretOutcome) {
        _state.update { SecretEntryUiState(busy = showBusy) }
        viewModelScope.launch {
            val outcome = try {
                call()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                if (e.status == 404) SecretOutcome.Gone else SecretOutcome.Failed(SecretProblem(e.detail, e.isNetwork))
            }
            _state.update { SecretEntryUiState(busy = false, problem = (outcome as? SecretOutcome.Failed)?.problem) }
            onOutcome(outcome)
        }
    }
}

data class SecureFormUiState(val busy: Boolean = false, val error: String = "")

/** The form where a login or a key is typed (My vault, the chat's secure card): saves it to the vault. */
class SecureFormViewModel(private val sessions: Sessions) : ViewModel() {
    private val _state = MutableStateFlow(SecureFormUiState())
    val state: StateFlow<SecureFormUiState> = _state.asStateFlow()

    fun submit(item: VaultItem, user: String, pass: String, mode: VaultMode, onSaved: () -> Unit) {
        _state.value = SecureFormUiState(busy = true)
        viewModelScope.launch {
            try {
                val api = sessions.requireApi()
                if (item.isLogin) VaultCalls.putLogin(api, item.name, user.trim(), pass, mode) else VaultCalls.putSecret(api, item.name, pass.trim())
                _state.value = SecureFormUiState()
                onSaved()
            } catch (e: ApiError) {
                _state.value = SecureFormUiState(error = e.detail)
            }
        }
    }
}

/** [looked]: the server answered; [item] is null then when the request is gone. */
data class SecureCardUiState(val item: VaultItem? = null, val looked: Boolean = false)

/** The card in the chat that asks for a login or a key: the item it is about, and forgetting the request. */
class SecureCardViewModel(private val sessions: Sessions, private val name: String) : ViewModel() {
    private val _state = MutableStateFlow(SecureCardUiState())
    val state: StateFlow<SecureCardUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val item = try { VaultCalls.find(sessions.requireApi(), name) } catch (_: ApiError) { null }
            _state.value = SecureCardUiState(item, looked = true)
        }
    }

    /** Nothing was entered: the request is forgotten, and nothing is left in the vault. */
    fun cancel(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { VaultCalls.cancelDraft(sessions.requireApi(), name) }
            onDone()
        }
    }
}
