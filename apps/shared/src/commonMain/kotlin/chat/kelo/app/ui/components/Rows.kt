package chat.kelo.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.isSpecified
import chat.kelo.app.res.*
import chat.kelo.app.ui.platform.FormFactor
import chat.kelo.app.ui.platform.LocalBackHandler
import chat.kelo.app.ui.platform.LocalFormFactor
import chat.kelo.app.ui.platform.LocalPageFrame
import chat.kelo.app.ui.platform.formFactor
import chat.kelo.app.ui.text.plural
import chat.kelo.app.ui.text.string
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.runtime.remember
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.theme.Kelo

/* The building blocks of 마이 and its pages: a heading above a group, rows inside a group, cards, and a sub page with a back arrow. */

/** A group's heading, in the accent colour (Material 3 settings style). */
@Composable
fun SectionLabel(text: String) {
    Text(
        text, color = Kelo.colors.accent, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** Collects the rows of a [MenuGroup]. */
class GroupScope {
    internal val rows = mutableListOf<@Composable () -> Unit>()
    fun row(content: @Composable () -> Unit) {
        rows += content
    }
}

/** Rows straight on the page, one under the other (no card around them). */
@Composable
fun MenuGroup(build: GroupScope.() -> Unit) {
    val rows = GroupScope().apply(build).rows
    if (rows.isEmpty()) return
    Column(Modifier.fillMaxWidth()) { rows.forEach { it() } }
}

/**
 * A settings row: an icon, the name, and under it the current value in a smaller line (a Material 3 two-line list item). A danger
 * row is red. [badge]: a count of new things in a quiet pill at the end (red is kept for what waits for the owner: WorkBadge).
 */
@Composable
fun MenuRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    value: String? = null,
    valueColor: Color? = null,
    danger: Boolean = false,
    /** A count in a quiet pill (new things to look at; nothing to act on, so not red) */
    badge: Int? = null,
) {
    val c = Kelo.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (danger) c.danger else c.muted, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            // a file name or an id never wraps in the middle of a word: one line, shortened in the middle when it is long
            Text(
                chat.kelo.app.ui.components.TokenText.fit(label), color = if (danger) c.danger else c.ink, style = MaterialTheme.typography.bodyLarge,
                maxLines = if (chat.kelo.app.ui.components.TokenText.isToken(label)) 1 else 2, overflow = TextOverflow.Ellipsis,
            )
            if (!value.isNullOrEmpty()) {
                Text(chat.kelo.app.ui.components.TokenText.fit(value), color = valueColor ?: c.muted, style = MaterialTheme.typography.bodySmall,
                    maxLines = formFactor(phone = 1, desktop = 2), overflow = TextOverflow.Ellipsis)
            }
        }
        if (badge != null && badge > 0) {
            val desc = plural(Res.plurals.count_new, badge, badge)
            Text(
                if (badge > 99) string(Res.string.count_badge_max) else badge.toString(), color = c.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(c.mine).padding(horizontal = 7.dp, vertical = 1.dp)
                    .semantics { contentDescription = desc },
            )
        }
    }
}

/** A card on a page: rounded 12dp corners and a hairline. */
@Composable
fun MyCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Kelo.colors
    val source = remember { MutableInteractionSource() }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.card)
            .border(1.dp, c.line, RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(interactionSource = source, indication = ripple(), role = Role.Button, onClick = onClick) else Modifier)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** One block inside a card; blocks after the first get a hairline above. */
@Composable
fun KeyBox(first: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!first) HorizontalDivider(color = Kelo.colors.line, modifier = Modifier.padding(bottom = 4.dp))
        content()
    }
}

/** A muted centred line for an empty list ("아직 올린 문서가 없어요."). */
@Composable
fun EmptyNote(text: String) {
    Text(
        text, color = Kelo.colors.muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
    )
}

/**
 * A sub page: the back arrow and title on top, and the content scrolling under it. The phone's back gesture steps out too (the app
 * root's [LocalBackHandler]); in a window the page sits below the app's header, centred and at most as wide as [LocalPageFrame]
 * says, and Escape steps back as the arrow does.
 */
@Composable
fun SubPage(
    title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Kelo.colors
    LocalBackHandler.current(onBack)
    when (LocalFormFactor.current) {
        // full screen (no app header or tabs): keep clear of the status bar, the navigation bar and the keyboard
        FormFactor.Phone -> Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding()) {
            SubPageBody(title, onBack, actions, content)
        }
        FormFactor.Desktop -> {
            val frame = LocalPageFrame.current
            Box(
                Modifier.fillMaxSize().background(c.bg).onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onBack(); true } else false
                },
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(Modifier.then(if (frame.maxWidth.isSpecified) Modifier.widthIn(max = frame.maxWidth) else Modifier).fillMaxWidth().padding(top = frame.topInset)) {
                    SubPageBody(title, onBack, actions, content)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.SubPageBody(
    title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    BackTitleBar(title, onBack, actions = actions)
    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** A yes/no question. [danger] colours the yes button red; a [title] (the desktop pages use one) heads the text. */
@Composable
fun ConfirmDialog(text: String, yes: String, onYes: () -> Unit, onDismiss: () -> Unit, danger: Boolean = true, title: String? = null) {
    val c = Kelo.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = title?.let { { Text(it, color = c.ink) } },
        text = { Text(text, color = c.ink) },
        confirmButton = { TextButton(onClick = { onDismiss(); onYes() }) { Text(yes, color = if (danger) c.danger else c.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(string(Res.string.cancel), color = c.ink) } },
    )
}

/** A small rounded word (red when [denied]). */
@Composable
fun Badge(text: String, denied: Boolean = false) {
    val c = Kelo.colors
    val (bg, fg) = when {
        !denied -> c.bubble to c.muted
        else -> c.dangerSoft to c.dangerInk
    }
    Text(
        text, color = fg, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(bg).padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

/** A small tinted label ("지금 쓰는 모델"). */
@Composable
fun Tag(text: String) {
    val c = Kelo.colors
    Text(
        text, color = c.ink, fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(c.mine).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** A thin track with a filled part; red when [hot]. */
@Composable
fun Bar(fraction: Float, hot: Boolean) {
    val c = Kelo.colors
    Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(8.dp).clip(RoundedCornerShape(99.dp)).background(c.bubble)) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(8.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(if (hot) c.danger else c.accent),
        )
    }
}

/** A bold line with an icon in front. */
@Composable
fun IconTitle(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = Kelo.colors.ink, modifier = Modifier.size(20.dp))
        Text(text, fontWeight = FontWeight.Bold, color = Kelo.colors.ink, style = MaterialTheme.typography.bodyLarge)
    }
}
