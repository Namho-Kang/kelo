package chat.kelo.app.chat

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import chat.kelo.app.ui.platform.decodeImage
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.kelo.app.chat.vault.CardBox
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.theme.Kelo
import chat.kelo.app.wallet.CardSendError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The picture in a browser event: "data:image/jpeg;base64,..." decoded off the main thread (null while decoding or unreadable). */
@Composable
private fun rememberDataImage(dataUrl: String?): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, dataUrl) {
        value = if (dataUrl == null) null else withContext(Dispatchers.Default) { BrowserImage.decode(dataUrl) }
    }
    return image
}

object BrowserImage {
    /** The bytes of a base64 data: URL, or null when it is not one. */
    fun bytes(dataUrl: String): ByteArray? {
        val comma = dataUrl.indexOf(',')
        if (!dataUrl.startsWith("data:image/") || comma < 0 || !dataUrl.substring(0, comma).endsWith(";base64")) return null
        return runCatching { java.util.Base64.getDecoder().decode(dataUrl.substring(comma + 1)) }.getOrNull()
    }

    fun decode(dataUrl: String): ImageBitmap? {
        val b = bytes(dataUrl) ?: return null
        return runCatching { decodeImage(b) }.getOrNull()
    }
}

/**
 * The assistant's browser tab as one compact live row per reply (it updates in place as the agent moves on, also to other sites in
 * the same tab): a tile with the site's first letter, the site and what the agent is doing there, and 보기 (the tab live, where the
 * user can also take it over). Under it, once there are pictures, the last few as a small
 * filmstrip (tap one for full screen).
 */
