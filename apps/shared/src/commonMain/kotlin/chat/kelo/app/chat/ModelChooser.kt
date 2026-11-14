package chat.kelo.app.chat

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import chat.kelo.app.ui.platform.FormFactor
import chat.kelo.app.ui.platform.LocalFormFactor
import chat.kelo.app.ui.platform.formFactor
import chat.kelo.app.ui.platform.headerHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Profile
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.theme.Kelo
import chat.kelo.app.ui.theme.LocalReduceMotion
import chat.kelo.app.ui.theme.Motion
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun effortLabel(v: String): String = when (v) {
    "" -> string(Res.string.chat_effort_default)
    "low" -> string(Res.string.chat_effort_low)
    "medium" -> string(Res.string.chat_effort_medium)
    "high" -> string(Res.string.chat_effort_high)
    "xhigh" -> string(Res.string.chat_effort_xhigh)
    "max" -> string(Res.string.chat_effort_max)
    else -> v
}

@Composable
private fun traitText(t: ModelTrait): String = string(
    when (t) {
        ModelTrait.FAST -> Res.string.model_trait_fast
        ModelTrait.REASONING -> Res.string.model_trait_reasoning
        ModelTrait.LARGE -> Res.string.model_trait_large
        ModelTrait.IMAGES -> Res.string.model_trait_images
        ModelTrait.LONG -> Res.string.model_trait_long
    },
)

/** The one line under a model's name: its traits, or a plain "글로 대화" when nothing is known. */
@Composable
fun modelBlurb(id: String, facts: ModelFacts?): String {
    val traits = ModelTraits.of(id, facts)
    return if (traits.isEmpty()) string(Res.string.model_trait_plain)
    else traits.map { traitText(it) }.joinToString(string(Res.string.model_trait_separator))
}

/** The chip's words: "<model> <thinking level>", the level only when this provider takes one. */
@Composable
fun modelChipText(profile: Profile, providers: List<ProviderInfo>, list: ModelList?): String {
    if (profile.model.isEmpty()) return string(Res.string.chat_model_none)
    val name = ModelNames.short(ModelNames.display(profile.model, list?.takeIf { it.provider == profile.provider }?.labels?.get(profile.model)))
    val effort = ModelChooserRules.effort(providers.find { it.name == profile.provider },
        list?.takeIf { it.provider == profile.provider }?.info?.get(profile.model))
    return if (effort is EffortChoice.Levels) string(Res.string.chat_model_chip, name, effortLabel(ModelChooserRules.shownLevel(effort, profile.effort))) else name
}

