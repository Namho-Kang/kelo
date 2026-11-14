package chat.kelo.app.my

import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import chat.kelo.app.ui.components.MyCard
import chat.kelo.app.ui.components.KeyBox
import chat.kelo.app.ui.components.SubPage
import chat.kelo.app.ui.components.ConfirmDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.chat.ModelCatalog
import chat.kelo.app.chat.ModelChoice
import chat.kelo.app.chat.ModelList
import chat.kelo.app.chat.ModelPicker
import chat.kelo.app.chat.ModelRules
import chat.kelo.app.chat.SelectField
import chat.kelo.app.chat.SelectOption
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import chat.kelo.app.core.Profile
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.components.emphasized
import chat.kelo.app.ui.theme.Kelo
import chat.kelo.app.ui.platform.rememberViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val CUSTOM = "__custom__"
private const val ADD_ENDPOINT = "__add_endpoint__"

/**
 * Which AI service and model answer, the fallback model, and the API key for the service. [cliProvider]: a newer CLI notice opened
 * the page for that provider's CLI; when it is not the selected service its box shows on its own card at the top (with 업데이트),
 * so the main and fallback choice stay as they are.
 */
@Composable
fun ModelPage(profile: Profile, onProfile: (Profile) -> Unit, onBack: () -> Unit, cliProvider: String? = null) {
    val sessions = LocalAppServices.current.sessions
    val texts = ModelTexts(
        listFailed = string(Res.string.model_picker_list_failed),
        installed = string(Res.string.model_cli_installed_msg),
        chooseModel = string(Res.string.model_picker_choose_model),
        urlProblems = mapOf(
            EndpointRules.Problem.EMPTY to string(Res.string.model_url_empty),
            EndpointRules.Problem.SPACES to string(Res.string.model_url_spaces),
            EndpointRules.Problem.SCHEME to string(Res.string.model_url_scheme),
            EndpointRules.Problem.HOST to string(Res.string.model_url_host),
        ),
        endpointNameTaken = string(Res.string.endpoint_name_taken),
        endpointTooMany = string(Res.string.endpoint_too_many),
        endpointInUse = string(Res.string.endpoint_in_use),
    )
    val vm = rememberViewModel { ModelSettingsViewModel(sessions, profile, texts) }
    val s by vm.state.collectAsStateWithLifecycle()
    val gate = chat.kelo.app.chat.rememberConsentGate()
    var confirmRemoveKey by remember { mutableStateOf(false) }
    var showAddEndpoint by remember { mutableStateOf(false) }
    var addName by remember { mutableStateOf("") }
    var addUrl by remember { mutableStateOf("") }
    var editingName by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf("") }
    var confirmDeleteEndpoint by remember { mutableStateOf(false) }
    val providers = s.providers
    val provider = s.provider
    val model = s.model
    val fb = s.fallback
    val bg = s.background
    val save = { vm.save { saved -> onProfile(saved); onBack() } }   // saved: back to 마이

    LaunchedEffect(provider) {
        editingName = false
        confirmDeleteEndpoint = false
    }
    // an unofficial provider without the user's agreement: ask, then save again
    LaunchedEffect(s.consentFor) {
        val needed = s.consentFor ?: return@LaunchedEffect
        vm.consentHandled()
        gate.request(needed.asInfo().copy(consent = null)) { save() }
    }

    // a saved choice that is unofficial and not agreed to (set up before the question existed): asked once, kept either way
    LaunchedEffect(providers) {
        val infos = providers.map { it.asInfo() }
        chat.kelo.app.chat.ProviderConsentRules.pending(profile.provider, profile.fallbackProvider, infos)
            .firstOrNull { !gate.alreadyAsked(it) }
            ?.let { p -> gate.request(p) {} }
    }

    val current = providers.find { it.name == provider }
    val c = Kelo.colors
    val unagreed = chat.kelo.app.chat.ProviderConsentRules.pending(provider, fb.provider, providers.map { it.asInfo() }).filter { gate.needs(it) }
    SubPage(string(Res.string.model_title), onBack) {
        if (unagreed.isNotEmpty() && gate.asking == null) {
            MutedText(string(Res.string.model_unofficial_warning, unagreed.joinToString(", ") { it.displayName }), small = true)
        }
        // the CLI a newer-CLI notice is about, when another service is selected below (the selected one shows its own box there)
        providers.find { it.name == CliUpdateRules.focus(cliProvider, provider, providers.filter { p -> p.cli != null }.map { p -> p.name }) }?.let { f ->
            MyCard {
                FieldLabel(f.displayName)
                CliBox(f.cli!!, s.installing, s.installMessage, onInstall = { vm.install(f) },
                    updating = s.updating == f.name, updateOutcome = s.updateOutcome?.takeIf { it.first == f.name }?.second,
                    onUpdate = { vm.updateCli(f) })
            }
        }
        MyCard {
            val options = buildList {
                add(SelectOption("", string(if (profile.modelReady == false) Res.string.model_picker_choose_provider else Res.string.model_sheet_server_default), keep = true))
                providers.forEach { p ->
                    // ready ones first, then the rest by name
                    add(SelectOption(p.name, when (p.configured) {
                        true -> string(Res.string.model_picker_ready, p.displayName)
                        false -> string(Res.string.model_picker_needs_setup, p.displayName)
                        null -> p.displayName
                    }, rank = if (p.configured == true) 0 else 1, sortKey = p.displayName, group = if (p.unofficial) 1 else 0))
                }
                add(SelectOption(ADD_ENDPOINT, string(Res.string.endpoint_add), keep = true, rank = 2))
            }
            FieldLabel(string(Res.string.model_picker_provider))
            SelectField(string(Res.string.model_picker_provider), options, provider, sorted = true,
                groupLabel = string(Res.string.model_picker_unofficial)) { picked ->
                if (picked == ADD_ENDPOINT) {
                    showAddEndpoint = true
                    vm.clearEndpointMessage()
                } else {
                    gate.request(providers.firstOrNull { it.name == picked }?.asInfo()) {
                        vm.pickProvider(picked)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                LinkButton(string(Res.string.endpoint_add), onClick = {
                    showAddEndpoint = true
                    vm.clearEndpointMessage()
                })
            }
            if (showAddEndpoint) {
                KeyBox {
                    FieldLabel(string(Res.string.endpoint_name))
                    KeloTextField(
                        addName, { addName = it; vm.clearEndpointMessage() },
                        string(Res.string.endpoint_name_hint),
                        maxLength = 40,
                    )
                    FieldLabel(string(Res.string.model_url_label))
                    KeloTextField(
                        addUrl, { addUrl = it; vm.clearEndpointMessage() },
                        string(Res.string.model_url_placeholder),
                        maxLength = 300,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PrimaryButton(
                            string(Res.string.save),
                            onClick = {
                                vm.addEndpoint(addName, addUrl) {
                                    showAddEndpoint = false
                                    addName = ""
                                    addUrl = ""
                                }
                            },
                            enabled = !s.savingEndpoint && addName.isNotBlank() && addUrl.isNotBlank(),
                        )
                        LinkButton(string(Res.string.cancel), onClick = {
                            showAddEndpoint = false
                            addName = ""
                            addUrl = ""
                            vm.clearEndpointMessage()
                        })
                    }
                    if (s.endpointMessage.isNotEmpty()) ErrorText(s.endpointMessage, small = true)
                }
            }
            chat.kelo.app.chat.ChatsGoLine(provider, current?.company)
            current?.cli?.let { cli ->
                CliBox(cli, s.installing, s.installMessage, onInstall = { vm.install(current) }, signInHere = current.signin,
                    updating = s.updating == current.name, updateOutcome = s.updateOutcome?.takeIf { it.first == current.name }?.second,
                    onUpdate = { vm.updateCli(current) })
            }
            if (current != null && chat.kelo.app.chat.SignInRules.offered(current.signin, current.signedIn)) {
                KeyBox(first = current.cli == null) {
                    chat.kelo.app.chat.SignInBox(current.asInfo(), gate) {
                        vm.signedIn()   // the box then shows the account
                    }
                }
            }
            if (current != null && chat.kelo.app.chat.SignOutRules.offered(current.signedIn)) {
                KeyBox(first = current.cli == null) {
                    chat.kelo.app.chat.SignOutBox(current.name, current.displayName, current.account, profile.provider) { outcome, result, detail ->
                        vm.signedOut(current.name, outcome, result?.fallbackCleared == true, detail, onProfile)
                    }
                }
            }
            s.signOut?.takeIf { it.first == current?.name }?.let { chat.kelo.app.chat.SignOutMessage(it.second, s.signOutDetail) }
            if (current?.takesUrl == true) {
                KeyBox(first = true) {
                    val isCustom = current.name == "custom"
                    val isUser = current.userEndpoint
                    if (isUser || isCustom) {
                        if (editingName) {
                            FieldLabel(string(Res.string.endpoint_name))
                            chat.kelo.app.ui.components.FieldWithAction(
                                field = { m ->
                                    KeloTextField(
                                        renameInput, { renameInput = it; vm.clearEndpointMessage() },
                                        string(Res.string.endpoint_name_hint),
                                        maxLength = 40, modifier = m,
                                    )
                                },
                                action = {
                                    PrimaryButton(
                                        string(Res.string.save),
                                        onClick = {
                                            vm.renameEndpoint(current.name, renameInput) {
                                                editingName = false
                                            }
                                        },
                                        enabled = renameInput.isNotBlank() || isCustom,
                                    )
                                },
                            )
                            LinkButton(string(Res.string.cancel), onClick = {
                                editingName = false
                                vm.clearEndpointMessage()
                            })
                        } else {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LinkButton(string(Res.string.endpoint_rename), onClick = {
                                    renameInput = current.displayName
                                    editingName = true
                                    vm.clearEndpointMessage()
                                })
                                if (isUser) {
                                    LinkButton(string(Res.string.endpoint_delete), onClick = {
                                        confirmDeleteEndpoint = true
                                        vm.clearEndpointMessage()
                                    }, danger = true)
                                }
                            }
                        }
                    }
                    // an address already saved shows as one line with 변경; otherwise (or while changing it) the field
                    val saved = current.baseUrl.orEmpty()
                    if (saved.isNotEmpty() && !s.editingUrl) {
                        MutedText(string(Res.string.model_url_set, saved), small = true)
                        LinkButton(string(Res.string.model_url_change), onClick = { vm.changeUrl(saved) })
                    } else {
                        FieldLabel(string(Res.string.model_url_label))
                        chat.kelo.app.ui.components.FieldWithAction(
                            field = { m ->
                                KeloTextField(
                                    s.urlInput, vm::typeUrl, string(Res.string.model_url_placeholder),
                                    maxLength = 300, modifier = m,
                                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                                )
                            },
                            action = { PrimaryButton(string(Res.string.save), onClick = vm::saveUrl, enabled = !s.savingUrl && s.urlInput.isNotBlank()) },
                        )
                        MutedText(string(Res.string.model_url_help), small = true)
                        if (saved.isNotEmpty()) LinkButton(string(Res.string.cancel), onClick = vm::cancelUrl)
                    }
                    if (s.urlMessage.isNotEmpty()) ErrorText(s.urlMessage, small = true)
                    if (s.endpointMessage.isNotEmpty() && !showAddEndpoint) ErrorText(s.endpointMessage, small = true)
                }
            }
            if (current != null && (current.takesKey || current.takesUrl)) {
                KeyBox(first = current.takesUrl != true) {
                    if (current.takesUrl) FieldLabel(string(Res.string.model_key_optional))
                    // a key kept in the app: only that it is set, and 삭제 (then the field comes back); a key from the server's .env:
                    // only that note; no key: the field to paste one
                    when (KeyRules.state(current.keySource)) {
                        KeyRules.State.IN_APP -> {
                            val hint = current.keyHint.orEmpty()
                            MutedText(
                                if (hint.isEmpty()) string(Res.string.model_key_set) else string(Res.string.model_key_in_app, hint),
                                small = true,
                            )
                            LinkButton(string(Res.string.model_key_delete), onClick = { confirmRemoveKey = true }, danger = true)
                        }
                        KeyRules.State.ENV -> MutedText(string(Res.string.model_key_env), small = true)
                        KeyRules.State.NONE -> {
                            MutedText(string(Res.string.model_key_none), small = true)
                            chat.kelo.app.ui.components.FieldWithAction(
                                field = { m ->
                                    KeloTextField(
                                        s.keyInput, vm::typeKey, string(Res.string.model_key_paste),
                                        password = true, maxLength = 512, modifier = m,
                                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                                    )
                                },
                                action = { PrimaryButton(string(Res.string.save), onClick = vm::saveKey, enabled = s.keyInput.trim().length >= 8) },
                            )
                        }
                    }
                    if (s.keyMessage.isNotEmpty()) MutedText(s.keyMessage, small = true)
                }
            }
            if (current != null && current.oauth && current.loggedIn != true && !current.signin) {   // an older server: no sign-in here
                Text(
                    emphasized(string(Res.string.model_oauth_login_needed), "python -m assistant.providers.oauth login ${current.name}", code = true),
                    color = c.danger, style = MaterialTheme.typography.bodySmall,
                )
            }
            if (provider.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FieldLabel(string(Res.string.model_picker_model))
                    Row(Modifier.weight(1f)) {}
                    LinkButton(
                        string(if (s.slowModels) Res.string.loading else Res.string.refresh),
                        onClick = vm::refreshModels,
                    )
                }
                // nothing kept yet and still loading: the chosen model alone, by name (never the raw id in a text field)
                val l = s.models ?: if (s.loadingModels) ModelCatalog.placeholder(provider, model) else null
                val showSelect = l != null && l.models.isNotEmpty()
                if (showSelect) {
                    val mOptions = buildList {
                        if (model.isEmpty()) add(SelectOption("", string(Res.string.model_picker_choose_model), enabled = false, keep = true))
                        val names = chat.kelo.app.chat.ModelNames.displayAll(l!!.models, l.labels)
                        l.models.forEach { m ->
                            add(SelectOption(m, names.getValue(m)))
                        }
                        add(SelectOption(CUSTOM, string(Res.string.model_picker_custom), keep = true))
                    }
                    SelectField(string(Res.string.model_picker_model), mOptions, if (s.customModel) CUSTOM else model) { v ->
                        if (v == CUSTOM) vm.chooseCustomModel() else vm.pickModel(v)
                    }
                }
                if (s.customModel || !showSelect) {
                    KeloTextField(model, vm::typeModel, string(Res.string.model_picker_custom_placeholder), maxLength = 200)
                }
                l?.error?.let { ErrorText(it, small = true) }
                if (l?.stale == true) MutedText(string(Res.string.model_list_stale), small = true)
                // the thinking level, as in the chat's model sheet; a server that turned it away: its sentence, quietly
                val effortChoice = chat.kelo.app.chat.ModelChooserRules.effort(current?.asInfo(), l?.info?.get(model))
                if (effortChoice is chat.kelo.app.chat.EffortChoice.Levels) {
                    FieldLabel(string(Res.string.chat_effort))
                    SelectField(
                        string(Res.string.chat_effort),
                        effortChoice.levels.map { v -> SelectOption(v, chat.kelo.app.chat.effortLabel(v), keep = true) },
                        chat.kelo.app.chat.ModelChooserRules.shownLevel(effortChoice, s.effort),
                    ) { v -> vm.pickEffort(v) }
                    MutedText(string(Res.string.chooser_effort_hint), small = true)
                } else {
                    chat.kelo.app.chat.ModelChooserRules.effortNote(effortChoice)?.let { MutedText(it, small = true) }
                }
            } else {
                Text(string(Res.string.model_server_default_note), color = c.muted, style = MaterialTheme.typography.bodySmall)
            }
            Text(string(Res.string.model_sheet_fallback), style = MaterialTheme.typography.titleSmall, color = c.ink)
            MutedText(string(Res.string.model_fallback_help), small = true)
            ModelPicker(
                providers.map { it.asInfo() }, fb, noneLabel = string(Res.string.model_sheet_no_fallback), exclude = provider, gate = gate,
                onChange = vm::pickFallback,
            )
            if (s.fallbackCleared) MutedText(string(Res.string.model_sheet_fallback_cleared), small = true)
            // background work (look-back, memory, journal, ...): any provider, the main one too; empty = the main model
            Text(string(Res.string.model_background_title), style = MaterialTheme.typography.titleSmall, color = c.ink)
            MutedText(string(Res.string.model_background_help), small = true)
            ModelPicker(
                providers.map { it.asInfo() }, bg, noneLabel = string(Res.string.model_background_same), gate = gate,
                onChange = vm::pickBackground,
            )
            UsageLogic.fallbackShown(s.backgroundState, bg.provider, bg.model)?.let { f ->
                MutedText(string(Res.string.model_background_fell_back, shortDateTime(f.at, appLocale())), small = true)
            }
            PrimaryButton(string(Res.string.save), onClick = save, enabled = !s.saving)
            if (s.error.isNotEmpty()) ErrorText(s.error, small = true)
        }
    }

    chat.kelo.app.chat.ConsentDialog(gate)
    s.askInstall?.let { p ->
        ConfirmDialog(
            text = string(Res.string.model_cli_install_confirm, p.displayName),
            yes = string(Res.string.model_cli_install), danger = false,
            onYes = { vm.install(p) }, onDismiss = vm::dismissInstall,
        )
    }
    if (confirmRemoveKey) {
        ConfirmDialog(
            text = string(Res.string.model_key_delete_confirm), yes = string(Res.string.delete),
            onYes = vm::removeKey, onDismiss = { confirmRemoveKey = false },
        )
    }
    if (confirmDeleteEndpoint && current != null) {
        ConfirmDialog(
            text = string(Res.string.endpoint_delete_confirm, current.displayName),
            yes = string(Res.string.delete),
            danger = true,
            onYes = {
                vm.deleteEndpoint(current.name)
                confirmDeleteEndpoint = false
            },
            onDismiss = { confirmDeleteEndpoint = false },
        )
    }
}

