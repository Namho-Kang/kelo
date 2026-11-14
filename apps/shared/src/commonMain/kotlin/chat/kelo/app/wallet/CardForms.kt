package chat.kelo.app.wallet

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.ui.platform.rememberViewModel
import chat.kelo.app.keyguard.KeyguardNeededDialog
import chat.kelo.app.ui.platform.LocalBackHandler
import androidx.compose.runtime.LaunchedEffect

import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import chat.kelo.app.ui.components.PrimaryButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.core.ApiError
import chat.kelo.app.ui.components.MyCard
import chat.kelo.app.ui.components.SubPage
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.LocalHaptics
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.launch
import java.time.YearMonth

/** Shows raw card digits grouped ("4242 4242 4242 4242") while the field keeps only the digits. */
internal object CardNumberTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val grouped = CardNumber.group(raw)
        return TransformedText(AnnotatedString(grouped), object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = CardNumber.groupedOffset(raw, offset).coerceIn(0, grouped.length)
            override fun transformedToOriginal(offset: Int): Int = CardNumber.rawOffset(raw, offset).coerceIn(0, raw.length)
        })
    }
}

/** Shows "0428" as "04/28". */
internal object ExpiryTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val shown = Expiry.format(raw)
        return TransformedText(AnnotatedString(shown), object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = if (offset > 2 && raw.length > 2) offset + 1 else offset
            override fun transformedToOriginal(offset: Int): Int = if (offset > 2 && raw.length > 2) offset - 1 else offset.coerceAtMost(raw.length)
        })
    }
}

/** A labelled input of the card forms. [error] shows under it in red. */
@Composable
internal fun WalletField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    transformation: VisualTransformation = VisualTransformation.None,
    error: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val c = Kelo.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // a fixed height: Korean and Latin labels side by side (유효기간 / CVC) keep their fields level
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, modifier = Modifier.padding(start = 2.dp).height(20.dp))
        OutlinedTextField(
            value = value, onValueChange = onChange, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { chat.kelo.app.ui.components.FieldPlaceholder(placeholder) },
            visualTransformation = transformation, keyboardOptions = keyboard, isError = error != null,
            trailingIcon = trailing,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = c.card, unfocusedContainerColor = c.card, errorContainerColor = c.card,
                focusedBorderColor = c.accent, unfocusedBorderColor = c.line, errorBorderColor = c.danger, cursorColor = c.accent,
                focusedTextColor = c.ink, unfocusedTextColor = c.ink, errorTextColor = c.ink,
            ),
        )
        if (error != null) ErrorText(error, small = true, modifier = Modifier.padding(start = 2.dp))
    }
}

private val DIGITS = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next)

/** The save button (a plain primary button), "저장하는 중…" while it works. */
@Composable
internal fun SaveButton(text: String, enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    PrimaryButton(if (busy) string(Res.string.wallet_saving) else text, onClick = onClick, enabled = enabled && !busy)
}

/** The number field's checks, shown once a full number is typed. */
@Composable
private fun numberError(number: String, mustEnd: String?): String? {
    val n = CardNumber.digits(number)
    val brand = CardNumber.brand(n)
    val complete = n.length >= (if (brand == CardBrand.AMEX) 15 else 16) || n.length == CardNumber.MAX_DIGITS
    return when {
        !complete -> null
        !CardNumber.valid(n) -> string(Res.string.wallet_number_invalid)
        mustEnd != null && CardNumber.last4(n) != mustEnd -> string(Res.string.wallet_number_other_card, mustEnd)
        else -> null
    }
}

/**
 * 카드 등록, in steps over a live card face: 1 the number (its brand shows as it is typed), 2 expiry and CVC, 3 the holder's name
 * and postal code (both optional), then the notice to tick and Save. The number and CVC are kept only in memory (never in saved
 * state or on disk; [WalletPlaces] holds them through a lock for up to ten minutes), sealed with this phone's key after the owner's
 * check, and cleared once sent. Back steps back a step.
 */
