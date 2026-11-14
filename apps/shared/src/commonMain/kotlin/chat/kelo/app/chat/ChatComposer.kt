package chat.kelo.app.chat

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import chat.kelo.app.ui.platform.FormFactor
import chat.kelo.app.ui.platform.LocalFormFactor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.theme.Kelo
import chat.kelo.app.ui.theme.LocalReduceMotion
import chat.kelo.app.ui.theme.Motion
import chat.kelo.app.ui.theme.pressShrink

/** The composer's own space above its card (Composer: padding top). */
val COMPOSER_TOP = 6.dp

// ---- the composer: one rounded box with the text on top and, under it, "+", the model chip, the mic and 보내기

/** The model chip inside the composer: "<model> <thinking level>"; opens the model chooser. Main model only, never the fallback. */
@Composable
private fun ModelChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Kelo.colors
    val source = remember { MutableInteractionSource() }
    val description = string(Res.string.chat_model_settings)
    Row(
        modifier
            .clip(RoundedCornerShape(99.dp))
            .pressShrink(source)
            .pointerHoverIcon(PointerIcon.Hand)   // a mouse shows a hand over the chip (a no-op on touch)
            .clickable(interactionSource = source, indication = ripple(), role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$description: $text" }
            .heightIn(min = 36.dp)
            .padding(start = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text, color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
        Icon(KeloIcons.ChevronRight, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = 90f })
    }
}

@Composable
fun Composer(
    input: String, busy: Boolean, canSend: Boolean, focusRequester: FocusRequester, onFocus: (Boolean) -> Unit,
    onInput: (String) -> Unit, onSend: () -> Unit, onStop: () -> Unit, onAttach: () -> Unit,
    chipText: String, onChip: () -> Unit,
    /** The app's own modifier for the text field (the phone: pictures pasted from the keyboard) */
    fieldModifier: Modifier = Modifier,
    /** The mic next to send, if the app has one */
    mic: (@Composable (field: () -> TextFieldValue, setField: (TextFieldValue) -> Unit) -> Unit)? = null,
    /** The desktop's Enter or Tab while a "/" list shows: picks its top command; true = the key was used */
    onPickTop: (() -> Boolean)? = null,
) {
    val c = Kelo.colors
    var focused by remember { mutableStateOf(false) }
    val desktop = LocalFormFactor.current == FormFactor.Desktop
    val border by androidx.compose.animation.animateColorAsState(
        if (focused) c.accent else c.line, Motion.small(LocalReduceMotion.current), label = "composer-border",
    )
    Column(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = COMPOSER_TOP, bottom = 10.dp)
            .clip(RoundedCornerShape(24.dp)).background(c.card).border(1.dp, border, RoundedCornerShape(24.dp)),
    ) {
        // a TextFieldState field: the one kind that takes pictures from the keyboard (Gboard's and Samsung's clipboard screenshot above
        // the keys), from a long-press Paste and from a drop; text put in from outside (수정해서 다시 보내기, a share) puts the cursor
        // at its end
        val state = rememberTextFieldState(input)
        LaunchedEffect(input) {
            if (state.text.toString() != input) state.edit { replace(0, length, input); placeCursorAtEnd() }
        }
        LaunchedEffect(state) {
            snapshotFlow { state.text.toString() }.collect { if (it != input) onInput(it) }
        }
        val muted = c.muted
        val placeholder = string(Res.string.chat_input_placeholder)
        val textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink)
        BasicTextField(
            state = state,
            // a little more room above the first line: the "↓" rests on the card's top-end strip and never covers the text
            modifier = Modifier.padding(top = ChatOverlayRules.fieldTopExtra(16f).dp)
                .fillMaxWidth().heightIn(min = 48.dp).focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
                .then(fieldModifier)
                // the desktop sends with Enter (Shift+Enter is a new line); the phone's Enter is a new line
                .then(if (desktop) Modifier.onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && !e.isShiftPressed &&
                        (e.key == Key.Tab || e.key == Key.Enter || e.key == Key.NumPadEnter) && onPickTop?.invoke() == true) {
                        return@onPreviewKeyEvent true
                    }
                    if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && !e.isShiftPressed) {
                        if (!busy && canSend) onSend()
                        true
                    } else false
                } else Modifier),
            textStyle = textStyle,
            cursorBrush = SolidColor(c.accent),
            lineLimits = TextFieldLineLimits.MultiLine(1, 6),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            decorator = { inner ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    if (state.text.isEmpty()) Text(placeholder, style = textStyle, color = muted)
                    inner()
                }
            },
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(onClick = onAttach) {
                Icon(KeloIcons.Plus, contentDescription = string(Res.string.chat_attach), tint = c.muted)
            }
            // the shortcut hint only where there is room for it (a narrow window keeps the chip and the button whole)
            if (desktop) androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                if (maxWidth > 220.dp) Text(string(Res.string.desktop_enter_hint), style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
            // the chip takes what is left (ellipsized), so a long model name never squeezes 보내기 on a narrow screen
            if (desktop) ModelChip(chipText, onChip, Modifier.widthIn(max = 260.dp))
            else Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { ModelChip(chipText, onChip, Modifier.widthIn(max = 220.dp)) }
            // the mic, left of 보내기: spoken words go into the field at the cursor
            mic?.invoke({ TextFieldValue(state.text.toString(), state.selection) }, { v -> state.edit { replace(0, length, v.text); selection = v.selection } })
            val sendSource = remember { MutableInteractionSource() }
            IconButton(
                onClick = if (busy) onStop else onSend,
                enabled = canSend,
                interactionSource = sendSource,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = c.ink, contentColor = c.bg, disabledContainerColor = c.disabledFill, disabledContentColor = c.disabledInk,
                ),
                modifier = Modifier.pressShrink(sendSource),
            ) {
                Icon(
                    if (busy) StopIcon else KeloIcons.Send,
                    contentDescription = string(if (busy) Res.string.chat_stop else Res.string.chat_send),
                )
            }
        }
    }
}

/** A filled square. */
private val StopIcon: ImageVector by lazy {
    ImageVector.Builder("stop", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(8f, 7f); lineTo(16f, 7f); quadTo(17f, 7f, 17f, 8f); lineTo(17f, 16f); quadTo(17f, 17f, 16f, 17f)
            lineTo(8f, 17f); quadTo(7f, 17f, 7f, 16f); lineTo(7f, 8f); quadTo(7f, 7f, 8f, 7f); close()
        }
    }.build()
}
