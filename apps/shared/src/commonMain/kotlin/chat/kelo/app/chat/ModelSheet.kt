package chat.kelo.app.chat

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Profile
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Pick the model and an automatic fallback (PUT /api/profile). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(profile: Profile, onSaved: (Profile) -> Unit, onClose: () -> Unit) {
    val sessions = LocalAppServices.current.sessions
    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf(ModelCatalog.keptProviderInfos(sessions)) }   // the last list at once, refreshed below
    var main by remember { mutableStateOf(ModelChoice(profile.provider, profile.model)) }
    var fb by remember { mutableStateOf(ModelRules.initialFallback(profile)) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    val gate = rememberConsentGate()

    var signOut by remember { mutableStateOf<Pair<String, SignOutRules.Outcome>?>(null) }   // provider name, answer
    var signOutDetail by remember { mutableStateOf("") }

    suspend fun loadProviders() {
        runCatching { ModelCatalog.fetchProviderInfos(sessions) }.onSuccess { providers = it }
    }

    LaunchedEffect(Unit) { loadProviders() }

    /** The account and 로그아웃 under a picker whose provider is signed in, and the answer after signing out. */
    @Composable
    fun SignInLine(choice: ModelChoice) {
        val p = providers.find { it.name == choice.provider }
        if (p != null && SignOutRules.offered(p.signedIn)) {
            SignOutBox(p.name, p.displayName, p.account, profile.provider, quietWhenMain = true) { outcome, result, detail ->
                signOut = p.name to outcome
                signOutDetail = detail
                if (SignOutRules.done(outcome)) {
                    if (result?.fallbackCleared == true && fb.provider == p.name) fb = ModelChoice.NONE
                    scope.launch {
                        loadProviders()
                        runCatching { sessions.requireApi().get<Profile>("/api/profile") }.onSuccess(onSaved)
                    }
                }
            }
        }
        signOut?.takeIf { it.first == choice.provider }?.let { SignOutMessage(it.second, signOutDetail) }
        if (p != null && SignInRules.offered(p.signin, p.signedIn)) {
            SignInBox(p, gate) {
                signOut = null
                scope.launch { loadProviders() }   // the line then shows the account and 로그아웃
            }
        }
    }

    fun apply() {
        busy = true; err = ""
        scope.launch {
            try {
                onSaved(
                    sessions.requireApi().put<Profile>("/api/profile", buildJsonObject {
                        put("provider", main.provider); put("model", main.model.trim())
                        put("fallback_provider", fb.provider); put("fallback_model", fb.model.trim())
                    }),
                )
                onClose()
            } catch (e: ApiError) {
                // an unofficial provider without the user's agreement: ask, then save again
                val needed = ProviderConsentRules.requiredBy(e)?.let { n -> providers.firstOrNull { it.name == n } }
                if (needed != null) gate.request(needed.copy(consent = null)) { apply() } else err = e.detail
            } finally {
                busy = false
            }
        }
    }

    val c = Kelo.colors
    chat.kelo.app.ui.components.KeloModal(onDismiss = onClose) {   // a sheet on the phone, a centred dialog on the desktop
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(string(Res.string.model_sheet_title), style = MaterialTheme.typography.titleMedium, color = c.ink)
            ModelPicker(providers, main, noneLabel = string(if (profile.modelReady == false) Res.string.model_picker_choose_provider else Res.string.model_sheet_server_default), gate = gate, onChange = { v ->
                main = v
                val (next, cleared) = ModelRules.afterMainChange(v, fb)
                fb = next
                note = cleared
            })
            SignInLine(main)
            Row {
                Text(string(Res.string.model_sheet_fallback), style = MaterialTheme.typography.titleMedium, color = c.ink)
            }
            MutedText(string(Res.string.model_sheet_fallback_hint), small = true)
            ModelPicker(providers, fb, noneLabel = string(Res.string.model_sheet_no_fallback), exclude = main.provider, gate = gate, onChange = { v ->
                note = false
                fb = v
            })
            SignInLine(fb)
            if (note) MutedText(string(Res.string.model_sheet_fallback_cleared), small = true)
            if (err.isNotEmpty()) ErrorText(err, small = true)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                SecondaryButton(string(Res.string.cancel), onClick = onClose, modifier = Modifier.weight(1f))
                PrimaryButton(
                    string(Res.string.model_sheet_apply), onClick = ::apply, modifier = Modifier.weight(1f),
                    enabled = !busy && ModelRules.complete(main) && ModelRules.complete(fb),
                )
            }
        }
    }
    ConsentDialog(gate)
}

/**
 * Provider + model in one block; the model list is whatever that provider really offers. Unofficial providers come last, under
 * their own divider; choosing one goes through [gate] (the user's agreement, asked once).
 */