@Composable
internal fun AddCardPage(onBack: () -> Unit, onSaved: () -> Unit) {
    var back by remember { mutableStateOf<(() -> Boolean)?>(null) }
    SubPage(string(Res.string.wallet_add_title), onBack = { if (back?.invoke() != true) onBack() }) {
        AddCardForm(onSaved = { onSaved() }, stepBack = { back = it })
    }
}

/**
 * The card form itself, used by 카드 등록 and, [inline], by a card entry in the chat (request_card) and the Keychain's waiting list.
 * [stepBack] gets a function that goes back one step (true) or says there is none (false). Inline, nothing is kept through the
 * wallet's lock or taken from a held-back message: the draft lives only as long as the card on screen. [onSaved] gets the new card
 * (its nickname and last four digits; never the number).
 */
@Composable
internal fun ColumnScope.AddCardForm(
    onSaved: (WalletCard) -> Unit, stepBack: ((() -> Boolean)?) -> Unit = {}, inline: Boolean = false,
) {
    val services = LocalAppServices.current
    val vm = rememberViewModel { AddCardViewModel(services.sessions, services.cardKeys, services.walletPlaces, services.walletConsent, services.secretHandoff, inline) }
    val ui by vm.state.collectAsStateWithLifecycle()
    HandleWalletEvents(vm.events)
    LaunchedEffect(vm) { vm.saved.collect { card -> onSaved(card) } }
    val c = Kelo.colors
    val draft = ui.draft
    val step = draft.step
    val holder = draft.holder
    val number = draft.number
    val expiry = draft.expiry
    val cvc = draft.cvc
    val postal = draft.postal
    // the notice is acknowledged each time a card is added (never remembered as "seen"; kept only through a lock mid-flow)
    val agreed = draft.agreed
    val busy = ui.busy
    val problem = ui.problem
    fun set(d: AddCardDraft) = vm.set(d)
    val prev = AddCardSteps.prev(step)
    if (!inline && prev != null && !busy) LocalBackHandler.current { vm.stepBack() }
    val goBack by androidx.compose.runtime.rememberUpdatedState { vm.stepBack() }
    DisposableEffect(Unit) { stepBack { goBack() }; onDispose { stepBack(null) } }

    val now = YearMonth.now()
    val brand = CardNumber.brand(number)
    val exp = Expiry.check(expiry, now)
    val expError = when (exp) {
        ExpiryCheck.BadMonth -> string(Res.string.wallet_expiry_bad_month)
        ExpiryCheck.Expired -> string(Res.string.wallet_expiry_expired)
        ExpiryCheck.TooFar -> string(Res.string.wallet_expiry_too_far)
        else -> null
    }
    val texts = rememberSaveTexts()
    val scanner = services.cardScanner

    run {
        CardFaceLarge(
            brand, CardFaceRules.preview(number), holder,
            if (expiry.isEmpty()) string(Res.string.wallet_field_expiry_hint) else Expiry.format(expiry),
            modifier = Modifier.padding(horizontal = if (inline) 40.dp else 24.dp),
        )
        val (at, of) = AddCardSteps.position(step)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(string(stepTitle(step)), style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
            Text(string(Res.string.wallet_step_of, at, of), style = MaterialTheme.typography.labelLarge, color = c.muted)
        }
        LinearProgressIndicator(
            progress = { at / of.toFloat() }, modifier = Modifier.fillMaxWidth().height(4.dp),
            color = c.accent, trackColor = c.line, drawStopIndicator = {},
        )
        when (step) {
            AddStep.NUMBER -> {
                WalletField(
                    string(Res.string.wallet_field_number), number,
                    { set(draft.copy(number = CardNumber.digits(it).take(CardNumber.maxLength(CardNumber.brand(it))))) },
                    placeholder = string(Res.string.wallet_field_number_hint), keyboard = DIGITS, transformation = CardNumberTransformation,
                    error = numberError(number, null),
                    trailing = brandName(brand)?.let { name -> { Text(name, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(end = 12.dp)) } },
                )
                // 카드 스캔: fills the number and, when read, the expiry (never the CVC); the owner checks them on the next steps
                scanner?.Button(onResult = { scan -> set(CardScanFill.apply(draft, scan)) }, modifier = Modifier)
            }
            AddStep.DATES -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WalletField(
                    string(Res.string.wallet_field_expiry), expiry, { set(draft.copy(expiry = Expiry.clean(it))) },
                    placeholder = string(Res.string.wallet_field_expiry_hint), keyboard = DIGITS, transformation = ExpiryTransformation,
                    error = expError, modifier = Modifier.weight(1f),
                )
                WalletField(
                    string(Res.string.wallet_field_cvc), cvc, { set(draft.copy(cvc = Cvc.clean(it, brand))) },
                    placeholder = string(if (brand == CardBrand.AMEX) Res.string.wallet_field_cvc_hint4 else Res.string.wallet_field_cvc_hint3),
                    keyboard = DIGITS, transformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f),
                )
            }
            AddStep.OWNER -> {
                // the owner's name for the card: its title in the wallet, the approval cards and what Kelo calls it
                WalletField(
                    string(Res.string.wallet_nickname_field), draft.nickname, { set(draft.copy(nickname = it.take(60))) },
                    placeholder = string(Res.string.wallet_rename_hint),
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    error = if (Regex("(?:\\d[ -]?){12,}").containsMatchIn(draft.nickname)) string(Res.string.wallet_rename_no_digits) else null,
                )
                WalletField(
                    string(Res.string.wallet_field_holder), holder, { set(draft.copy(holder = it.take(80))) },
                    placeholder = string(Res.string.wallet_field_holder_hint),
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                )
                WalletField(
                    string(Res.string.wallet_field_postal), postal, { set(draft.copy(postal = it.take(16))) },
                    placeholder = string(Res.string.wallet_field_postal_hint),
                    keyboard = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                )
            }
            AddStep.CONFIRM -> {
                Text(string(Res.string.wallet_card_notice), style = MaterialTheme.typography.bodySmall, color = c.ink)
                AgreeRow(agreed, onAgree = { set(draft.copy(agreed = it)) })
            }
        }
        if (problem.isNotEmpty()) ErrorText(problem, small = true, modifier = Modifier.padding(horizontal = 4.dp))
        val canLeave = AddCardSteps.canLeave(step, number, expiry, cvc, agreed, now)
        if (step == AddStep.CONFIRM) {
            SaveButton(string(Res.string.wallet_save), enabled = canLeave, busy = busy, onClick = { vm.save(texts) })
        } else {
            PrimaryButton(string(Res.string.wallet_step_next), enabled = canLeave, onClick = { set(draft.copy(step = AddCardSteps.next(step))) })
        }
        if (inline && prev != null) chat.kelo.app.ui.components.LinkButton(string(Res.string.back), onClick = { goBack() })
    }
}

