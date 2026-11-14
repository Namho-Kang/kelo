package chat.kelo.app.start

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.Caches
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import chat.kelo.app.core.Profile
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The profile of the signed-in server. */
sealed interface ProfileState {
    data object SignedOut : ProfileState
    data object Loading : ProfileState
    data object Failed : ProfileState
    data class Ready(val profile: Profile) : ProfileState
}

/** The first-run gate: connect -> onboarding (no agent name yet) -> the main shell. */
class AppStateViewModel(private val sessions: chat.kelo.app.core.Sessions) : ViewModel() {
    private val _profile = MutableStateFlow<ProfileState>(ProfileState.SignedOut)
    val profile: StateFlow<ProfileState> = _profile.asStateFlow()

    private var loading: Job? = null

    init {
        viewModelScope.launch {
            sessions.api.collect { api -> if (api == null) _profile.value = ProfileState.SignedOut else loadProfile(api) }
        }
    }

    fun retryProfile() {
        sessions.api.value?.let(::loadProfile)
    }

    fun setProfile(profile: Profile) {
        _profile.value = ProfileState.Ready(profile)
        sessions.keep(Caches.PROFILE, profile)
    }

    fun signOut() = sessions.signOut()

    /**
     * The profile kept from last time (if any) shows the main screens at once; the server's answer then replaces it in place. When
     * the server cannot be reached, the kept profile stays (each screen shows its own "could not refresh" hint) instead of an
     * error page.
     */
    private fun loadProfile(api: KeloApi) {
        loading?.cancel()
        val kept = sessions.cached(Caches.PROFILE)?.takeIf { it.agentName.isNotBlank() }
        _profile.value = if (kept != null) ProfileState.Ready(kept) else ProfileState.Loading
        loading = viewModelScope.launch {
            try {
                val fresh = api.get<Profile>("/api/profile")
                sessions.keep(api, Caches.PROFILE, fresh)
                _profile.value = ProfileState.Ready(fresh)
            } catch (e: ApiError) {
                _profile.value = ProfileRules.afterFailure(_profile.value, e.isUnauthorized)
            }
        }
    }
}

object ProfileRules {
    /** A failed profile request: a 401 signs out; otherwise a profile already shown (kept from last time) stays. */
    fun afterFailure(current: ProfileState, unauthorized: Boolean): ProfileState = when {
        unauthorized -> ProfileState.SignedOut
        current is ProfileState.Ready -> current
        else -> ProfileState.Failed
    }

    /** The first screen is known (the splash screen can go): anything but waiting for the profile. */
    fun settled(signedIn: Boolean, state: ProfileState): Boolean = !signedIn || (state != ProfileState.Loading && state != ProfileState.SignedOut)
}