@Composable
fun BrowserCard(b: ChatItem.Browser, onOpenLive: (() -> Unit)? = null) {
    val c = Kelo.colors
    var full by remember { mutableStateOf<String?>(null) }
    val site = b.host.ifEmpty { b.title.ifEmpty { string(Res.string.chat_browser_title) } }
    Column(
        Modifier.fillMaxWidth().padding(end = 32.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, c.line, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val hue = chat.kelo.app.connectors.TileColors.hue(b.host.ifEmpty { b.id })
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(chat.kelo.app.connectors.TileColors.of(hue, c.dark)),
                contentAlignment = Alignment.Center,
            ) { Text(SiteTile.letter(b.host, b.title), color = chat.kelo.app.ui.theme.TileInk, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
            Column(Modifier.weight(1f)) {
                Text(site, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // the newest step; once the tab went through several sites in this reply, how many
                val step = b.status.ifEmpty { b.title }
                val line = if (b.hosts.size > 1) string(Res.string.chat_browser_step_sites, step, b.hosts.size) else step
                Text(line, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onOpenLive != null) {
                androidx.compose.material3.TextButton(onClick = onOpenLive) {
                    Text(string(Res.string.chat_browser_view_short), color = c.accent, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        val strip = b.recent.ifEmpty { listOfNotNull(b.image) }
        if (strip.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                strip.forEach { url -> FilmFrame(url, onOpen = { full = url }) }
            }
        }
    }
    full?.let { url ->
        val image = rememberDataImage(url)
        if (image != null) ImageViewer(image, b.title.ifEmpty { b.host }, onClose = { full = null })
    }
}

/** One small picture of the filmstrip (the top of the page, cropped). */
@Composable
private fun FilmFrame(dataUrl: String, onOpen: () -> Unit) {
    val c = Kelo.colors
    val image = rememberDataImage(dataUrl)
    val viewLabel = string(Res.string.chat_browser_view)
    Box(
        Modifier.size(width = 72.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)).background(c.bubble)
            .border(1.dp, c.line, RoundedCornerShape(6.dp)).clickable(enabled = image != null, role = Role.Image, onClickLabel = viewLabel, onClick = onOpen),
    ) {
        if (image != null) Image(image, contentDescription = viewLabel, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
    }
}

/** The letter on a site's tile: the host's first letter or digit ("www." skipped), else the title's. */
object SiteTile {
    fun letter(host: String, title: String): String {
        val h = host.removePrefix("www.")
        return (h.firstOrNull { it.isLetterOrDigit() } ?: title.firstOrNull { it.isLetterOrDigit() } ?: '?').uppercaseChar().toString()
    }
}

/** A picture on the whole screen; pinch to zoom, drag to move, × or back to close. */
@Composable
private fun ImageViewer(image: ImageBitmap, title: String, onClose: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Kelo.colors.media)) {
            Image(
                image, contentDescription = title, contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            )
            Row(Modifier.fillMaxWidth().safeDrawingPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Kelo.colors.onMedia, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
                IconButton(onClick = onClose) { Icon(KeloIcons.Close, contentDescription = string(Res.string.close), tint = Kelo.colors.onMedia) }
            }
        }
    }
}

/**
 * The approval card of confirm_purchase, everything at once: the site; the items (name, options, × quantity, price) or the summary;
 * where it goes (nickname · name · phone, then the address lines); what pays (the card's nickname with brand and last 4, or the way
 * the site takes it); the coupon; the total, large; Allow / Deny. Long names and addresses wrap at any width, never cut.
 */
@Composable
fun PurchaseApproval(a: ChatItem.Approval, p: PaymentInfo, onAnswer: (Boolean) -> Unit) {
    val c = Kelo.colors
    val working = a.send == CardSend.Working
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(string(Res.string.chat_purchase_question), style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
        Outcomes.of(a)?.let { OutcomeChip(it, cardSent = a.needsCard && a.send == CardSend.Sent) }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bubble).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (p.site.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(KeloIcons.Globe, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp))
                Text(p.site, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (p.items.isNotEmpty()) {
            OrderPart(KeloIcons.Package, string(Res.string.order_items)) {
                p.items.forEach { OrderItemRow(it) }
            }
        } else if (p.summary.isNotEmpty() || p.orderWhat.isNotEmpty()) {
            Text(p.summary.ifEmpty { p.orderWhat }, style = MaterialTheme.typography.bodyMedium, color = c.ink)
        }
        p.shipping?.let { ship ->
            OrderPart(KeloIcons.MapPin, string(Res.string.order_ship_to)) {
                val who = chat.kelo.app.wallet.AddressRules.who(ship.label, ship.name, chat.kelo.app.ui.platform.phoneShown(ship.phone, ship.country))
                if (who.isNotEmpty()) Text(who, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = c.ink)
                chat.kelo.app.wallet.AddressRules.lines(ship.formatted).forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, color = c.ink)
                }
                when (ship.source) {
                    chat.kelo.app.wallet.OrderShipping.SOURCE_SITE -> MutedText(string(Res.string.order_ship_site), small = true)
                    chat.kelo.app.wallet.OrderShipping.SOURCE_ONCE -> MutedText(string(Res.string.order_ship_once), small = true)
                    else -> {}
                }
            }
        }
        OrderPart(KeloIcons.Card, string(Res.string.order_pay_with)) {
            val card = a.card
            if (card != null) {
                // the owner's nickname for the card, with brand and last 4 under it
                Text(card.card.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = c.ink)
                val b = chat.kelo.app.wallet.CardBrand.of(card.card.brand)
                chat.kelo.app.wallet.CardMask.subtitle(card.card.label, chat.kelo.app.wallet.brandName(b) ?: chat.kelo.app.wallet.brandShort(b), card.card.last4)
                    ?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
            } else {
                Text(string(Res.string.order_pay_site), style = MaterialTheme.typography.bodyMedium, color = c.ink)
            }
        }
        if (p.coupon.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(KeloIcons.Tag, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp))
                Text(string(Res.string.order_coupon), style = MaterialTheme.typography.bodySmall, color = c.muted)
                Text(p.coupon, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = c.ink, modifier = Modifier.weight(1f))
            }
        }
        androidx.compose.material3.HorizontalDivider(color = c.line)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(string(Res.string.order_total), style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(bottom = 3.dp))
            Text(
                p.amountText.ifEmpty { string(Res.string.wallet_money, p.amount.toString(), p.currency) },
                fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = c.ink,
                textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f),
            )
        }
    }
    a.card?.let { card ->
        // why it asks (any card, also one saved to ask each time), as the server words it
        if (card.reasonText.isNotEmpty()) MutedText(string(Res.string.chat_purchase_not_auto, card.reasonText), small = true)
        if (card.needsCard) MutedText(string(Res.string.chat_purchase_needs_card), small = true)
    }
    if (!a.open) {
        // answered or expired: the card stays as it was asked; the chip on top says how it ended
        (a.send as? CardSend.Failed)?.let { ErrorText(cardSendErrorText(it.error), small = true) }
        return
    }
    CardSendLine(a.send, canDeny = true)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton(string(Res.string.chat_approval_deny), onClick = { onAnswer(false) }, enabled = !working, modifier = Modifier.weight(1f))
        PrimaryButton(
            string(if (working) Res.string.chat_card_sending else Res.string.chat_approval_allow),
            onClick = { onAnswer(true) }, enabled = !working, modifier = Modifier.weight(1f),
        )
    }
}

/** One part of the order card: a small icon and title, then its lines. */
@Composable
private fun OrderPart(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, content: @Composable () -> Unit) {
    val c = Kelo.colors
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, color = c.muted)
        }
        Column(Modifier.padding(start = 22.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    }
}