private fun stepTitle(s: AddStep): org.jetbrains.compose.resources.StringResource = when (s) {
    AddStep.NUMBER -> Res.string.wallet_step_number
    AddStep.DATES -> Res.string.wallet_step_dates
    AddStep.OWNER -> Res.string.wallet_step_owner
    AddStep.CONFIRM -> Res.string.wallet_step_confirm
}

/** "확인했어요" with a checkbox; the whole row toggles it. */
@Composable
internal fun AgreeRow(agreed: Boolean, onAgree: (Boolean) -> Unit) {
    val c = Kelo.colors
    Row(
        Modifier.fillMaxWidth()
            .agreeToggle(agreed, onAgree),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Checkbox(
            checked = agreed, onCheckedChange = null,
            colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = c.accent, checkmarkColor = c.onAccent, uncheckedColor = c.muted),
        )
        Text(string(Res.string.wallet_notice_agree), color = c.ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun Modifier.agreeToggle(value: Boolean, onChange: (Boolean) -> Unit): Modifier =
    this.then(Modifier.toggleable(value = value, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = onChange)).padding(vertical = 4.dp)

/** Why a card was not saved, as text resolved in composition (the save runs in a coroutine). */
class SaveWords(private val cancelled: String, private val noLock: String, private val keyLost: String, private val failed: String) {
    fun of(a: CardAuth): String = when (a) {
        is CardAuth.Ok -> ""
        CardAuth.Cancelled -> cancelled
        CardAuth.NoLock -> noLock
        CardAuth.KeyLost -> keyLost
        is CardAuth.Failed -> failed.replace("%1\$s", a.message)
    }
}

@Composable
internal fun rememberSaveWords(): SaveWords {
    val a = string(Res.string.wallet_save_cancelled)
    val b = string(Res.string.wallet_save_no_lock)
    val c = string(Res.string.wallet_save_key_lost)
    val d = string(Res.string.wallet_save_failed)
    return remember(a, b, c, d) { SaveWords(a, b, c, d) }
}

@Composable
internal fun rememberSaveTexts(): SaveTexts {
    val title = string(Res.string.wallet_auth_save_title)
    val subtitle = string(Res.string.wallet_auth_save_subtitle)
    val failedSend = string(Res.string.wallet_save_server)
    val words = rememberSaveWords()
    return remember(title, subtitle, failedSend, words) { SaveTexts(title, subtitle, failedSend, words) }
}

/**
 * The number and CVC of a saved card typed again: to make a new phone copy (a card this phone cannot open), or to turn auto-pay on
 * (the server keeps its own sealed copy then). The number must be the same card (same last 4 digits).
 */
@Composable
internal fun ReenterPage(card: WalletCard, autoPay: Boolean, onBack: () -> Unit, onSaved: () -> Unit) {
    val services = LocalAppServices.current
    val vm = rememberViewModel(card.id to autoPay) { ReenterViewModel(services.sessions, services.cardKeys, services.walletPlaces, services.walletConsent, card, autoPay) }
    val ui by vm.state.collectAsStateWithLifecycle()
    HandleWalletEvents(vm.events) { e -> if (e == WalletEvent.Saved) onSaved() }
    val draft = ui.draft
    val number = draft.number
    val cvc = draft.cvc
    val busy = ui.busy
    val problem = ui.problem
    fun set(d: ReenterDraft) = vm.set(d)
    val brand = CardNumber.brand(number).takeIf { number.isNotEmpty() } ?: CardBrand.of(card.brand)
    val ready = CardNumber.valid(number) && CardNumber.last4(number) == card.last4 && Cvc.valid(cvc, brand)
    val texts = rememberSaveTexts()
    val scanner = services.cardScanner
    if (ui.needKeyguard) KeyguardNeededDialog(onEnabled = { vm.dismissKeyguard(); vm.save(texts) }, onDismiss = vm::dismissKeyguard)

    SubPage(string(if (autoPay) Res.string.wallet_reenter_auto_title else Res.string.wallet_reenter_title), onBack) {
        Note(string(if (autoPay) Res.string.wallet_reenter_auto_body else Res.string.wallet_reenter_body, card.label))
        MyCard {
            WalletField(
                string(Res.string.wallet_field_number), number,
                { set(draft.copy(number = CardNumber.digits(it).take(CardNumber.maxLength(CardNumber.brand(it))))) },
                placeholder = string(Res.string.wallet_field_number_ending, card.last4), keyboard = DIGITS,
                transformation = CardNumberTransformation, error = numberError(number, card.last4),
            )
            scanner?.Button(onResult = { scan -> set(draft.copy(number = CardNumber.digits(scan.number).take(CardNumber.maxLength(CardNumber.brand(scan.number))))) }, modifier = Modifier)
            WalletField(
                string(Res.string.wallet_field_cvc), cvc, { set(draft.copy(cvc = Cvc.clean(it, brand))) },
                placeholder = string(if (brand == CardBrand.AMEX) Res.string.wallet_field_cvc_hint4 else Res.string.wallet_field_cvc_hint3),
                keyboard = DIGITS.copy(imeAction = ImeAction.Done), transformation = PasswordVisualTransformation(),
            )
        }
        if (problem.isNotEmpty()) ErrorText(problem, small = true, modifier = Modifier.padding(horizontal = 4.dp))
        SaveButton(string(if (autoPay) Res.string.wallet_auto_turn_on else Res.string.wallet_save), enabled = ready, busy = busy, onClick = { vm.save(texts) })
    }
}