@Composable
fun ModelPicker(
    providers: List<ProviderInfo>,
    value: ModelChoice,
    onChange: (ModelChoice) -> Unit,
    noneLabel: String? = null,
    /** A provider that cannot be chosen here: the fallback must come from another provider than the main model */
    exclude: String? = null,
    gate: ConsentGate? = null,
) {
    val sessions = LocalAppServices.current.sessions
    // the provider's last good list (kept on the phone) shows at once and is refreshed in place; never the previous provider's
    var list by remember { mutableStateOf(ModelCatalog.keptList(sessions, value.provider)) }
    var loading by remember { mutableStateOf(false) }
    var slow by remember { mutableStateOf(false) }   // the refresh takes over ModelCatalog.SLOW_MS: a small hint shows
    var custom by remember { mutableStateOf(list.let { it != null && value.model.isNotEmpty() && value.model !in it.models }) }
    val failed = string(Res.string.model_picker_list_failed)
    val info = providers.find { it.name == value.provider }

    LaunchedEffect(value.provider) {
        val kept = ModelCatalog.keptList(sessions, value.provider)
        list = kept
        custom = kept != null && value.model.isNotEmpty() && value.model !in kept.models
        if (value.provider.isEmpty()) return@LaunchedEffect
        loading = true
        val hint = launch { kotlinx.coroutines.delay(ModelCatalog.SLOW_MS); slow = true }
        try {
            val m = ModelCatalog.shown(ModelCatalog.fetch(sessions, value.provider), kept)!!
            list = m
            custom = m.models.isEmpty() || (value.model.isNotEmpty() && value.model !in m.models)
        } catch (e: ApiError) {
            list = ModelCatalog.shown(ModelList(value.provider, emptyList(), error = failed), kept)
        } finally {
            hint.cancel()
            loading = false
            slow = false
        }
    }

    val providerOptions = buildList {
        add(SelectOption("", noneLabel ?: string(Res.string.model_picker_choose_provider), keep = true))
        providers.forEach { p ->
            val taken = exclude != null && exclude.isNotEmpty() && p.name == exclude
            var text = when (p.configured) {
                true -> string(Res.string.model_picker_ready, p.displayName)
                false -> string(Res.string.model_picker_needs_setup, p.displayName)
                null -> p.displayName
            }
            if (taken) text = string(Res.string.model_picker_taken, text)
            // ready ones first, then the rest by name
            add(SelectOption(p.name, text, enabled = !taken, rank = if (p.configured == true) 0 else 1, sortKey = p.displayName, group = ProviderConsentRules.group(p)))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectField(string(Res.string.model_picker_provider), providerOptions, value.provider, sorted = true,
            groupLabel = string(Res.string.model_picker_unofficial)) { v ->
            val p = providers.firstOrNull { it.name == v }
            if (gate == null) onChange(ModelChoice(v, "")) else gate.request(p) { onChange(ModelChoice(v, "")) }
        }
        if (value.provider.isNotEmpty() && info?.configured == false) ErrorText(string(Res.string.model_picker_not_ready), small = true)
        if (value.provider.isNotEmpty()) {
            // nothing kept yet and still loading: the chosen model alone, by name (never the raw id in a text field)
            val l = list ?: if (loading) ModelCatalog.placeholder(value.provider, value.model) else null
            val showSelect = l != null && l.models.isNotEmpty()
            if (showSelect) {
                val options = buildList {
                    if (value.model.isEmpty()) {
                        add(SelectOption("", string(if (loading) Res.string.loading else Res.string.model_picker_choose_model), enabled = false, keep = true))
                    }
                    val names = ModelNames.displayAll(l!!.models, l.labels)
                    l.models.forEach { m ->
                        add(SelectOption(m, names.getValue(m)))
                    }
                    add(SelectOption(CUSTOM, string(Res.string.model_picker_custom), keep = true))
                }
                SelectField(string(Res.string.model_picker_model), options, if (custom) CUSTOM else value.model) { v ->
                    if (v == CUSTOM) custom = true else { custom = false; onChange(ModelChoice(value.provider, v)) }
                }
            }
            if (custom || !showSelect) {
                KeloTextField(value.model, { onChange(ModelChoice(value.provider, it)) }, string(Res.string.model_picker_custom_placeholder), maxLength = 200)
            }
            l?.error?.let { MutedText(it, small = true) }
            if (l?.stale == true) MutedText(string(Res.string.model_list_stale), small = true)
            if (slow) MutedText(string(Res.string.loading), small = true)
        }
    }
}

private const val CUSTOM = "__custom__"
