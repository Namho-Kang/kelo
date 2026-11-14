package chat.kelo.app.chat

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import chat.kelo.app.ui.platform.decodeImage
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import chat.kelo.app.ui.platform.formFactor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.kelo.app.chat.vault.CardBox
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the payment-step card can do; the screen wires these to the view model and the server. */
class PaymentStepActions(
    val answer: (String, String?) -> Unit,
    val openLive: () -> Unit,
    /** The keypad picture now (JPEG bytes), or null when it cannot be had */
    val padImage: suspend () -> ByteArray?,
    /** A tap at fractions of the picture; returns the taps counted, or null when it did not go through */
    val padTap: suspend (Float, Float) -> Int?,
    val padClear: suspend () -> Int?,
)

@Composable
private fun rememberNowS(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis() / 1000
        }
    }
    return now
}

/**
 * One payment step that needs the user, at the bottom of the chat: what is paid, then what to do. An app approval shows the code
 * large (tap to copy), the QR code from the page, the time left counting down and [승인했어요] / [취소]; when the time runs out it asks
 * whether to get a new code. A one-time code has a field for it. The PIN keypad kind shows the page's keypad region live; the user's
 * taps on it go to the same spots of the page, with a dot per tap and the page's own clear key. Ended steps fold into one line.
 */
@Composable
fun PaymentStepCard(
    step: PaymentStep, actions: PaymentStepActions, preview: Boolean = false,
    /** A line under an approval that happens in another app (a card or bank app, a passkey): the desktop says it happens on the phone; the phone adds nothing. */
    approvalNote: @Composable () -> Unit = {},
) {
    val c = Kelo.colors
    CardBox(Modifier.animateContentSize()) {
        if (!step.open && step.state != "finishing") {
            EndedLine(step)
            return@CardBox
        }
        val now = if (preview) (step.expiresAt?.minus(151) ?: 0L) else rememberNowS()
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (step.isPad) KeloIcons.Lock else KeloIcons.Phone, contentDescription = null, tint = c.accent,
                modifier = Modifier.padding(top = 2.dp).size(20.dp))
            Text(
                string(
                    when (step.kind) {
                        PaymentStep.KIND_PIN_PAD -> Res.string.pay_step_title_pin
                        PaymentStep.KIND_OTP -> Res.string.pay_step_title_otp
                        PaymentStep.KIND_PASSKEY -> Res.string.pay_step_title_passkey
                        else -> Res.string.pay_step_title_app
                    },
                ),
                style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f).semantics { heading() },
            )
        }
        OrderBox(step)
        if (step.instruction.isNotBlank()) Text(step.instruction, style = MaterialTheme.typography.bodyMedium, color = c.ink)
        when {
            step.state == "finishing" -> FinishingLine()
            step.isPad -> PinPadPart(step, actions, preview)
            else -> ApprovePart(step, actions, now, approvalNote)
        }
    }
}

