package chat.kelo.app.my

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.chat.ModelCatalog
import chat.kelo.app.chat.ModelChoice
import chat.kelo.app.chat.ModelList
import chat.kelo.app.chat.ModelRules
import chat.kelo.app.chat.ProviderConsentRules
import chat.kelo.app.chat.SignOutRules
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import chat.kelo.app.core.Profile
import chat.kelo.app.core.Sessions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The sentences the model page's actions put into its state (the page resolves them in the app's language). */
class ModelTexts(
    val listFailed: String,
    val installed: String,
    val chooseModel: String,
    val urlProblems: Map<EndpointRules.Problem, String>,
    val endpointNameTaken: String = "",
    val endpointTooMany: String = "",
    val endpointInUse: String = "",
)

/** Everything the model page shows and edits. */
data class ModelUiState(
    val provider: String,
    val model: String,
    val effort: String,
    val effortTouched: Boolean = false,
    val fallback: ModelChoice,
    val fallbackCleared: Boolean = false,
    val background: ModelChoice,
    val backgroundState: BackgroundModelState? = null,
    val providers: List<ProviderDetail> = emptyList(),
    val models: ModelList? = null,
    val loadingModels: Boolean = false,
    /** a refresh over ModelCatalog.SLOW_MS: the refresh link says it is loading */
    val slowModels: Boolean = false,
    val customModel: Boolean = false,
    val saving: Boolean = false,
    val error: String = "",
    /** an unofficial provider the owner has not agreed to yet: the page asks, then calls [ModelSettingsViewModel.save] again */
    val consentFor: ProviderDetail? = null,
    val keyInput: String = "",
    val keyMessage: String = "",
    val installing: Boolean = false,
    val installMessage: String = "",
    /** the CLI provider being updated, and how the last update went (provider name, outcome) */
    val updating: String? = null,
    val updateOutcome: Pair<String, CliUpdateRules.Outcome>? = null,
    /** a CLI that can be installed was selected: offer to install it */
    val askInstall: ProviderDetail? = null,
    val signOut: Pair<String, SignOutRules.Outcome>? = null,
    val signOutDetail: String = "",
    val urlInput: String = "",
    val editingUrl: Boolean = false,
    val urlMessage: String = "",
    val savingUrl: Boolean = false,
    val endpointMessage: String = "",
    val savingEndpoint: Boolean = false,
)

/**
 * 마이 → 모델: which AI service and model answer, the fallback and background models, the API key or server address of the
 * service, installing and updating its CLI. The provider and model lists kept on the phone show at once and are refreshed in place.
 */
