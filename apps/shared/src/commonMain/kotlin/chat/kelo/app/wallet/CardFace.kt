package chat.kelo.app.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A card drawn as a small card face: a colour per brand (two stops of a diagonal gradient), the brand's short name and the last
 * four digits. No brand logos: the colours and letters are Kelo's own.
 */
/** The text on a card face: the faces are dark in both themes. */
val CardInk = Color(0xFFFFFFFF)

object CardFaceRules {
    /** The two colours (ARGB) of a brand's face; both stops keep white text at 4.5:1 or more (ThemeContrastTest). */
    fun palette(brand: CardBrand): Pair<Long, Long> = when (brand) {
        CardBrand.VISA -> 0xFF1C2E8C to 0xFF3D5AD8
        CardBrand.MASTERCARD -> 0xFF3A1F1A to 0xFFB34A16
        CardBrand.AMEX -> 0xFF0E5E7A to 0xFF1C7A8C
        CardBrand.JCB -> 0xFF0F5A3A to 0xFF237A4C
        CardBrand.DISCOVER -> 0xFF6B3A0A to 0xFFA85C0C
        CardBrand.UNIONPAY -> 0xFF6E0F1E to 0xFFC62F3F
        CardBrand.DINERS -> 0xFF2B3440 to 0xFF5E6B7C
        CardBrand.OTHER -> 0xFF3B3B46 to 0xFF6A6A7A
    }

    /**
     * The number as the preview on the add form shows it while typing: the typed digits grouped like the field, the rest of the
     * expected length as dots, and only the first six and the last four ever shown in full once the number is long.
     */
    fun preview(digits: String): String {
        val d = CardNumber.digits(digits)
        val brand = CardNumber.brand(d)
        val length = maxOf(d.length, if (brand == CardBrand.AMEX) 15 else 16)
        val masked = StringBuilder()
        for (i in 0 until length) {
            val ch = d.getOrNull(i)
            masked.append(
                when {
                    ch == null -> '•'
                    d.length > 10 && i in 6 until d.length - 4 -> '•'
                    else -> ch
                },
            )
        }
        val sizes = CardNumber.groups(brand, length)
        val out = StringBuilder()
        var at = 0
        for (size in sizes) {
            if (at >= masked.length) break
            if (out.isNotEmpty()) out.append(' ')
            out.append(masked, at, minOf(at + size, masked.length))
            at += size
        }
        return out.toString()
    }
}

/** A small card face for a list row ([width] wide, the shape of a payment card). */
@Composable
internal fun CardFaceThumb(brand: CardBrand, last4: String, width: Dp = 56.dp, dimmed: Boolean = false) {
    val (a, b) = CardFaceRules.palette(brand)
    Box(
        Modifier.size(width = width, height = width * 0.63f).clip(RoundedCornerShape(6.dp))
            .background(Brush.linearGradient(listOf(Color(a), Color(b))))
            .then(if (dimmed) Modifier.background(CardInk.copy(alpha = 0.45f)) else Modifier)
            .padding(horizontal = 5.dp, vertical = 4.dp),
    ) {
        Text(brandShort(brand), color = CardInk, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.align(Alignment.TopStart))
        Text(last4.takeLast(4), color = CardInk, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, modifier = Modifier.align(Alignment.BottomEnd))
    }
}

/** The large face at the top of the add form and a card's page: brand, number, holder and expiry, as typed so far. */
@Composable
internal fun CardFaceLarge(brand: CardBrand, number: String, holder: String, expiry: String, modifier: Modifier = Modifier) {
    val (a, b) = CardFaceRules.palette(brand)
    Column(
        modifier.fillMaxWidth().aspectRatio(1.586f).clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(Color(a), Color(b))))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 34.dp, height = 24.dp).clip(RoundedCornerShape(5.dp)).background(CardInk.copy(alpha = 0.35f)))
            Spacer(Modifier.weight(1f))
            Text(brandName(brand) ?: "", color = CardInk, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.weight(1f))
        Text(number, color = CardInk, fontFamily = FontFamily.Monospace, fontSize = 20.sp, letterSpacing = 1.sp, maxLines = 1)
        Spacer(Modifier.size(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(holder.ifBlank { " " }.uppercase(), color = CardInk.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(expiry, color = CardInk.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

/** The add form's steps, in order. */
enum class AddStep { NUMBER, DATES, OWNER, CONFIRM }

/** Which step may be left for the next, kept apart from the screen. */
object AddCardSteps {
    fun next(s: AddStep): AddStep = AddStep.entries.getOrElse(s.ordinal + 1) { s }
    fun prev(s: AddStep): AddStep? = AddStep.entries.getOrNull(s.ordinal - 1)

    /** 1-based number of the step and how many there are, for "2 / 4". */
    fun position(s: AddStep): Pair<Int, Int> = (s.ordinal + 1) to AddStep.entries.size

    /**
     * NUMBER: a complete number that passes the check; DATES: an expiry that is not past nor too far, and a CVC of the brand's
     * length; OWNER: nothing required (the name and postal code are optional); CONFIRM: the notice ticked and everything above.
     */
    fun canLeave(s: AddStep, number: String, expiry: String, cvc: String, agreed: Boolean, now: java.time.YearMonth): Boolean {
        val numberOk = CardNumber.valid(number)
        val datesOk = Expiry.check(expiry, now) is ExpiryCheck.Ok && Cvc.valid(cvc, CardNumber.brand(number))
        return when (s) {
            AddStep.NUMBER -> numberOk
            AddStep.DATES -> datesOk
            AddStep.OWNER -> true
            AddStep.CONFIRM -> numberOk && datesOk && ConsentRules.canSave(true, agreed)
        }
    }

    /** The first step that still blocks saving (to go back to), or null when everything is ready. */
    fun firstBlocking(number: String, expiry: String, cvc: String, now: java.time.YearMonth): AddStep? = when {
        !canLeave(AddStep.NUMBER, number, expiry, cvc, false, now) -> AddStep.NUMBER
        !canLeave(AddStep.DATES, number, expiry, cvc, false, now) -> AddStep.DATES
        else -> null
    }
}
