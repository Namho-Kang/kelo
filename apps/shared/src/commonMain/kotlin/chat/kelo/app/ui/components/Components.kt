package chat.kelo.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import chat.kelo.app.ui.theme.pressShrink
import chat.kelo.app.res.*
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.FormFactor
import chat.kelo.app.ui.platform.LocalFormFactor
import chat.kelo.app.ui.platform.LocalHaptics
import chat.kelo.app.ui.platform.formFactor
import chat.kelo.app.ui.text.string
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import chat.kelo.app.ui.theme.Kelo

private val Rounded12 = RoundedCornerShape(12.dp)

/** The filled main action: the accent colour with white text (a Material 3 filled button). */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Kelo.colors
    val wide = LocalFormFactor.current == FormFactor.Phone
    val source = remember { MutableInteractionSource() }
    Button(
        // the window's button is as wide as its text; the phone's fills the column
        onClick = onClick, enabled = enabled, modifier = modifier.then(if (wide) Modifier.fillMaxWidth() else Modifier).pressShrink(source), shape = ButtonShape,
        interactionSource = source,
        colors = ButtonDefaults.buttonColors(
            containerColor = c.accent, contentColor = c.onAccent,
            disabledContainerColor = c.disabledFill, disabledContentColor = c.disabledInk,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = formFactor(phone = 12.dp, desktop = 10.dp)),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Buttons are fully rounded (Material 3). */
private val ButtonShape = RoundedCornerShape(50)

/** A plain button: card background with a hairline border. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Kelo.colors
    val source = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.pressShrink(source), shape = ButtonShape, border = BorderStroke(1.dp, c.line),
        interactionSource = source,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = c.card, contentColor = c.ink, disabledContainerColor = c.card, disabledContentColor = c.disabledInk),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = formFactor(phone = 12.dp, desktop = 10.dp)),
    ) { Text(text, style = formFactor(phone = MaterialTheme.typography.bodyLarge, desktop = MaterialTheme.typography.bodyMedium)) }
}

/** A text action in the accent colour. */
@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false) {
    val c = Kelo.colors
    // a small corner rather than the pill: a link that wraps to two lines (a long site name, a long language) would lose the
    // corners of its first and last letters to the pill's round ends
    TextButton(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
        Text(text, color = if (danger) c.danger else c.accent, style = MaterialTheme.typography.bodySmall)
    }
}

/** A filter chip: outlined when off, accent-tinted with a check when on (Material 3). */
@Composable
fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Kelo.colors
    val source = remember { MutableInteractionSource() }
    val haptics = LocalHaptics.current
    OutlinedButton(
        onClick = { haptics.tick(); onClick() }, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, if (selected) c.accent.copy(alpha = 0f) else c.line),
        interactionSource = source, modifier = Modifier.pressShrink(source).androidxSemanticsSelected(selected),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) c.mine else c.card, contentColor = c.ink,
        ),
        contentPadding = PaddingValues(start = if (selected) 8.dp else 12.dp, end = 12.dp, top = formFactor(phone = 6.dp, desktop = 4.dp), bottom = formFactor(phone = 6.dp, desktop = 4.dp)),
    ) {
        if (selected) Icon(KeloIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp).padding(end = 2.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun Modifier.androidxSemanticsSelected(on: Boolean): Modifier =
    this.then(Modifier.semantics { selected = on })

/**
 * The hint inside an empty field. A field that holds one value (an address, a key, a code, a name, a number) is one line: its value
 * scrolls sideways and its hint never wraps onto a second line (it is cut with … instead). Only message-like fields
 * (`singleLine = false`) wrap. SingleLineFieldsTest checks every field of the app keeps to this.
 */
@Composable
fun FieldPlaceholder(text: String, singleLine: Boolean = true) {
    if (singleLine) {
        Text(text, color = Kelo.colors.muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    } else {
        Text(text, color = Kelo.colors.muted)
    }
}

/**
 * A one-value field with its button (저장, 추가, …). Where there is room the button sits beside the field; in a narrow column (a phone,
 * [stackBelow]) it goes under the field, so the field keeps the whole width instead of being squeezed.
 */
@Composable
fun FieldWithAction(
    modifier: Modifier = Modifier,
    stackBelow: Dp = FIELD_ACTION_STACK_BELOW,
    field: @Composable (Modifier) -> Unit,
    action: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth < stackBelow) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                field(Modifier.fillMaxWidth())
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { action() }
            }
        } else {
            Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                field(Modifier.weight(1f))
                action()
            }
        }
    }
}