class ModelSettingsViewModel(
    private val sessions: Sessions,
    profile: Profile,
    private val texts: ModelTexts,
) : ViewModel() {
    private val kept = ModelCatalog.keptList(sessions, profile.provider)
    private val _state = MutableStateFlow(
        ModelUiState(
            provider = profile.provider, model = profile.model, effort = profile.effort,
            fallback = ModelRules.initialFallback(profile),
            background = ModelChoice(profile.backgroundProvider, profile.backgroundModel),
            providers = ModelCatalog.keptProviders(sessions),
            models = kept,
            customModel = kept != null && profile.model.isNotEmpty() && profile.model !in kept.models,
        ),
    )
    val state: StateFlow<ModelUiState> = _state.asStateFlow()
    private val asked = mutableSetOf<String>()
    private var loading: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch { loadProviders() }
        loadModels(profile.provider)
        viewModelScope.launch {   // the background model's last failure, if any (an older server has no such page: nothing shows)
            runCatching { sessions.requireApi().get<UsageSummary>("/api/usage/summary?days=1") }.onSuccess { s -> _state.update { it.copy(backgroundState = s.background) } }
        }
    }

    private val provider get() = _state.value.provider

    private suspend fun loadProviders() {
        runCatching { ModelCatalog.fetchProviders(sessions) }.onSuccess { list ->
            _state.update { it.copy(providers = list) }
            offerInstall(list)
        }
    }

    /** Selecting a CLI provider that can be installed but is not: offer to install it (once per selection). */
    private fun offerInstall(list: List<ProviderDetail>) {
        val p = list.find { it.name == provider }
        val cli = p?.cli
        if (cli != null && !cli.installed && cli.installable && p.name !in asked) {
            asked += p.name
            _state.update { it.copy(askInstall = p) }
        }
    }

    fun loadModels(p: String, refresh: Boolean = false) {
        if (p.isEmpty()) { _state.update { it.copy(models = null) }; return }
        val keptList = ModelCatalog.keptList(sessions, p)
        // another provider: its own last list at once, never the previous one's
        _state.update { if (it.models?.provider != p) it.copy(models = keptList, loadingModels = true) else it.copy(loadingModels = true) }
        viewModelScope.launch {
            val hint = launch { delay(ModelCatalog.SLOW_MS); if (p == provider) _state.update { it.copy(slowModels = true) } }
            try {
                val m = ModelCatalog.fetch(sessions, p, refresh)
                if (p != provider) return@launch          // the user picked another provider meanwhile
                val shown = ModelCatalog.shown(m, keptList) ?: m
                _state.update { it.copy(models = shown, customModel = shown.models.isEmpty() || (it.model.isNotEmpty() && it.model !in shown.models)) }
            } catch (_: ApiError) {
                if (p == provider) _state.update { it.copy(models = ModelCatalog.shown(ModelList(p, emptyList(), error = texts.listFailed), keptList)) }
            } finally {
                hint.cancel()
                if (p == provider) _state.update { it.copy(loadingModels = false, slowModels = false) }
            }
        }
    }

    fun refreshModels() { if (!_state.value.loadingModels) loadModels(provider, refresh = true) }

    fun pickProvider(v: String) {
        // the main model and the fallback never come from the same provider
        val s = _state.value
        val (next, cleared) = ModelRules.afterMainChange(ModelChoice(v, s.model), s.fallback)
        _state.update {
            var n = it.copy(fallback = next, fallbackCleared = cleared, provider = v)
            if (v != it.provider) {
                // another provider offers other models: the old name must not stay behind
                n = n.copy(urlInput = "", editingUrl = false, urlMessage = "", model = "", customModel = false, models = ModelCatalog.keptList(sessions, v))
            }
            n
        }
        if (v != s.provider) loadModels(v)
    }

    fun pickModel(v: String) = _state.update { it.copy(model = v, customModel = false) }
    fun chooseCustomModel() = _state.update { it.copy(customModel = true) }
    fun typeModel(v: String) = _state.update { it.copy(model = v) }
    fun pickEffort(v: String) = _state.update { it.copy(effort = v, effortTouched = true) }
    fun pickFallback(v: ModelChoice) = _state.update { it.copy(fallbackCleared = false, fallback = v) }
    fun pickBackground(v: ModelChoice) = _state.update { it.copy(background = v) }

    fun install(p: ProviderDetail) {
        _state.update { it.copy(installing = true, installMessage = "") }
        viewModelScope.launch {
            try {
                sessions.requireApi().post<JsonElement>("/api/providers/${KeloApi.segment(p.name)}/install")
                _state.update { it.copy(installMessage = texts.installed) }
            } catch (e: ApiError) {
                _state.update { it.copy(installMessage = e.detail) }
            } finally {
                _state.update { it.copy(installing = false) }
                loadProviders()
            }
        }
    }

    fun dismissInstall() = _state.update { it.copy(askInstall = null) }

    /** 업데이트 in the CLI box (owner only): the sidecar installs the newer CLI into its volume; the old one keeps working on failure. */
    fun updateCli(p: ProviderDetail) {
        if (_state.value.updating != null) return
        _state.update { it.copy(updating = p.name, updateOutcome = null) }
        viewModelScope.launch {
            try {
                val st = CliUpdateRules.run(sessions.requireApi(), p.name)
                _state.update { it.copy(updateOutcome = p.name to CliUpdateRules.outcome(st, p.displayName)) }
            } finally {
                _state.update { it.copy(updating = null) }
                loadProviders()
            }
        }
    }

    fun typeKey(v: String) = _state.update { it.copy(keyInput = v) }

    fun saveKey() {
        _state.update { it.copy(keyMessage = "") }
        val p = provider
        viewModelScope.launch {
            try {
                sessions.requireApi().put<JsonElement>("/api/providers/${KeloApi.segment(p)}/key", buildJsonObject { put("api_key", _state.value.keyInput.trim()) })
                _state.update { it.copy(keyInput = "", keyMessage = "") }
                loadProviders()   // the box then shows the key as set (no field)
                loadModels(p, refresh = true)
            } catch (e: ApiError) {
                _state.update { it.copy(keyMessage = e.detail) }
            }
        }
    }

    fun removeKey() {
        val p = provider
        viewModelScope.launch {
            runCatching { sessions.requireApi().delete<JsonElement>("/api/providers/${KeloApi.segment(p)}/key") }
            _state.update { it.copy(keyMessage = "") }
            loadProviders()
            loadModels(p, refresh = true)
        }
    }

    fun typeUrl(v: String) = _state.update { it.copy(urlInput = v, urlMessage = "") }
    fun changeUrl(current: String) = _state.update { it.copy(urlInput = current, urlMessage = "", editingUrl = true) }
    fun cancelUrl() = _state.update { it.copy(editingUrl = false, urlInput = "", urlMessage = "") }

    fun saveUrl() {
        val input = _state.value.urlInput
        val problem = EndpointRules.check(input)
        if (problem != null) { _state.update { it.copy(urlMessage = texts.urlProblems[problem].orEmpty()) }; return }
        val p = provider
        _state.update { it.copy(urlMessage = "", savingUrl = true) }
        viewModelScope.launch {
            try {
                sessions.requireApi().put<JsonElement>("/api/providers/${KeloApi.segment(p)}/endpoint", buildJsonObject { put("base_url", input.trim()) })
                _state.update { it.copy(urlInput = "", editingUrl = false) }
                loadProviders()                     // the box then shows the address as set (no field)
                loadModels(p, refresh = true)       // the models come from that server
            } catch (e: ApiError) {
                _state.update { it.copy(urlMessage = e.detail) }
            } finally {
                _state.update { it.copy(savingUrl = false) }
            }
        }
    }

    fun clearEndpointMessage() = _state.update { it.copy(endpointMessage = "") }

    fun addEndpoint(name: String, baseUrl: String, onDone: () -> Unit = {}) {
        val trimmedName = name.trim()
        val trimmedUrl = baseUrl.trim()
        val problem = EndpointRules.check(trimmedUrl)
        if (problem != null) {
            _state.update { it.copy(endpointMessage = texts.urlProblems[problem].orEmpty()) }
            return
        }
        _state.update { it.copy(endpointMessage = "", savingEndpoint = true) }
        viewModelScope.launch {
            try {
                val created = sessions.requireApi().post<JsonObject>(
                    "/api/providers/custom-endpoints",
                    buildJsonObject {
                        put("name", trimmedName)
                        put("base_url", trimmedUrl)
                    },
                )
                val id = created["id"]?.let { (it as? JsonPrimitive)?.content } ?: ""
                _state.update { it.copy(endpointMessage = "") }
                loadProviders()
                if (id.isNotEmpty()) {
                    pickProvider(id)
                }
                onDone()
            } catch (e: ApiError) {
                val msg = when (e.code) {
                    "name_taken" -> texts.endpointNameTaken.ifEmpty { e.detail }
                    "too_many" -> texts.endpointTooMany.ifEmpty { e.detail }
                    else -> e.detail
                }
                _state.update { it.copy(endpointMessage = msg) }
            } finally {
                _state.update { it.copy(savingEndpoint = false) }
            }
        }
    }

    fun renameEndpoint(id: String, newName: String, onDone: () -> Unit = {}) {
        val trimmed = newName.trim()
        _state.update { it.copy(endpointMessage = "") }
        viewModelScope.launch {
            try {
                if (id == "custom") {
                    sessions.requireApi().put<JsonElement>(
                        "/api/providers/custom/name",
                        buildJsonObject { put("name", trimmed) },
                    )
                } else {
                    sessions.requireApi().patch<JsonElement>(
                        "/api/providers/custom-endpoints/${KeloApi.segment(id)}",
                        buildJsonObject { put("name", trimmed) },
                    )
                }
                _state.update { it.copy(endpointMessage = "") }
                loadProviders()
                onDone()
            } catch (e: ApiError) {
                val msg = when (e.code) {
                    "name_taken" -> texts.endpointNameTaken.ifEmpty { e.detail }
                    else -> e.detail
                }
                _state.update { it.copy(endpointMessage = msg) }
            }
        }
    }

    fun deleteEndpoint(id: String, onDone: () -> Unit = {}) {
        _state.update { it.copy(endpointMessage = "") }
        viewModelScope.launch {
            try {
                sessions.requireApi().delete<JsonElement>("/api/providers/custom-endpoints/${KeloApi.segment(id)}")
                _state.update { it.copy(endpointMessage = "") }
                loadProviders()
                if (provider == id) {
                    pickProvider("")
                }
                onDone()
            } catch (e: ApiError) {
                val msg = when (e.code) {
                    "in_use" -> texts.endpointInUse.ifEmpty { e.detail }
                    else -> e.detail
                }
                _state.update { it.copy(endpointMessage = msg) }
            }
        }
    }

    /** The sign-in box finished a sign-in: the box shows the account now. */
    fun signedIn() {
        _state.update { it.copy(signOut = null) }
        viewModelScope.launch { loadProviders(); loadModels(provider, refresh = true) }
    }

    /** The sign-out box answered; [onProfile] gets the profile again when the account was signed out. */
    fun signedOut(name: String, outcome: SignOutRules.Outcome, fallbackCleared: Boolean, detail: String, onProfile: (Profile) -> Unit) {
        _state.update { it.copy(signOut = name to outcome, signOutDetail = detail) }
        if (!SignOutRules.done(outcome)) return
        if (fallbackCleared && _state.value.fallback.provider == name) _state.update { it.copy(fallback = ModelChoice.NONE) }
        viewModelScope.launch {
            loadProviders()   // the box goes away and the row shows the sign-in is needed
            runCatching { sessions.requireApi().get<Profile>("/api/profile") }.onSuccess { p ->
                onProfile(p)
                _state.update { it.copy(background = ModelChoice(p.backgroundProvider, p.backgroundModel)) }   // a background model on that sign-in is emptied
            }
        }
    }

    /** Saves the choice; [onSaved] gets the saved profile (the page then steps back to 마이; an error keeps it open with the reason). */
    fun save(onSaved: (Profile) -> Unit) {
        val s = _state.value
        if (s.saving) return
        _state.update { it.copy(error = "") }
        if (!ModelRules.complete(s.background)) { _state.update { it.copy(error = texts.chooseModel) }; return }   // a background provider needs its model
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val saved = sessions.requireApi().put<Profile>("/api/profile", buildJsonObject {
                    put("provider", s.provider); put("model", s.model)
                    put("fallback_provider", s.fallback.provider); put("fallback_model", s.fallback.model)
                    put("background_provider", s.background.provider); put("background_model", if (s.background.provider.isEmpty()) "" else s.background.model)
                    if (s.effortTouched) put("effort", s.effort)
                })
                onSaved(saved)
            } catch (e: ApiError) {
                // an unofficial provider without the user's agreement: the page asks, then saves again
                val needed = ProviderConsentRules.requiredBy(e)?.let { n -> _state.value.providers.firstOrNull { it.name == n } }
                if (needed != null) _state.update { it.copy(consentFor = needed) } else _state.update { it.copy(error = e.detail) }
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }

    fun consentHandled() = _state.update { it.copy(consentFor = null) }
}