@Composable
private fun OrderBox(step: PaymentStep) {
    val c = Kelo.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.bubble).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (step.site.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(KeloIcons.Globe, contentDescription = null, tint = c.muted, modifier = Modifier.size(14.dp))
                Text(step.site, style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
        }
        val what = step.items.joinToString(", ").ifEmpty { step.summary }
        if (what.isNotEmpty()) Text(what, style = MaterialTheme.typography.bodyMedium, color = c.ink)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step.payWith.isNotEmpty()) Text(step.payWith, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.weight(1f))
            else Box(Modifier.weight(1f))
            if (step.amountText.isNotEmpty()) Text(step.amountText, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.ink, textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun ApprovePart(step: PaymentStep, actions: PaymentStepActions, now: Long, approvalNote: @Composable () -> Unit) {
    val c = Kelo.colors
    val clipboard = LocalClipboardManager.current
    var copied by remember(step.code) { mutableStateOf(false) }
    val expired = PaymentStepRules.expired(step, now)
    val code = PaymentStepRules.codeText(step)
    if (step.kind == PaymentStep.KIND_APP || step.kind == PaymentStep.KIND_PASSKEY || step.kind.isEmpty()) approvalNote()
    if (code.isNotEmpty()) {
        val copyLabel = string(Res.string.pay_step_copy)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                code, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp,
                color = if (expired) c.muted else c.ink, textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).pointerHoverIcon(PointerIcon.Hand).clickable(onClickLabel = copyLabel) {
                    clipboard.setText(AnnotatedString(step.code.orEmpty()))
                    copied = true
                }.padding(horizontal = 12.dp, vertical = 4.dp).semantics { contentDescription = code.toCharArray().joinToString(" ") },
            )
            MutedText(string(if (copied) Res.string.pay_step_copied else Res.string.pay_step_tap_copy), small = true)
        }
    }
    step.qr?.let { url ->
        val image = rememberStepImage(url)
        if (image != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(image, contentDescription = string(Res.string.pay_step_qr), contentScale = ContentScale.Fit,
                    modifier = Modifier.size(formFactor(phone = 148.dp, desktop = 168.dp))   // a larger code on a computer, where it is scanned from further away
                    .clip(RoundedCornerShape(8.dp)).background(androidx.compose.ui.graphics.Color.White).padding(6.dp))
            }
        }
    }
    val left = PaymentStepRules.secondsLeft(step, now)
    when {
        expired -> {
            Text(string(Res.string.pay_step_expired), color = c.danger, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(string(Res.string.pay_step_cancel), onClick = { actions.answer("cancel", null) }, modifier = Modifier.weight(1f))
                PrimaryButton(string(Res.string.pay_step_new_code), onClick = { actions.answer("new_code", null) }, modifier = Modifier.weight(1f))
            }
        }
        step.state == "renewing" -> MutedText(string(Res.string.pay_step_renewing))
        step.state == "checking" -> MutedText(string(Res.string.pay_step_checking))
        else -> {
            if (left != null) {
                Text(string(Res.string.pay_step_left, CardCountdown.text(left)), style = MaterialTheme.typography.bodySmall,
                    color = if (left <= 30) c.danger else c.muted)
                PaymentStepRules.progress(step, now)?.let { p ->
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = if (left <= 30) c.danger else c.accent, trackColor = c.line)
                }
            } else {
                MutedText(string(Res.string.pay_step_left_unknown), small = true)
            }
            if (step.needsCode) OtpField(actions)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(string(Res.string.pay_step_cancel), onClick = { actions.answer("cancel", null) }, modifier = Modifier.weight(1f))
                if (!step.needsCode) PrimaryButton(string(Res.string.pay_step_approved), onClick = { actions.answer("approved", null) },
                    modifier = Modifier.weight(1f))
            }
        }
    }
    LinkButton(string(Res.string.pay_step_view), onClick = actions.openLive)
}

@Composable
private fun OtpField(actions: PaymentStepActions) {
    var code by remember { mutableStateOf("") }
    KeloTextField(code, { v -> code = v.filter { it.isLetterOrDigit() }.take(12) }, string(Res.string.pay_step_code_hint),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrectEnabled = false))
    PrimaryButton(string(Res.string.pay_step_send_code), onClick = { actions.answer("code", code); code = "" }, enabled = code.length >= 4)
}