/** The CLI's state on the server: installed, signed in, its version, and an install button when it can be installed. */
@Composable
private fun CliBox(cli: CliStatus, installing: Boolean, installMsg: String, onInstall: () -> Unit, signInHere: Boolean = false,
                   updating: Boolean = false, updateOutcome: CliUpdateRules.Outcome? = null, onUpdate: () -> Unit = {}) {
    KeyBox {
        MutedText(
            when {
                installing -> string(Res.string.model_cli_installing)
                !cli.installed -> string(if (cli.remote) Res.string.model_cli_unreachable else Res.string.model_cli_not_installed)
                cli.loggedIn == false -> string(Res.string.model_cli_login_needed)
                cli.loggedIn == true -> string(if (cli.remote) Res.string.model_cli_ok_remote else Res.string.model_cli_ok_local)
                else -> string(Res.string.model_cli_ok_unknown)
            },
            small = true,
        )
        if (!cli.installed && cli.installable && !installing) SecondaryButton(string(Res.string.model_cli_install), onClick = onInstall)
        // the terminal hint only when the app cannot sign it in itself (the 로그인 box below does it)
        val signInBelow = signInHere && cli.installed && cli.loggedIn != true
        if ((!cli.installed || cli.loggedIn != true) && !installing && cli.hint.isNotEmpty() && !signInBelow) MutedText(cli.hint, small = true)
        if (cli.installed && (!cli.version.isNullOrEmpty() || !cli.updateHint.isNullOrEmpty())) {
            val parts = listOfNotNull(
                cli.version?.takeIf { it.isNotEmpty() }?.let { string(Res.string.model_cli_version, it) },
                cli.latestVersion?.takeIf { cli.updateAvailable && it.isNotEmpty() }?.let { string(Res.string.model_cli_update_available, it) },
                cli.updateHint?.takeIf { it.isNotEmpty() && !cli.updatable },   // the button below does it
            )
            MutedText(parts.joinToString(string(Res.string.usage_group_joiner)), small = true)
        }
        if (updating) MutedText(string(Res.string.model_cli_updating), small = true)
        else if (CliUpdateRules.showButton(cli, busy = installing)) SecondaryButton(string(Res.string.model_cli_update_button), onClick = onUpdate)
        when (updateOutcome) {
            is CliUpdateRules.Outcome.Updated -> MutedText(string(Res.string.model_cli_update_done, updateOutcome.product, updateOutcome.version), small = true)
            is CliUpdateRules.Outcome.Unchanged -> MutedText(string(Res.string.model_cli_update_unchanged, updateOutcome.product), small = true)
            is CliUpdateRules.Outcome.Failed -> ErrorText(string(Res.string.model_cli_update_failed, updateOutcome.error), small = true)
            else -> {}
        }
        if (installMsg.isNotEmpty()) MutedText(installMsg, small = true)
    }
}

/** "2026. 11. 9. 오후 3:12": a server time (epoch seconds) in the phone's zone and the app's language. */
private fun shortDateTime(epochSeconds: Double, locale: java.util.Locale): String =
    java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT).withLocale(locale)
        .format(java.time.Instant.ofEpochMilli((epochSeconds * 1000).toLong()).atZone(java.time.ZoneId.systemDefault()))
