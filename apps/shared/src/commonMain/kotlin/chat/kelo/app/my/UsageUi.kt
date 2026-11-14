package chat.kelo.app.my

import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import chat.kelo.app.ui.components.MyCard
import chat.kelo.app.ui.components.SubPage
import chat.kelo.app.ui.components.Tag
import chat.kelo.app.ui.components.Bar
import chat.kelo.app.ui.components.ConfirmDialog
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.KeloModal
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import org.jetbrains.compose.resources.StringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.theme.Kelo
import chat.kelo.app.ui.theme.LocalReduceMotion

/** The English phrases the CLIs report, paired with their translations. */
@Composable
fun usageLabels(): List<Pair<String, String>> = UsageLogic.LABEL_SOURCES.zip(
    listOf(
        string(Res.string.usage_label_session), string(Res.string.usage_label_week),
        string(Res.string.usage_label_all_models), string(Res.string.usage_label_weekly),
        string(Res.string.usage_label_five_hour),
    ),
)

/** The limit groups of [usage] for [model], named in the app's language. */
@Composable
fun usageGroups(usage: Usage?, model: String): List<UsageGroup> {
    val models = string(Res.string.usage_group_models)
    val joiner = string(Res.string.usage_group_joiner)
    return UsageLogic.groups(usage, model) { g -> UsageLogic.groupName(g, { models.format(it) }, joiner) }
}

/**
 * When a limit resets, in the phone's own zone and language: "3시간 후 초기화" when near, "11월 7일 오후 8:40에 초기화" further off,
 * or the server's words (shortened, never split in a word) when they cannot be read.
 */
@Composable
fun resetText(w: UsageWindow): String? {
    val now = System.currentTimeMillis()
    val r = UsageLogic.reset(w, now) ?: return null
    return when (val p = UsageLogic.phrase(r, now)) {
        UsageLogic.Phrase.Soon -> string(Res.string.usage_reset_soon_phrase)
        is UsageLogic.Phrase.InMinutes -> string(Res.string.usage_reset_in_minutes, p.n)
        is UsageLogic.Phrase.InHours -> string(Res.string.usage_reset_in_hours, p.n)
        is UsageLogic.Phrase.On -> string(Res.string.usage_reset_on, localDayTime(p.epochMillis))
        is UsageLogic.Phrase.Raw -> string(Res.string.usage_resets, chat.kelo.app.ui.components.TokenText.fit(p.text, 24))
    }
}

/** "11월 7일 오후 8:40": month, day and time in the phone's zone and the app's language. */
@Composable
private fun localDayTime(millis: Long): String {
    val locale = appLocale()
    val pattern = chat.kelo.app.ui.platform.bestDatePattern("MMMdjmm", locale)
    return java.time.format.DateTimeFormatter.ofPattern(pattern, locale).format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()))
}

/** The label and "42% 사용", the bar, and when it resets (with what is left, in the full form). */
@Composable
fun UsageBar(w: UsageWindow, compact: Boolean, labels: List<Pair<String, String>>) {
    val c = Kelo.colors
    val reset = resetText(w)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(UsageLogic.label(w.label, labels), color = c.ink, modifier = Modifier.weight(1f), maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(string(Res.string.usage_used, w.usedPercent), fontWeight = FontWeight.Bold, color = c.ink)
        }
        Bar(UsageLogic.barFraction(w.usedPercent), UsageLogic.isHot(w.usedPercent))
        val line = if (compact) {
            reset.orEmpty()
        } else {
            val left = string(Res.string.usage_left, UsageLogic.left(w.usedPercent))
            if (reset != null) string(Res.string.usage_left_and_reset, left, reset) else left
        }
        if (line.isNotEmpty()) MutedText(line, small = true)
    }
}

/**
 * One block per group of models with its name. Where the model in use is known, its group is first and
 * marked, and (in the short form) the other groups take one line each with their percentages; otherwise every group is in full.
 */