/** The page's keypad region, live: polled while shown; a tap goes to the same spot of the page. */
@Composable
private fun PinPadPart(step: PaymentStep, actions: PaymentStepActions, preview: Boolean) {
    val c = Kelo.colors
    val scope = rememberCoroutineScope()
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var taps by remember(step.stepId) { mutableIntStateOf(step.taps) }
    var tick by remember { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf(false) }
    LaunchedEffect(step.stepId, tick) {
        if (preview) return@LaunchedEffect
        while (true) {
            val bytes = actions.padImage()
            problem = bytes == null
            if (bytes != null) withContext(Dispatchers.Default) { decodeImage(bytes) }?.let { image = it }
            delay(PAD_REFRESH_MS)
        }
    }
    LaunchedEffect(step.taps) { taps = step.taps }
    val padLabel = string(Res.string.pay_step_pad)
    Box(
        Modifier.fillMaxWidth().widthIn(max = 420.dp).aspectRatio(image?.let { it.width.toFloat() / it.height } ?: 1.3f)
            .clip(RoundedCornerShape(10.dp)).border(1.dp, c.line, RoundedCornerShape(10.dp)).background(c.bubble)
            .onSizeChanged { size = it }
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics { contentDescription = padLabel }
            .pointerInput(step.stepId) {
                detectTapGestures { o ->
                    val f = PaymentStepRules.tapFraction(o.x, o.y, size.width, size.height) ?: return@detectTapGestures
                    scope.launch {
                        actions.padTap(f.first, f.second)?.let { taps = it }
                        tick++
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (img != null) Image(img, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        else MutedText(string(Res.string.pay_step_pad_loading), small = true)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            PaymentStepRules.dots(taps).ifEmpty { string(Res.string.pay_step_pad_none) },
            style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f)
                .semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "$taps" },
        )
        if (step.canClear) SecondaryButton(string(Res.string.pay_step_clear), onClick = {
            scope.launch { actions.padClear()?.let { taps = it }; tick++ }
        })
    }
    if (problem && image == null) ErrorText(string(Res.string.pay_step_pad_problem), small = true)
    MutedText(string(Res.string.pay_step_pad_note), small = true)
    SecondaryButton(string(Res.string.pay_step_cancel), onClick = { actions.answer("cancel", null) }, modifier = Modifier.fillMaxWidth())
    LinkButton(string(Res.string.pay_step_full_view), onClick = actions.openLive)
}

@Composable
private fun FinishingLine() {
    val c = Kelo.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(KeloIcons.Hourglass, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp))
        Text(string(Res.string.pay_step_finishing), style = MaterialTheme.typography.bodyMedium, color = c.ink,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun EndedLine(step: PaymentStep) {
    val c = Kelo.colors
    val (icon, words, muted) = when (step.state) {
        "approved" -> Triple(KeloIcons.Check, Res.string.pay_step_done_approved, false)
        "done" -> Triple(KeloIcons.Check, Res.string.pay_step_done, false)
        "cancelled" -> Triple(KeloIcons.Close, Res.string.pay_step_done_cancelled, true)
        "timeout" -> Triple(KeloIcons.Clock, Res.string.pay_step_done_timeout, true)
        else -> Triple(KeloIcons.Clock, Res.string.pay_step_done_closed, true)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = if (muted) c.muted else c.success, modifier = Modifier.size(16.dp))
        Text(string(words, step.site.ifEmpty { "-" }), style = MaterialTheme.typography.bodySmall, color = if (muted) c.muted else c.ink)
    }
}

/** A payment's PIN waits for this phone: "확인하고 보내기" (fingerprint / face), the time left, or how it went. */
@Composable
fun PinNeedCard(need: PinNeed, onSend: () -> Unit) {
    val c = Kelo.colors
    val now = rememberNowS()
    val left = (need.expiresAt - now).coerceAtLeast(0)
    CardBox(Modifier.animateContentSize()) {
        when {
            need.send == CardSend.Sent -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(KeloIcons.Check, contentDescription = null, tint = c.success, modifier = Modifier.size(16.dp))
                Text(string(Res.string.pin_need_sent, need.site), style = MaterialTheme.typography.bodySmall, color = c.ink)
            }
            left <= 0L -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(KeloIcons.Clock, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp))
                Text(string(Res.string.pin_need_over, need.site), style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
            else -> {
                Text(string(Res.string.pin_need_title), style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(string(Res.string.pin_need_body, need.pinLabel.ifEmpty { need.site }, need.site, need.amountText),
                    style = MaterialTheme.typography.bodyMedium, color = c.ink)
                MutedText(string(Res.string.chat_card_needed_left, CardCountdown.text(left)), small = true)
                (need.send as? CardSend.Failed)?.let { ErrorText(cardSendErrorText(it.error), small = true) }
                PrimaryButton(string(if (need.send == CardSend.Working) Res.string.chat_card_sending else Res.string.pin_need_send),
                    onClick = onSend, enabled = need.send != CardSend.Working)
            }
        }
    }
}

@Composable
private fun rememberStepImage(dataUrl: String): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, dataUrl) {
        value = withContext(Dispatchers.Default) { BrowserImage.decode(dataUrl) }
    }
    return image
}

private const val PAD_REFRESH_MS = 700L
