package chat.kelo.app.keyguard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.DeviceList
import chat.kelo.app.core.KeloApi
import chat.kelo.app.core.Sessions
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** What went wrong, for the screen to put in words (the server's own message is used when none of these fits). */
enum class KeyguardProblem { NO_LOCK, KEY_LOST, FAILED, LOCKED_OTHER, ENV_KEY, LAST_PHONE, NOT_OWNER, CODE_WRONG }

/** Maps the server's refusals and the device's check results to a [KeyguardProblem] (pure). */
object KeyguardProblems {
    /** null: none of the known ones, show the server's message */
    fun of(e: ApiError): KeyguardProblem? = when {
        e.code == KeyguardRules.CODE_ENV_KEY -> KeyguardProblem.ENV_KEY
        e.code in KeyguardRules.LAST_PHONE_CODES -> KeyguardProblem.LAST_PHONE
        e.status == 403 -> KeyguardProblem.NOT_OWNER
        else -> null
    }

    /** null: it worked, or the owner backed out (nothing to say) */
    fun of(a: KeyguardAuth): KeyguardProblem? = when (a) {
        is KeyguardAuth.Ok, KeyguardAuth.Cancelled -> null
        KeyguardAuth.NoLock -> KeyguardProblem.NO_LOCK
        KeyguardAuth.KeyLost -> KeyguardProblem.KEY_LOST
        is KeyguardAuth.Failed -> KeyguardProblem.FAILED
    }

    /** A refused recovery code */
    fun ofCode(e: ApiError): KeyguardProblem? = if (e.status == 422 || e.status == 400) KeyguardProblem.CODE_WRONG else of(e)

    /** A refused POST /api/keyguard/unlock */
    fun ofUnlock(e: ApiError): KeyguardProblem = if (e.code == KeyguardRules.CODE_WRONG_KEY) KeyguardProblem.KEY_LOST else KeyguardProblem.FAILED
}

/** The words of the owner's check, resolved in composition (the calls run in the view model). */
class KeyguardPrompts(val wrap: String, val title: String, val subtitle: String)

/** Waits for the device's check (it answers through a callback, once). */
internal suspend fun awaitCheck(start: ((KeyguardAuth) -> Unit) -> Unit): KeyguardAuth =
    suspendCancellableCoroutine { cont -> start { a -> if (cont.isActive) cont.resume(a) } }

sealed interface UnlockOutcome {
    data object Unlocked : UnlockOutcome
    data object Cancelled : UnlockOutcome
    data class Refused(val problem: KeyguardProblem) : UnlockOutcome
}

/**
 * Unlocking Kelo with this device's copy (spec 5.14): GET /api/keyguard/blob (KE2E), the owner's check releases the device key, the
 * blob opens here and the key goes to POST /api/keyguard/unlock once; its bytes are wiped.
 */
class KeyguardUnlock(private val sessions: Sessions, private val keys: KeyguardKeys) {
    suspend fun run(title: String, subtitle: String): UnlockOutcome {
        val answer = try { KeyguardCalls.blob(sessions.requireApi()) } catch (e: ApiError) {
            return UnlockOutcome.Refused(if (e.status == 404) KeyguardProblem.LOCKED_OTHER else KeyguardProblem.FAILED)
        }
        val (iv, sealed) = KeyguardBlob.decode(answer.blob) ?: return UnlockOutcome.Refused(KeyguardProblem.LOCKED_OTHER)
        val a = awaitCheck { done -> keys.forUnwrap(iv, title, subtitle, done) }
        if (a !is KeyguardAuth.Ok) return KeyguardProblems.of(a)?.let { UnlockOutcome.Refused(it) } ?: UnlockOutcome.Cancelled
        val key = try { a.cipher.doFinal(sealed) } catch (e: Exception) { return UnlockOutcome.Refused(KeyguardProblem.KEY_LOST) }
        return try {
            KeyguardCalls.unlock(sessions.requireApi(), key)
            sessions.setLocked(false)
            UnlockOutcome.Unlocked
        } catch (e: ApiError) {
            UnlockOutcome.Refused(KeyguardProblems.ofUnlock(e))
        } finally {
            key.fill(0)
        }
    }
}

/** A step that asks "are you sure?" first. */
enum class KeyguardConfirm { TURN_ON, REMOVE, TURN_OFF }

data class KeyguardUiState(
    val view: KeyguardView? = null,
    /** This device's id (GET /api/devices "current") */
    val me: Long? = null,
    val notOwner: Boolean = false,
    val busy: Boolean = false,
    val problem: KeyguardProblem? = null,
    /** The server's own words, when [problem] is null */
    val message: String? = null,
    /** "Kelo is unlocked" under the cards */
    val unlocked: Boolean = false,
    /** Shown ONCE after turning it on; nothing else is on the page until "I wrote it down" */
    val recoveryCode: String? = null,
    val codeOpen: Boolean = false,
    val code: String = "",
    val confirm: KeyguardConfirm? = null,
)

/** 마이 → Security → Keyguard: its state, the phones that hold it, enrol, unlock, recover, remove this phone's copy, turn it off. */
class KeyguardViewModel(
    private val sessions: Sessions,
    private val keys: KeyguardKeys,
    startWithCode: Boolean = false,
    enableNow: Boolean = false,
) : ViewModel() {
    private val _state = MutableStateFlow(KeyguardUiState(codeOpen = startWithCode, confirm = if (enableNow) KeyguardConfirm.TURN_ON else null))
    val state: StateFlow<KeyguardUiState> = _state.asStateFlow()
    private val _enabled = Channel<Unit>(Channel.BUFFERED)

    /** Keyguard is on now (the recovery code was written down, or enrolling turned it on without a new code). */
    val enabled: Flow<Unit> = _enabled.receiveAsFlow()

    private val unlocker = KeyguardUnlock(sessions, keys)

    init { viewModelScope.launch { load() } }

    private suspend fun load() {
        try {
            val api = sessions.requireApi()
            val v = KeyguardCalls.view(api)
            val me = runCatching { api.get<DeviceList>("/api/devices").current }.getOrNull()
            _state.update { it.copy(view = v, me = me) }
            sessions.setLocked(v.locked)
        } catch (e: ApiError) {
            if (e.status == 403) _state.update { it.copy(notOwner = true) } else failed(e)
        }
    }

    private fun begin() = _state.update { it.copy(busy = true, problem = null, message = null, unlocked = false) }

    private fun failed(e: ApiError, problem: KeyguardProblem? = KeyguardProblems.of(e)) =
        _state.update { it.copy(busy = false, problem = problem, message = if (problem == null) e.detail else null) }

    fun ask(c: KeyguardConfirm) = _state.update { it.copy(confirm = c) }
    fun dismissConfirm() = _state.update { it.copy(confirm = null) }

    /** The "are you sure?" was answered yes. */
    fun confirmed(p: KeyguardPrompts) {
        val c = _state.value.confirm ?: return
        _state.update { it.copy(confirm = null) }
        when (c) {
            KeyguardConfirm.TURN_ON -> enroll(p)
            KeyguardConfirm.REMOVE -> _state.value.me?.let { id -> change { api -> KeyguardCalls.removePhone(api, id) } }
            KeyguardConfirm.TURN_OFF -> change { api -> KeyguardCalls.disable(api) }
        }
    }

    /** Turn it on, or add this phone while unlocked: a key from the server, wrapped here after the owner's check, the blob back. */
    fun enroll(p: KeyguardPrompts) {
        if (_state.value.busy || !keys.available) return
        begin()
        viewModelScope.launch {
            val answer = try { KeyguardCalls.enroll(sessions.requireApi()) } catch (e: ApiError) { return@launch failed(e) }
            val a = awaitCheck { done -> keys.forWrap(p.wrap, p.subtitle, done) }
            if (a !is KeyguardAuth.Ok) return@launch _state.update { it.copy(busy = false, problem = KeyguardProblems.of(a)) }
            val plain = answer.key.encodeToByteArray()
            val blob = try {
                KeyguardBlob.encode(a.cipher.iv, a.cipher.doFinal(plain))
            } catch (e: Exception) {
                return@launch _state.update { it.copy(busy = false, problem = KeyguardProblem.FAILED) }
            } finally {
                plain.fill(0)
            }
            try {
                val v = KeyguardCalls.finish(sessions.requireApi(), answer.enrollmentId, blob)
                _state.update { it.copy(busy = false, view = v, recoveryCode = answer.recoveryCode) }
                if (answer.recoveryCode == null && v.on) _enabled.trySend(Unit)
            } catch (e: ApiError) {
                failed(e)
            }
        }
    }

    /** "I wrote it down": the code is forgotten for good. */
    fun recoveryDone() {
        _state.update { it.copy(recoveryCode = null) }
        _enabled.trySend(Unit)
    }

    fun unlock(p: KeyguardPrompts) {
        if (_state.value.busy) return
        begin()
        viewModelScope.launch {
            when (val r = unlocker.run(p.title, p.subtitle)) {
                UnlockOutcome.Unlocked -> { _state.update { it.copy(busy = false, unlocked = true) }; load() }
                UnlockOutcome.Cancelled -> _state.update { it.copy(busy = false) }
                is UnlockOutcome.Refused -> _state.update { it.copy(busy = false, problem = r.problem) }
            }
        }
    }

    fun openCode() = _state.update { it.copy(codeOpen = true) }
    fun typeCode(text: String) = _state.update { it.copy(code = text) }

    /** The recovery code instead of a phone. */
    fun recover() {
        val code = _state.value.code
        if (_state.value.busy || !KeyguardRules.codeReady(code)) return
        begin()
        viewModelScope.launch {
            try {
                KeyguardCalls.recover(sessions.requireApi(), code)
                sessions.setLocked(false)
                _state.update { it.copy(busy = false, unlocked = true, codeOpen = false, code = "") }
                load()
            } catch (e: ApiError) {
                failed(e, KeyguardProblems.ofCode(e))
            }
        }
    }

    /** Remove this phone's copy or turn it off; this device's key goes once the server agreed. */
    private fun change(call: suspend (KeloApi) -> KeyguardView) {
        begin()
        viewModelScope.launch {
            try {
                val v = call(sessions.requireApi())
                keys.forget()
                _state.update { it.copy(busy = false, view = v) }
            } catch (e: ApiError) {
                failed(e)
            }
        }
    }
}

data class UnlockUiState(val busy: Boolean = false, val problem: KeyguardProblem? = null)

/** The "Kelo is locked" banner's unlock with this device. */
class LockedBannerViewModel(sessions: Sessions, keys: KeyguardKeys) : ViewModel() {
    private val unlocker = KeyguardUnlock(sessions, keys)
    private val _state = MutableStateFlow(UnlockUiState())
    val state: StateFlow<UnlockUiState> = _state.asStateFlow()

    fun unlock(p: KeyguardPrompts) {
        if (_state.value.busy) return
        _state.value = UnlockUiState(busy = true)
        viewModelScope.launch {
            val r = unlocker.run(p.title, p.subtitle)
            _state.value = UnlockUiState(problem = (r as? UnlockOutcome.Refused)?.problem)
        }
    }
}
