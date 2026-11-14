package chat.kelo.app.chat

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import chat.kelo.app.ui.platform.windowHeight
import chat.kelo.app.ui.platform.formFactor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.theme.Kelo

/**
 * One choice of a [SelectField]. [rank] orders a sorted list (lower first: ready providers are 0), [sortKey] is what it is sorted by
 * within its rank (the name without marks like ✓); a [keep] option (none / choose… / 직접 입력…) is always shown, whatever is searched,
 * and a sorted list keeps it at the top.
 */
data class SelectOption(
    val value: String,
    val text: String,
    val enabled: Boolean = true,
    val rank: Int = 1,
    val sortKey: String? = null,
    val keep: Boolean = false,
    /** 0: the main list; 1: below a divider at the very end (unofficial providers) */
    val group: Int = 0,
)

/** How a [SelectField] lays out its options; kept apart from the UI so it can be tested. */
object SelectRules {
    /** Up to this many options open as a menu under the field; more open a sheet that scrolls, with a search field. */
    const val MENU_MAX = 10

    fun useSheet(count: Int): Boolean = count > MENU_MAX

    /**
     * The options shown for [query]: those whose text or value contains it (any case), plus every [SelectOption.keep] one. With
     * [sorted] the kept ones come first, then by rank, then alphabetically (any case); otherwise the given order stays.
     */
    fun arrange(options: List<SelectOption>, query: String, sorted: Boolean): List<SelectOption> {
        val q = query.trim()
        val shown = if (q.isEmpty()) options
        else options.filter { it.keep || it.text.contains(q, ignoreCase = true) || it.value.contains(q, ignoreCase = true) }
        val (kept, rest) = shown.partition { it.keep }
        val ordered = if (!sorted) rest
        else rest.sortedWith(compareBy<SelectOption> { it.rank }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.sortKey ?: it.text })
        // a later group always goes to the very end, whatever the order inside it (sortedBy is stable)
        return if (!sorted && rest.none { it.group > 0 }) shown else kept + ordered.sortedBy { it.group }
    }

    /** Whether a divider goes before [index] of an arranged list: the first option of a later group. */
    fun dividerBefore(arranged: List<SelectOption>, index: Int): Boolean =
        index > 0 && !arranged[index].keep && arranged[index].group > arranged[index - 1].group

    /** Whether the search found nothing (only the kept options are left). */
    fun nothingFound(shown: List<SelectOption>, query: String): Boolean = query.isNotBlank() && shown.none { !it.keep }
}

/**
 * A <select>: a field showing the chosen option. A short list opens as a menu under it (never taller than the space between the
 * status bar and the navigation bar); a long one (the ~40 providers) opens a sheet that scrolls to its last option above the
 * navigation bar, with a search field, ready options first ([sorted]), the chosen one marked, and options that cannot be chosen
 * shown greyed out.
 */
@Composable
fun SelectField(
    label: String, options: List<SelectOption>, selected: String, sorted: Boolean = false, groupLabel: String? = null,
    /** The chosen option's words wrap onto more lines instead of being cut (long country or region names) */
    wrap: Boolean = false,
    onSelect: (String) -> Unit,
) {
    val c = Kelo.colors
    var open by remember { mutableStateOf(false) }
    val shown = options.find { it.value == selected }?.text ?: selected
    val sheet = SelectRules.useSheet(options.size)
    Box {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, c.line),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = c.card, contentColor = c.ink),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(
                string(Res.string.chat_chip_menu, shown), modifier = Modifier.weight(1f),
                maxLines = if (wrap) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (!sheet) {
            DropdownMenu(
                expanded = open, onDismissRequest = { open = false }, containerColor = c.card,
                modifier = Modifier.heightIn(max = safeHeight() / 2),
            ) {
                val arranged = SelectRules.arrange(options, "", sorted)
                arranged.forEachIndexed { i, o ->
                    if (SelectRules.dividerBefore(arranged, i)) GroupDivider(groupLabel)
                    val on = o.value == selected
                    DropdownMenuItem(
                        text = { Text(o.text, color = if (o.enabled) c.ink else c.muted, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal) },
                        trailingIcon = if (on) ({ Icon(KeloIcons.Check, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp)) }) else null,
                        enabled = o.enabled,
                        onClick = { open = false; onSelect(o.value) },
                        modifier = Modifier.semantics { this.selected = on },
                    )
                }
            }
        }
    }
    if (sheet && open) SelectSheet(label, options, selected, sorted, groupLabel, onClose = { open = false }, onSelect = { open = false; onSelect(it) })
}

/** "──── 비공식 지원 ────": a line with a label in the middle, before the options of a later group. */
@Composable
private fun GroupDivider(label: String?) {
    val c = Kelo.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.HorizontalDivider(Modifier.weight(1f), color = c.line)
        if (label != null) Text(label, style = MaterialTheme.typography.labelSmall, color = c.muted, modifier = Modifier.padding(horizontal = 10.dp))
        androidx.compose.material3.HorizontalDivider(Modifier.weight(1f), color = c.line)
    }
}

/** The window's height between the status bar and the navigation bar. */
@Composable
private fun safeHeight(): Dp {
    val window = windowHeight()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return (window - top - bottom).coerceAtLeast(200.dp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectSheet(
    label: String, options: List<SelectOption>, selected: String, sorted: Boolean, groupLabel: String?, onClose: () -> Unit, onSelect: (String) -> Unit,
) {
    val c = Kelo.colors
    var query by remember { mutableStateOf("") }
    val shown = remember(options, query, sorted) { SelectRules.arrange(options, query, sorted) }
    // opens with the chosen option in view
    val list = rememberLazyListState(initialFirstVisibleItemIndex = SelectRules.arrange(options, "", sorted).indexOfFirst { it.value == selected }.coerceAtLeast(0))
    // the sheet never reaches under the status bar (the handle and title take about 56dp of it); its own bottom padding keeps the
    // last option above the navigation bar (and above the keyboard while searching)
    val maxHeight = safeHeight() - 56.dp
    chat.kelo.app.ui.components.KeloModal(
        onDismiss = onClose,
        // like the model sheet: never above the agent's name tag, however long the list (a desktop dialog floats in the middle: no offset)
        modifier = formFactor(phone = Modifier.padding(top = ModelChooserRules.sheetTop(chat.kelo.app.ui.platform.headerHeight())), desktop = Modifier),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).padding(bottom = 8.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            KeloTextField(
                query, { query = it }, string(Res.string.select_search),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), maxLength = 80,
            )
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), state = list) {
                itemsIndexed(shown, key = { _, o -> o.value }) { i, o ->
                    if (SelectRules.dividerBefore(shown, i)) GroupDivider(groupLabel)
                    SelectRow(o, on = o.value == selected, onClick = { onSelect(o.value) })
                }
                if (SelectRules.nothingFound(shown, query)) {
                    item(key = "__none__") {
                        Text(
                            string(Res.string.select_no_match), style = MaterialTheme.typography.bodyMedium, color = c.muted,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectRow(o: SelectOption, on: Boolean, onClick: () -> Unit) {
    val c = Kelo.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = o.enabled, role = Role.Button, onClick = onClick)
            .semantics { selected = on }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            o.text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            color = when {
                !o.enabled -> c.muted.copy(alpha = 0.6f)
                on -> c.accent
                else -> c.ink
            },
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (on) Icon(KeloIcons.Check, contentDescription = null, tint = c.accent, modifier = Modifier.padding(start = 8.dp).size(20.dp))
    }
}
