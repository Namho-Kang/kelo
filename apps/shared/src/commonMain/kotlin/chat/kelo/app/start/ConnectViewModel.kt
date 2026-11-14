package chat.kelo.app.start

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.AppServices
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.AppVersion
import chat.kelo.app.core.PairingClient
import chat.kelo.app.core.PairingLink
import chat.kelo.app.core.PairingLinks
import chat.kelo.app.core.ServerAddress
import chat.kelo.app.core.Sessions
import chat.kelo.app.core.UiText
import chat.kelo.app.res.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConnectState(
    /** A pairing link is being used: the whole screen says "이 기기를 연결하는 중…". */
    val pairing: Boolean = false,
    /** Why the last pairing link did not work, shown at the top. */
    val notice: UiText? = null,
    val linkError: UiText? = null,
    val keyBusy: Boolean = false,
    val keyError: UiText? = null,
    /** A valid pairing link waiting for "이 서버에 연결할까요?": nothing is sent before the user sees which server it is. */
    val confirm: PairingLink? = null,
)

/** Signs this device in: with a pairing link (scanned or pasted) or with a server address and the access key. */
class ConnectViewModel(
    private val sessions: Sessions,
    private val pairing: PairingClient,
    private val deviceName: () -> String,
    private val allowLocalHttp: Boolean,
    initialLink: String = "",
) : ViewModel() {
    constructor(services: AppServices, initialLink: String = "") :
        this(services.sessions, services.pairing, services::deviceName, services.allowLocalHttp, initialLink)

    private val _state = MutableStateFlow(ConnectState())
    val state: StateFlow<ConnectState> = _state.asStateFlow()

    init { if (initialLink.isNotBlank()) useLink(initialLink) }   // a link the system handed over at start (the desktop's pairing link)

    /** The address this device used before, to pre-fill the key form. */
    val lastServerUrl: String = sessions.lastServerUrl().orEmpty()

    /** A scanned or pasted link. Returns false (and shows why) when it is not a Kelo pairing link. */
    fun useLink(raw: String): Boolean {
        val link = PairingLinks.parse(raw)
        if (link == null) {
            _state.update { it.copy(linkError = UiText.Res(Res.string.connect_link_invalid)) }
            return false
        }
        if (!ServerAddress.isAllowed(link.serverUrl, allowLocalHttp)) {
            _state.update { it.copy(linkError = UiText.Res(Res.string.connect_https_only)) }
            return false
        }
        if (_state.value.pairing) return true
        _state.update { it.copy(confirm = link, notice = null, linkError = null) }
        return true
    }

    fun cancelPair() = _state.update { it.copy(confirm = null) }

    /** The user confirmed the server shown in the dialog. */
    fun confirmPair() {
        val link = _state.value.confirm ?: return
        if (_state.value.pairing) return
        _state.update { it.copy(confirm = null, pairing = true) }
        viewModelScope.launch {
            try {
                sessions.signIn(pairing.pair(link, deviceName()))
                _state.update { it.copy(pairing = false) }
            } catch (e: ApiError) {
                // the server's own words (an expired or used link), "could not reach the server", or a server too old (no KE2E)
                val why = when {
                    e.code == PairingClient.CODE_SERVER_OLD -> serverOld()
                    e.isNetwork -> UiText.Res(Res.string.connect_failed)
                    else -> UiText.Raw(e.detail)
                }
                _state.update { it.copy(pairing = false, notice = why) }
            }
        }
    }

    fun connectWithKey(address: String, key: String) {
        val server = ServerAddress.normalize(address)
        when {
            server == null -> _state.update { it.copy(keyError = UiText.Res(Res.string.connect_server_invalid)) }
            !ServerAddress.isAllowed(server, allowLocalHttp) -> _state.update { it.copy(keyError = UiText.Res(Res.string.connect_https_only)) }
            else -> {
                _state.update { it.copy(keyBusy = true, keyError = null) }
                viewModelScope.launch {
                    try {
                        // an access key, or a sign-in code typed in its place (KeySignIn: tried through POST /api/pair on a 401)
                        val (session, _) = KeySignIn.connect(pairing, server, key, deviceName())
                        sessions.signIn(session)
                        _state.update { it.copy(keyBusy = false) }
                    } catch (e: ApiError) {
                        val why = when {
                            e.code == PairingClient.CODE_SERVER_OLD -> serverOld()
                            e.isUnauthorized -> UiText.Res(Res.string.connect_key_wrong)
                            else -> UiText.Res(Res.string.connect_failed)
                        }
                        _state.update { it.copy(keyBusy = false, keyError = why) }
                    }
                }
            }
        }
    }

    fun clearLinkError() = _state.update { it.copy(linkError = null) }

    /** The server offers no end-to-end encryption: it must be updated first (this app never talks in the clear, spec 5.18). */
    private fun serverOld(): UiText =
        UiText.Res(Res.string.e2e_server_old_body, listOf(AppVersion.minServer))

    companion object {
        /** A pairing code the server could accept (PairIn: 16-128 characters, no spaces). */
        /** A typed code may be split by single spaces ("ABCD EFGH ..."), as people copy it from notes; the server ignores them. */
        fun codeOf(text: String): String = text.trim().replace(" ", "")

        fun looksLikeCode(text: String): Boolean = codeOf(text).let { t ->
            t.length in PairingLinks.CODE_LENGTH && t.none { it.isWhitespace() || it.isISOControl() }
        }
    }
}