/** "블루 머그 / 색상: 네이비" on the left, the price and "× 2" on the right; a long name wraps beside them. */
@Composable
private fun OrderItemRow(item: chat.kelo.app.wallet.OrderItem) {
    val c = Kelo.colors
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium, color = c.ink)
            if (item.options.isNotEmpty()) Text(item.options, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        Column(Modifier.widthIn(max = 140.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            if (item.priceText.isNotEmpty()) Text(item.priceText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = c.ink,
                textAlign = androidx.compose.ui.text.style.TextAlign.End)
            Text(string(Res.string.order_quantity, item.quantity), style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
    }
}

/** How sending the card went, when there is something to say. */
@Composable
fun CardSendLine(send: CardSend, canDeny: Boolean) {
    when (send) {
        CardSend.Working -> MutedText(string(Res.string.chat_card_sending_hint), small = true)
        // on a card_needed row there is no 거절: the words say it can be sent again in time
        is CardSend.Failed -> ErrorText(
            if (send.error == CardSendError.CANCELLED && !canDeny) string(Res.string.chat_card_error_cancelled_retry)
            else cardSendErrorText(send.error),
            small = true,
        )
        else -> {}
    }
}

@Composable
fun cardSendErrorText(e: CardSendError): String = string(
    when (e) {
        CardSendError.NOT_WAITING -> Res.string.chat_card_error_not_waiting
        CardSendError.MISMATCH -> Res.string.chat_card_error_mismatch
        CardSendError.NOT_ON_PHONE -> Res.string.chat_card_error_not_on_phone
        CardSendError.NO_LOCK -> Res.string.chat_card_error_no_lock
        CardSendError.CANCELLED -> Res.string.chat_card_error_cancelled
        CardSendError.NETWORK -> Res.string.chat_card_error_network
        CardSendError.OTHER -> Res.string.chat_card_error_other
        CardSendError.KEYGUARD_REQUIRED -> Res.string.wallet_keyguard_needed_title
    },
)

/** How an approval ended, for the chip on its card. */
enum class Outcome { ALLOWED, DENIED, EXPIRED }

object Outcomes {
    fun of(a: ChatItem.Approval): Outcome? = when {
        a.open -> null
        a.answer == true -> Outcome.ALLOWED
        a.answer == false -> Outcome.DENIED
        else -> Outcome.EXPIRED
    }
}

/** The outcome as a small chip in the card's corner ("허용함", "거절함", "시간 지남"). */
@Composable
fun OutcomeChip(o: Outcome, cardSent: Boolean = false) {
    val c = Kelo.colors
    val (word, tone) = when (o) {
        Outcome.ALLOWED -> string(if (cardSent) Res.string.chat_outcome_card_sent else Res.string.chat_outcome_allowed) to c.success
        Outcome.DENIED -> string(Res.string.chat_outcome_denied) to c.danger
        Outcome.EXPIRED -> string(Res.string.chat_outcome_expired) to c.muted
    }
    Text(
        word, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = tone,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(tone.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Seconds left until [expiresAt] (unix seconds), ticking. */
@Composable
private fun rememberSecondsLeft(expiresAt: Long): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(expiresAt) {
        while (now < expiresAt) {
            delay(1000)
            now = System.currentTimeMillis() / 1000
        }
    }
    return (expiresAt - now).coerceAtLeast(0)
}

/** An allowed payment waits for this phone's card: a countdown and "확인하고 카드 보내기". */
@Composable
fun CardNeededCard(item: ChatItem.CardNeeded, onSend: () -> Unit) {
    val c = Kelo.colors
    val left = rememberSecondsLeft(item.expiresAt)
    val over = item.ended || left <= 0L
    CardBox(Modifier.animateContentSize(FoldSpec)) {
        when {
            item.send == CardSend.Sent -> ResultLine(KeloIcons.Check, string(Res.string.chat_card_sent, item.amountText))
            over -> ResultLine(KeloIcons.Clock, string(Res.string.chat_card_needed_over, item.amountText), muted = true)
            else -> {
                Text(string(Res.string.chat_card_needed_title), style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(
                    string(Res.string.chat_card_needed_body, item.card.label, item.site, item.amountText),
                    style = MaterialTheme.typography.bodyMedium, color = c.ink,
                )
                MutedText(string(Res.string.chat_card_needed_left, CardCountdown.text(left)), small = true)
                CardSendLine(item.send, canDeny = false)
                PrimaryButton(
                    string(if (item.send == CardSend.Working) Res.string.chat_card_sending else Res.string.chat_card_needed_send),
                    onClick = onSend, enabled = item.send != CardSend.Working,
                )
            }
        }
    }
}

object CardCountdown {
    /** 272 -> "4:32" */
    fun text(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }
}

/** "자동결제했어요 · site · 24,000원 · Visa ••4242": a payment the auto-pay rules let through. */
@Composable
fun PaymentAutoRow(p: ChatItem.PaymentAuto) {
    val c = Kelo.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bubble).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(KeloIcons.Card, contentDescription = null, tint = c.success, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (p.cardLabel.isEmpty()) string(Res.string.chat_payment_auto_no_card, p.site, p.amountText)
                else string(Res.string.chat_payment_auto, p.site, p.amountText, p.cardLabel),
                style = MaterialTheme.typography.bodySmall, color = c.ink,
            )
            // where the order goes, when the server said
            p.order?.shipping?.let { ship ->
                val place = listOf(ship.label, chat.kelo.app.wallet.AddressRules.oneLine(ship.formatted)).filter { it.isNotBlank() }.joinToString(" · ")
                if (place.isNotEmpty()) Text(string(Res.string.shipping_ask_done, place), style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
        }
    }
}