@Composable
fun UsageGroupsView(groups: List<UsageGroup>, full: Boolean) {
    val c = Kelo.colors
    val labels = usageLabels()
    Column {
        groups.forEachIndexed { i, g ->
            if (i > 0) HorizontalDivider(color = c.line, modifier = Modifier.padding(top = 12.dp, bottom = 10.dp))
            if (!g.group.isNullOrEmpty()) {
                Row(
                    Modifier.padding(top = if (i == 0) 6.dp else 0.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(g.name, fontWeight = FontWeight.Bold, color = c.ink, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (g.active) Tag(string(Res.string.usage_in_use))
                }
            }
            if (UsageLogic.oneLine(groups, g, full)) {
                val parts = g.windows.map { string(Res.string.usage_one_line_part, UsageLogic.label(it.label, labels), it.usedPercent) }
                MutedText(parts.joinToString(string(Res.string.usage_group_joiner)), small = true, modifier = Modifier.padding(top = 4.dp))
            } else {
                g.windows.forEach { UsageBar(it, compact = !full, labels = labels) }
            }
        }
    }
}

/** The 사용량 row's value: "42% 사용" for providers with limits, else today's tokens; null while unknown. */
@Composable
fun usageSummary(usage: Usage?, model: String): String? {
    val groups = usageGroups(usage, model)
    UsageLogic.summaryPercent(groups)?.let { return string(Res.string.usage_used, it) }
    if (usage != null && usage.subscription == null) return string(Res.string.my_usage_today_tokens, fmtNumber(usage.today.sum))
    return null
}


@Composable
private fun roleWord(role: String): String? = when (role) {
    "background" -> string(Res.string.usage_role_background)
    "fallback" -> string(Res.string.usage_role_fallback)
    else -> null
}

/** The limits section: the current model's service first, then other services folded open. */
@Composable
fun LimitsSection(
    usage: Usage?,
    model: String,
    providers: List<chat.kelo.app.chat.ProviderInfo>,
    onRefresh: () -> Unit,
    onAdminKey: (String) -> Unit = {},
    onRemoveAdminKey: (String) -> Unit = {},
) {
    val c = Kelo.colors
    val parts = UsageLogic.limitParts(usage)
    var open by rememberSaveable { mutableStateOf(false) }
    val reduceMotion = LocalReduceMotion.current

    MyCard {
        when {
            usage == null -> chat.kelo.app.ui.components.SkeletonLines(3)
            parts.current != null || parts.currentAccount != null -> {
                val current = parts.current
                val currentAccount = parts.currentAccount
                val serviceLabel = if (current != null) {
                    chat.kelo.app.chat.providerLabel(current.provider, providers)
                } else {
                    currentAccount!!.displayName.ifBlank { chat.kelo.app.chat.providerLabel(currentAccount.provider, providers) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        string(Res.string.usage_limits_current, serviceLabel),
                        style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    LinkButton(string(Res.string.refresh), onClick = onRefresh)
                }
                if (current != null) {
                    if (current.error != null) {
                        MutedText(current.error!!, small = true)
                    } else {
                        val models = string(Res.string.usage_group_models)
                        val joiner = string(Res.string.usage_group_joiner)
                        val groups = UsageLogic.groups(Usage(subscription = current), model) { g ->
                            UsageLogic.groupName(g, { models.format(it) }, joiner)
                        }
                        UsageGroupsView(groups, full = true)
                    }
                } else if (currentAccount != null) {
                    currentAccount.balance?.let { Stat(string(Res.string.balance_left), money(it, currentAccount.currency)) }
                    currentAccount.limitRemaining?.let { Stat(string(Res.string.balance_limit_left), money(it, currentAccount.currency) + (currentAccount.limit?.let { l -> " / " + money(l, currentAccount.currency) } ?: "")) }
                    currentAccount.usedToday?.let { Stat(string(Res.string.balance_used_today), money(it, currentAccount.currency)) }
                    currentAccount.usedWeek?.let { Stat(string(Res.string.balance_used_week), money(it, currentAccount.currency)) }
                    currentAccount.usedMonth?.let { Stat(string(Res.string.balance_used_month), money(it, currentAccount.currency)) }
                    val info = providers.firstOrNull { it.name == currentAccount.provider }?.adminKey
                    currentAccount.adminError?.let { MutedText(it, small = true) }
                    if (info?.supported == true) {
                        if (info.set) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MutedText(string(Res.string.admin_key_set, info.hint ?: ""), small = true, modifier = Modifier.weight(1f))
                                LinkButton(string(Res.string.admin_key_remove), onClick = { onRemoveAdminKey(currentAccount.provider) }, danger = true)
                            }
                        } else {
                            LinkButton(string(Res.string.admin_key_add), onClick = { onAdminKey(currentAccount.provider) })
                        }
                    }
                }
            }
            else -> {
                MutedText(string(Res.string.usage_no_limits_app_tokens), small = true)
                Stat(string(Res.string.usage_today), fmtNumber(usage.today.sum))
                Stat(string(Res.string.usage_total), fmtNumber(usage.total.sum))
            }
        }
        if (parts.otherCount > 0) {
            AnimatedVisibility(
                visible = open,
                enter = if (reduceMotion) EnterTransition.None else expandVertically() + fadeIn(),
                exit = if (reduceMotion) ExitTransition.None else shrinkVertically() + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    parts.others.forEach { sub ->
                        HorizontalDivider(color = c.line, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                        val label = chat.kelo.app.chat.providerLabel(sub.provider, providers)
                        val role = roleWord(sub.role)
                        val title = if (role != null) "$label · $role" else label
                        Text(title, style = MaterialTheme.typography.titleSmall, color = c.ink)
                        if (sub.error != null) {
                            MutedText(sub.error!!, small = true)
                        } else {
                            val models = string(Res.string.usage_group_models)
                            val joiner = string(Res.string.usage_group_joiner)
                            val groups = UsageLogic.groups(Usage(subscription = sub), model) { g ->
                                UsageLogic.groupName(g, { models.format(it) }, joiner)
                            }
                            UsageGroupsView(groups, full = false)
                        }
                    }
                    parts.otherAccounts.forEach { a ->
                        HorizontalDivider(color = c.line, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                        val label = a.displayName.ifBlank { chat.kelo.app.chat.providerLabel(a.provider, providers) }
                        val role = roleWord(a.role)
                        val title = if (role != null) "$label · $role" else label
                        Text(title, style = MaterialTheme.typography.titleSmall, color = c.ink)
                        a.balance?.let { Stat(string(Res.string.balance_left), money(it, a.currency)) }
                        a.limitRemaining?.let { Stat(string(Res.string.balance_limit_left), money(it, a.currency) + (a.limit?.let { l -> " / " + money(l, a.currency) } ?: "")) }
                        a.usedToday?.let { Stat(string(Res.string.balance_used_today), money(it, a.currency)) }
                        a.usedWeek?.let { Stat(string(Res.string.balance_used_week), money(it, a.currency)) }
                        a.usedMonth?.let { Stat(string(Res.string.balance_used_month), money(it, a.currency)) }
                    }
                }
            }
            val chevronAngle by animateFloatAsState(
                targetValue = if (open) 180f else 0f,
                label = "limits-chevron",
            )
            Row(
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (open) string(Res.string.usage_limits_fold) else string(Res.string.usage_limits_more, parts.otherCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted,
                )
                Icon(
                    KeloIcons.ChevronDown,
                    contentDescription = null,
                    tint = c.muted,
                    modifier = Modifier.size(16.dp).rotate(chevronAngle),
                )
            }
        }
    }
}

/** All the limits of the chosen model, or (when it reports none) the tokens this app has used. */
@Composable
fun UsagePage(usage: Usage?, model: String, onRefresh: () -> Unit, onBack: () -> Unit) {
    val sessions = chat.kelo.app.LocalAppServices.current.sessions
    val vm = chat.kelo.app.ui.platform.keloViewModel("my-usage") { UsageViewModel(sessions) }
    val providers by vm.providers.collectAsStateWithLifecycle()
    var adminFor by rememberSaveable { mutableStateOf<String?>(null) }
    var removeFor by rememberSaveable { mutableStateOf<String?>(null) }
    SubPage(string(Res.string.usage_title), onBack) {
        UsageByModelCard()
        LimitsSection(
            usage = usage,
            model = model,
            providers = providers,
            onRefresh = onRefresh,
            onAdminKey = { adminFor = it },
            onRemoveAdminKey = { removeFor = it },
        )
    }
    if (adminFor != null) {
        AdminKeySheet(adminFor!!, vm, onDone = { adminFor = null; onRefresh() }, onDismiss = { adminFor = null; vm.clearAdminNote() })
    }
    if (removeFor != null) {
        ConfirmDialog(
            string(Res.string.admin_key_remove_q),
            string(Res.string.admin_key_remove),
            onYes = { vm.removeAdminKey(removeFor!!) { onRefresh() }; removeFor = null },
            onDismiss = { removeFor = null },
        )
    }
}

private fun adminHowTo(provider: String): StringResource = when (provider) {
    "openai" -> Res.string.admin_key_how_openai
    "anthropic" -> Res.string.admin_key_how_anthropic
    "openrouter" -> Res.string.admin_key_how_openrouter
    else -> Res.string.admin_key_how_openai
}

@Composable
private fun AdminKeySheet(provider: String, vm: UsageViewModel, onDone: () -> Unit, onDismiss: () -> Unit) {
    val c = Kelo.colors
    var text by rememberSaveable { mutableStateOf("") }
    val busy by vm.adminBusy.collectAsStateWithLifecycle()
    val note by vm.adminNote.collectAsStateWithLifecycle()
    KeloModal(onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(string(Res.string.admin_key_title), style = MaterialTheme.typography.titleMedium, color = c.ink)
            MutedText(string(adminHowTo(provider)), small = true)
            MutedText(string(Res.string.admin_key_safety), small = true)
            KeloTextField(
                value = text,
                onValueChange = { text = it; vm.clearAdminNote() },
                placeholder = string(Res.string.model_key_paste),
                password = true,
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
            )
            note?.let { ErrorText(string(it), small = true) }
            PrimaryButton(string(Res.string.admin_key_save), onClick = { vm.saveAdminKey(provider, text) { onDone() } }, enabled = text.isNotBlank() && !busy)
        }
    }
}

/** Five model colours (mock-up v6), light and dark. */
private val MODEL_LIGHT = listOf(0xFF3B6FD8, 0xFFE0913A, 0xFF3AA57A, 0xFF9B6AD6, 0xFFC9506A).map { Color(it) }
private val MODEL_DARK = listOf(0xFF6C97EE, 0xFFF0A85A, 0xFF5CC396, 0xFFB48BEA, 0xFFE0718A).map { Color(it) }

/** My → Usage: the period's tokens (fresh input + output) as one number, one bar by model and its legend (design A, mock-up v6). */
@Composable
fun UsageByModelCard() {
    val c = Kelo.colors
    val sessions = chat.kelo.app.LocalAppServices.current.sessions
    val vm = chat.kelo.app.ui.platform.rememberViewModel { UsageByModelViewModel(sessions) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val lang = textLocale().language
    val palette = if (c.dark) MODEL_DARK else MODEL_LIGHT
    MyCard {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            UsageLogic.PERIODS.forEach { d ->
                val on = d == ui.days
                Text(
                    if (d == 1) string(Res.string.usage_today) else string(Res.string.usage_days, d),
                    style = MaterialTheme.typography.labelMedium, color = if (on) c.card else c.muted,
                    modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(if (on) c.ink else Color.Transparent)
                        .border(1.dp, if (on) c.ink else c.line, RoundedCornerShape(99.dp))
                        .clickable(role = Role.Tab) { vm.pick(d) }.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        val s = ui.summary
        when {
            s == null && ui.failed -> MutedText(string(Res.string.usage_models_failed), small = true)
            s == null -> chat.kelo.app.ui.components.SkeletonLines(3)
            s.total.calls == 0L -> MutedText(string(Res.string.usage_models_empty), small = true)
            else -> {
                val t = s.total
                val big = UsageLogic.used(t.inputTokens, t.outputTokens, t.cacheReadTokens)
                Column {
                    Text(string(Res.string.usage_jobs_tokens, UsageLogic.compactTokens(big, lang)), fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = c.ink)
                    Text(string(Res.string.usage_replies, t.calls), style = MaterialTheme.typography.bodySmall, color = c.muted)
                    if (t.cacheReadTokens > 0) Text(string(Res.string.usage_cache_left_out, UsageLogic.compactTokens(t.cacheReadTokens, lang)),
                        style = MaterialTheme.typography.labelSmall, color = c.muted)
                }
                val shares = UsageLogic.modelShares(s.models)
                if (shares.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(c.line)) {
                        shares.forEachIndexed { i, sh ->
                            if (sh.tokens > 0) Box(Modifier.weight(sh.tokens.toFloat()).fillMaxHeight().background(palette[i % palette.size]))
                        }
                    }
                    Text(string(Res.string.usage_models_caption, UsageLogic.compactTokens(big, lang)), style = MaterialTheme.typography.bodySmall, color = c.muted)
                    shares.forEachIndexed { i, sh ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(palette[i % palette.size]))
                            Text(
                                if (sh.others > 0) string(Res.string.usage_models_others, sh.others)
                                else chat.kelo.app.chat.ModelNames.display(sh.model, null),
                                style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(1f),
                            )
                            Text(UsageLogic.compactTokens(sh.tokens, lang), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = c.ink)
                            Text("${sh.percent}%", style = MaterialTheme.typography.labelSmall, color = c.muted)
                        }
                    }
                }
            }
        }
    }
}

/** "$0.19", "-$0.19", "¥12.30" in the app's language: two decimals, the currency's own symbol ([NumberText.money]). */
@Composable
private fun money(v: Double, currency: String): String = NumberText.money(v, currency, appLocale())

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = Kelo.colors.ink, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Bold, color = Kelo.colors.ink)
    }
}