/** Below this width a field's button goes under it (FieldWithAction): every phone in portrait. */
val FIELD_ACTION_STACK_BELOW = 440.dp

/** Input: card background, hairline border, accent when focused. */
@Composable
fun KeloTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    maxLength: Int = Int.MAX_VALUE,
    textStyle: androidx.compose.ui.text.TextStyle = androidx.compose.material3.LocalTextStyle.current,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val c = Kelo.colors
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(maxLength)) },
        modifier = modifier.fillMaxWidth(),
        placeholder = { FieldPlaceholder(placeholder, singleLine) },
        trailingIcon = trailingIcon,
        singleLine = singleLine, textStyle = textStyle,
        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
        shape = Rounded12,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = c.card, unfocusedContainerColor = c.card,
            focusedBorderColor = c.accent, unfocusedBorderColor = c.line, cursorColor = c.accent,
            focusedTextColor = c.ink, unfocusedTextColor = c.ink,
        ),
    )
}

/** .card */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Kelo.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(c.card)
            .border(1.dp, c.line, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** .center-card: the first-run screens' narrow column, a little below the top. Scrolls when the keyboard is up. */
@Composable
fun CenterCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Kelo.colors.bg)
            .safeDrawingPadding()
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 96.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/** A centred muted line ("불러오는 중…"), optionally with actions under it. */
@Composable
fun CenterMessage(text: String, actions: @Composable ColumnScope.() -> Unit = {}) {
    CenterCard {
        Text(text, color = Kelo.colors.muted, style = MaterialTheme.typography.bodyLarge)
        actions()
    }
}

@Composable
fun MutedText(text: String, modifier: Modifier = Modifier, small: Boolean = false) {
    Text(
        text, modifier = modifier, color = Kelo.colors.muted,
        style = if (small) MaterialTheme.typography.bodySmall else formFactor(phone = MaterialTheme.typography.bodyLarge, desktop = MaterialTheme.typography.bodyMedium),
    )
}

@Composable
fun ErrorText(text: String, modifier: Modifier = Modifier, small: Boolean = false) {
    Text(
        text, modifier = modifier, color = Kelo.colors.danger,
        style = if (small) MaterialTheme.typography.bodySmall else formFactor(phone = MaterialTheme.typography.bodyLarge, desktop = MaterialTheme.typography.bodyMedium),
    )
}

/** [template] with one "%1$s" filled by [arg] in bold (or as code). */
@Composable
fun emphasized(template: String, arg: String, code: Boolean = false): AnnotatedString {
    val c = Kelo.colors
    val at = template.indexOf("%1\$s")
    return buildAnnotatedString {
        if (at < 0) {
            append(template); return@buildAnnotatedString
        }
        append(template.substring(0, at))
        val style = if (code) SpanStyle(fontFamily = FontFamily.Monospace, background = c.bubble)
        else SpanStyle(fontWeight = FontWeight.Bold, color = c.ink)
        withStyle(style) { append(arg) }
        append(template.substring(at + 4))
    }
}

/**
 * The top of a full-screen page: a back arrow and the title next to it, left-aligned (a Material 3 small top app bar). [actions]
 * go at the end.
 */
@Composable
fun BackTitleBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    val c = Kelo.colors
    val back = string(Res.string.back)
    Row(
        // the window's bar grows with a title that wraps (German, Thai); the phone's is one fixed line
        modifier.fillMaxWidth().then(formFactor(phone = Modifier.height(64.dp), desktop = Modifier.heightIn(min = 56.dp))).padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(KeloIcons.ArrowLeft, contentDescription = back, tint = c.ink)
        }
        Text(
            title, modifier = Modifier.weight(1f).padding(start = 4.dp, end = 8.dp),
            style = MaterialTheme.typography.titleLarge, color = c.ink, maxLines = formFactor(phone = 1, desktop = 2),
            overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}