/**
 * The model chooser: the models of the current provider (and of the other ready providers, folded), one tap switches
 * (PUT /api/profile, the unofficial-provider question first when needed); under the list the thinking level, then a way to
 * the detailed settings (fallback model, a model typed by hand).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelChooser(
    profile: Profile,
    providers: List<ProviderInfo>,
    currentList: ModelList?,
    onProfile: (Profile) -> Unit,
    onEffort: (String) -> Unit,
    onMore: () -> Unit,
    onClose: () -> Unit,
) {
    val sessions = LocalAppServices.current.sessions
    val scope = rememberCoroutineScope()
    val gate = rememberConsentGate()
    val c = Kelo.colors
    val reduce = LocalReduceMotion.current
    val failed = string(Res.string.model_picker_list_failed)

    val sections = remember(profile.provider, providers) { ModelChooserRules.sections(profile.provider, providers) }
    // each provider's last good list shows at once (kept on the phone); opening a section refreshes it in place
    val lists = remember { mutableStateMapOf<String, ModelList>().apply { putAll(ModelCatalog.keptLists(sessions)) } }
    val fetched = remember { mutableSetOf<String>() }
    val open = remember { mutableStateMapOf<String, Boolean>() }
    var switching by remember { mutableStateOf<ModelChoice?>(null) }
    var err by remember { mutableStateOf("") }
    var levelsOpen by remember { mutableStateOf(false) }
    // the model list folds like the thinking level: closed at first, so the level and 자세한 설정 never sit below every model
    var listOpen by remember { mutableStateOf(ModelChooserRules.listStartsOpen(profile.model, sections)) }
    fun effortOf(provider: String, model: String) = ModelChooserRules.effort(providers.find { it.name == provider }, lists[provider]?.info?.get(model))
    /** After a pick: close when nothing is left to choose here, else fold the list (the thinking level is right under it) */
    fun afterPick(choice: ModelChoice) { if (ModelChooserRules.closeAfterPick(effortOf(choice.provider, choice.model))) onClose() else listOpen = false }

    LaunchedEffect(currentList) { if (currentList != null && currentList.provider.isNotEmpty()) lists[currentList.provider] = currentList }
    suspend fun load(provider: String) {
        if (!fetched.add(provider)) return
        val fresh = try {
            ModelCatalog.fetch(sessions, provider)
        } catch (_: ApiError) {
            ModelList(provider, emptyList(), error = failed)
        }
        ModelCatalog.shown(fresh, lists[provider])?.let { lists[provider] = it }
    }
    LaunchedEffect(sections) { sections.firstOrNull()?.let { if (it == profile.provider) load(it) } }   // also for the folded row's name

    fun switch(choice: ModelChoice) {
        if (switching != null) return
        if (choice.provider == profile.provider && choice.model == profile.model) { afterPick(choice); return }
        err = ""
        switching = choice
        scope.launch {
            try {
                onProfile(sessions.requireApi().put<Profile>("/api/profile", buildJsonObject {
                    put("provider", choice.provider); put("model", choice.model)   // the server empties a fallback on the same provider
                }))
                afterPick(choice)
            } catch (e: ApiError) {
                val needed = ProviderConsentRules.requiredBy(e)?.let { n -> providers.firstOrNull { it.name == n } }
                if (needed != null) {
                    switching = null
                    gate.request(needed.copy(consent = null)) { switch(choice) }
                } else err = e.detail
            } finally {
                if (switching == choice) switching = null
            }
        }
    }
    fun pick(choice: ModelChoice) = gate.request(providers.firstOrNull { it.name == choice.provider }) { switch(choice) }

    // The sheet stops a little below the agent's name tag, however long the list. A top padding on the sheet itself does it: the
    // sheet measures its anchors inside that padding (so "expanded" can never reach above it), and the padding is not painted. This
    // does not depend on the screen size the configuration reports. The sheet adds only the navigation bar below its content (not
    // the status bar, which it never reaches, and not twice).
    val top = ModelChooserRules.sheetTop(headerHeight())
    // the list, the thinking level and the way to the settings: the same in the phone's sheet and the desktop's dialog
    val body: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = formFactor(0.dp, 8.dp)), verticalAlignment = Alignment.CenterVertically) {
            Text(string(Res.string.model_sheet_title), style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(KeloIcons.Close, contentDescription = string(Res.string.close), tint = c.muted) }
        }
        if (err.isNotEmpty()) ErrorText(err, small = true, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        LazyColumn(Modifier.weight(1f, fill = formFactor(false, true)).fillMaxWidth()) {
            if (sections.isNotEmpty()) item(key = "list") { Column {
                val currentList = lists[profile.provider]
                ModelListRow(
                    provider = if (profile.provider.isEmpty()) "" else providerLabel(profile.provider, providers),
                    model = if (profile.model.isEmpty()) string(Res.string.chat_model_none)
                        else ModelNames.display(profile.model, currentList?.labels?.get(profile.model)),
                    open = listOpen,
                    onToggle = {
                        val (l, v) = ModelChooserRules.toggle(listOpen, levelsOpen, ModelChooserRules.Fold.LIST)
                        listOpen = l; levelsOpen = v
                    },
                    reduce = reduce,
                )
                ChatsGoLine(profile.provider, providers.find { it.name == profile.provider }?.company,
                    Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp))
            } }
            // The whole list is one item that grows and shrinks (AnimatedVisibility), and each provider's models fold inside it
            // the same way: the rows below and the sheet's own edge move smoothly with it instead of jumping as rows come and go.
            if (sections.isNotEmpty()) item(key = "sections") {
                AnimatedVisibility(
                    visible = listOpen,
                    enter = expandVertically(Motion.enter(reduce), expandFrom = Alignment.Top) + fadeIn(Motion.standard(reduce)),
                    exit = shrinkVertically(Motion.exit(reduce), shrinkTowards = Alignment.Top) + fadeOut(Motion.small(reduce)),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        sections.forEachIndexed { index, provider ->
                            val main = provider == profile.provider
                            // the current provider starts open and folds with its arrow like the others; the rest start folded
                            val expanded = open[provider] ?: main
                            if (sections.size > 1 || !main) {
                                SectionHeader(
                                    label = providerLabel(provider, providers),
                                    foldable = true, expanded = expanded, first = index == 0,
                                    onToggle = {
                                        val next = !expanded
                                        open[provider] = next
                                        if (next) scope.launch { load(provider) }
                                    },
                                )
                            }
                            AnimatedVisibility(
                                visible = expanded,
                                enter = expandVertically(Motion.enter(reduce), expandFrom = Alignment.Top) + fadeIn(Motion.standard(reduce)),
                                exit = shrinkVertically(Motion.exit(reduce), shrinkTowards = Alignment.Top) + fadeOut(Motion.small(reduce)),
                            ) {
                                val list = lists[provider]
                                val rows = ModelChooserRules.rows(provider, list, profile.provider to profile.model)
                                val names = ModelNames.displayAll(rows, list?.labels.orEmpty())
                                // the loading row turning into the models grows smoothly too
                                Column(Modifier.fillMaxWidth().animateContentSize(Motion.standard(reduce))) {
                                    if (list == null) LoadingRow()
                                    rows.forEach { id ->
                                        val on = main && id == profile.model
                                        ModelRow(
                                            name = names.getValue(id),
                                            blurb = modelBlurb(id, list?.info?.get(id)),
                                            selected = on, busy = switching == ModelChoice(provider, id),
                                            onClick = { pick(ModelChoice(provider, id)) },
                                        )
                                    }
                                    list?.error?.let { e -> MutedText(e, small = true, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
                                    if (list?.stale == true) {
                                        MutedText(string(Res.string.model_list_stale), small = true, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (sections.isEmpty()) item { MutedText(string(Res.string.chooser_none_ready), small = true, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            // a model whose thinking level is part of its name (High/Medium/Low rows above) needs no extra row or note here
            val effortChoice = ModelChooserRules.effort(providers.find { it.name == profile.provider }, lists[profile.provider]?.info?.get(profile.model))
            if (effortChoice !is EffortChoice.FromModel) item(key = "effort") {
                val gap by androidx.compose.animation.core.animateDpAsState(if (listOpen) 8.dp else 0.dp, Motion.standard(reduce), label = "gap")
                HorizontalDivider(Modifier.padding(top = gap), color = c.line)
                EffortRow(
                    effortChoice, profile.effort, levelsOpen,
                    onToggle = {
                        val (l, v) = ModelChooserRules.toggle(listOpen, levelsOpen, ModelChooserRules.Fold.LEVELS)
                        listOpen = l; levelsOpen = v
                    },
                    onPick = { v -> levelsOpen = false; onEffort(v) },
                    reduce = reduce,
                )
            }
            item(key = "more") {
                HorizontalDivider(color = c.line)
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onMore).padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(string(Res.string.chooser_more), style = MaterialTheme.typography.bodyLarge, color = c.ink)
                        Text(string(Res.string.chooser_more_hint), style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                    Icon(KeloIcons.ChevronRight, contentDescription = null, tint = c.muted, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
    // the phone shows a bottom sheet, the desktop a centred dialog over a scrim (the container is the only difference)
    when (LocalFormFactor.current) {
        FormFactor.Phone -> ModalBottomSheet(
            onDismissRequest = onClose,
            sheetState = chat.kelo.app.ui.components.rememberKeloSheetState(),
            containerColor = c.card,
            sheetMaxWidth = androidx.compose.material3.BottomSheetDefaults.SheetMaxWidth,
            modifier = Modifier.padding(top = top),
            contentWindowInsets = { WindowInsets.navigationBars },
        ) { Column(Modifier.fillMaxWidth()) { body() } }
        FormFactor.Desktop -> Box(
            Modifier.fillMaxSize().background(c.scrim.copy(alpha = 0.32f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth(0.9f).heightIn(max = 760.dp).fillMaxHeight(0.8f).clip(RoundedCornerShape(20.dp)).background(c.card)
                    .border(1.dp, c.line, RoundedCornerShape(20.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) { body() }
        }
    }
    ConsentDialog(gate) { providerLabelPlain(it, providers) }
}

private fun providerLabelPlain(p: ProviderInfo, providers: List<ProviderInfo>): String =
    ProviderLabels.displayName(p.name, providers) ?: p.displayName

@Composable
private fun SectionHeader(label: String, foldable: Boolean, expanded: Boolean, first: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val c = Kelo.colors
    val turn by animateFloatAsState(ModelChooserRules.chevronTurn(expanded), Motion.standard(LocalReduceMotion.current), label = "fold")
    val stateText = string(if (expanded) Res.string.goals_hide else Res.string.goals_show)
    Row(
        modifier.fillMaxWidth()
            .then(if (foldable) Modifier.clickable(role = Role.Button, onClick = onToggle).semantics { stateDescription = stateText } else Modifier)
            .padding(start = 20.dp, end = 16.dp, top = if (first) 4.dp else 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = c.muted, modifier = Modifier.weight(1f))
        if (foldable) Icon(KeloIcons.ChevronRight, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp).rotate(turn))
    }
}

@Composable
private fun LoadingRow() {
    val c = Kelo.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), color = c.muted, strokeWidth = 2.dp)
        MutedText(string(Res.string.loading), small = true)
    }
}

@Composable
private fun ModelRow(name: String, blurb: String, selected: Boolean, busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Kelo.colors
    val now = string(Res.string.chooser_in_use)
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { this.selected = selected; if (selected) stateDescription = now }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = c.ink, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            Text(blurb, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when {
                busy -> CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                selected -> Icon(KeloIcons.Check, contentDescription = null, tint = c.accent, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/**
 * The model list's folding row, built like the thinking level's: "모델" with the provider under it, the model in use on the right,
 * a chevron that turns when it opens. A long name wraps on the right instead of pushing the label away.
 */
@Composable
private fun ModelListRow(provider: String, model: String, open: Boolean, onToggle: () -> Unit, reduce: Boolean) {
    val c = Kelo.colors
    val turn by animateFloatAsState(ModelChooserRules.chevronTurn(open), Motion.standard(reduce), label = "models")
    val fold = string(if (open) Res.string.a11y_expanded else Res.string.a11y_collapsed)
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onToggle).semantics { stateDescription = fold }
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        // the name takes at most about three fifths of the row, so "모델" and the provider keep room in any language
        val nameMax = maxWidth * ModelChooserRules.NAME_SHARE
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(string(Res.string.model_picker_model), style = MaterialTheme.typography.bodyLarge, color = c.ink)
                if (provider.isNotEmpty()) Text(provider, style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
            Text(
                model, style = MaterialTheme.typography.bodyLarge, color = c.accent, fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.widthIn(max = nameMax),
            )
            Icon(KeloIcons.ChevronRight, contentDescription = null, tint = c.muted, modifier = Modifier.size(20.dp).rotate(turn))
        }
    }
}

@Composable
private fun EffortRow(choice: EffortChoice, effort: String, open: Boolean, onToggle: () -> Unit, onPick: (String) -> Unit, reduce: Boolean) {
    val c = Kelo.colors
    val title = string(Res.string.chat_effort)
    when (choice) {
        is EffortChoice.Levels -> {
            val shown = ModelChooserRules.shownLevel(choice, effort)
            val turn by animateFloatAsState(ModelChooserRules.chevronTurn(open), Motion.standard(reduce), label = "levels")
            val fold = string(if (open) Res.string.a11y_expanded else Res.string.a11y_collapsed)
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle).semantics { stateDescription = fold }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, color = c.ink)
                    Text(string(Res.string.chooser_effort_hint), style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
                Text(effortLabel(shown), style = MaterialTheme.typography.bodyLarge, color = c.accent, fontWeight = FontWeight.SemiBold)
                Icon(KeloIcons.ChevronRight, contentDescription = null, tint = c.muted, modifier = Modifier.size(20.dp).rotate(turn))
            }
            AnimatedVisibility(
                visible = open,
                enter = expandVertically(Motion.enter(reduce)) + fadeIn(Motion.small(reduce)),
                exit = shrinkVertically(Motion.exit(reduce)) + fadeOut(Motion.small(reduce)),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                    choice.levels.forEach { v ->
                        val on = v == shown
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .then(if (on) Modifier.background(c.bubble) else Modifier)
                                .clickable(role = Role.RadioButton) { onPick(v) }
                                .semantics(mergeDescendants = true) { selected = on }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(effortLabel(v), style = MaterialTheme.typography.bodyLarge, color = c.ink,
                                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                                if (v.isEmpty()) Text(string(Res.string.chooser_effort_default_hint), style = MaterialTheme.typography.bodySmall, color = c.muted)
                            }
                            if (on) Icon(KeloIcons.Check, contentDescription = null, tint = c.accent, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
        else -> {
            // shown, but greyed, with the reason in words
            val reason = ModelChooserRules.effortNote(choice) ?: string(Res.string.chooser_effort_none)
            Column(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$title. $reason" }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = c.disabledInk)
                Text(reason, style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
        }
    }
}
