package chat.kelo.app.start

import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.viewModelScope
import chat.kelo.app.LocalAppServices
import chat.kelo.app.ui.platform.rememberViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Profile
import chat.kelo.app.my.FieldLabel
import chat.kelo.app.ui.components.CenterCard
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The default agent name in the setup form (the app's own name). */
private const val DEFAULT_AGENT_NAME = "Kelo"

data class OnboardingUiState(val busy: Boolean = false, val error: OnboardingProblem? = null)

/** Why the setup form did not save: the server's words, or no answer at all ([network]). */
data class OnboardingProblem(val detail: String, val network: Boolean)

/** The first-run setup form: saves the user's name and the agent's name (PUT /api/profile). */
class OnboardingViewModel(private val sessions: chat.kelo.app.core.Sessions) : androidx.lifecycle.ViewModel() {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(OnboardingUiState())
    val state: kotlinx.coroutines.flow.StateFlow<OnboardingUiState> = _state

    fun save(userName: String, agentName: String, onDone: (Profile) -> Unit) {
        _state.value = OnboardingUiState(busy = true)
        viewModelScope.launch {
            try {
                val profile = sessions.requireApi().put<Profile>("/api/profile", buildJsonObject {
                    put("user_name", userName.trim())
                    put("agent_name", agentName.trim())
                })
                _state.value = OnboardingUiState()
                onDone(profile)
            } catch (e: ApiError) {
                _state.value = OnboardingUiState(error = OnboardingProblem(e.detail, e.isNetwork))
            }
        }
    }
}

/**
 * First visit to a server whose profile has no agent name yet: one short setup form with the user's name and the agent's name
 * (filled in with "Kelo"); both can be changed later in 마이.
 */
@Composable
fun OnboardingScreen(initial: Profile, onDone: (Profile) -> Unit) {
    val services = LocalAppServices.current
    val vm = rememberViewModel("onboarding") { OnboardingViewModel(services.sessions) }
    val ui by vm.state.collectAsStateWithLifecycle()
    var userName by rememberSaveable { mutableStateOf(initial.userName) }
    var agentName by rememberSaveable { mutableStateOf(initial.agentName.ifBlank { DEFAULT_AGENT_NAME }) }
    val focus = LocalFocusManager.current
    val failed = string(Res.string.connect_failed)
    val busy = ui.busy
    val error = ui.error?.let { if (it.network) failed else it.detail }
    val ready = userName.isNotBlank() && agentName.isNotBlank() && !busy

    fun finish() {
        if (ready) vm.save(userName, agentName, onDone)
    }

    CenterCard {
        Text(string(Res.string.onboarding_hello), style = MaterialTheme.typography.headlineMedium)
        MutedText(string(Res.string.onboarding_intro))
        FieldLabel(string(Res.string.onboarding_name_label))
        KeloTextField(
            value = userName, onValueChange = { userName = it }, maxLength = 60,
            placeholder = string(Res.string.onboarding_name_placeholder),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
        )
        FieldLabel(string(Res.string.onboarding_agent_label))
        KeloTextField(
            value = agentName, onValueChange = { agentName = it }, maxLength = 60,
            placeholder = string(Res.string.onboarding_agent_placeholder),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { finish() }),
        )
        MutedText(string(Res.string.onboarding_agent_question), small = true)
        error?.let { ErrorText(it, small = true) }
        PrimaryButton(string(Res.string.onboarding_start), onClick = ::finish, enabled = ready, modifier = Modifier)
    }
}
