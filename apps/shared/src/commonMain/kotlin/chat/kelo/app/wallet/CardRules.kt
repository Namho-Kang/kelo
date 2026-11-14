package chat.kelo.app.wallet

import java.time.YearMonth

/** The card brands the server knows (wallet.py brand_of), in the same order of checks. */
enum class CardBrand(val wire: String) {
    VISA("visa"), AMEX("amex"), MASTERCARD("mastercard"), JCB("jcb"), DISCOVER("discover"), UNIONPAY("unionpay"),
    DINERS("diners"), OTHER("other");

    companion object {
        fun of(wire: String?): CardBrand = entries.firstOrNull { it.wire == wire?.lowercase() } ?: OTHER
    }
}

/**
 * Card number rules on the phone: the same Luhn check and brand detection as the server (wallet.py), plus the grouping the add
 * form shows while typing. Nothing here keeps or logs a number.
 */
object CardNumber {
    const val MIN_DIGITS = 12
    const val MAX_DIGITS = 19

    /** Only the digits of what was typed or pasted (spaces and dashes dropped), at most [MAX_DIGITS]. */
    fun digits(text: String): String {
        val out = StringBuilder(minOf(text.length, MAX_DIGITS))
        for (ch in text) {
            if (ch in '0'..'9') {
                out.append(ch)
                if (out.length == MAX_DIGITS) break
            }
        }
        return out.toString()
    }

    fun luhn(number: String): Boolean {
        if (number.isEmpty() || number.any { it !in '0'..'9' }) return false
        var total = 0
        for ((i, ch) in number.reversed().withIndex()) {
            var d = ch - '0'
            if (i % 2 == 1) d = if (d > 4) d * 2 - 9 else d * 2
            total += d
        }
        return total % 10 == 0
    }

    private fun prefix(n: String, len: Int): Int = n.take(len).toIntOrNull()?.takeIf { n.length >= len } ?: 0

    /** wallet.py brand_of: works on a partial number too (the form shows the brand as soon as it can be told). */
    fun brand(number: String): CardBrand {
        val n = digits(number)
        if (n.isEmpty()) return CardBrand.OTHER
        val p2 = prefix(n, 2)
        val p3 = prefix(n, 3)
        val p4 = prefix(n, 4)
        return when {
            n.startsWith("4") -> CardBrand.VISA
            n.startsWith("34") || n.startsWith("37") -> CardBrand.AMEX
            p2 in 51..55 || p4 in 2221..2720 -> CardBrand.MASTERCARD
            p4 in 3528..3589 -> CardBrand.JCB
            n.startsWith("6011") || n.startsWith("65") || p3 in 644..649 -> CardBrand.DISCOVER
            n.startsWith("62") -> CardBrand.UNIONPAY
            n.startsWith("36") || n.startsWith("38") || n.startsWith("39") || p3 in 300..305 -> CardBrand.DINERS
            else -> CardBrand.OTHER
        }
    }

    /** Where the spaces go: 4-6-5 for American Express (15 digits), 4-6-4 for Diners (14), otherwise groups of 4. */
    fun groups(brand: CardBrand, length: Int): List<Int> = when {
        brand == CardBrand.AMEX -> listOf(4, 6, 5)
        brand == CardBrand.DINERS && length <= 14 -> listOf(4, 6, 4)
        else -> List((maxOf(length, 16) + 3) / 4) { 4 }
    }

    /** "4242424242424242" -> "4242 4242 4242 4242" (a partial number is grouped as far as it goes). */
    fun group(number: String): String {
        val n = digits(number)
        val sizes = groups(brand(n), n.length)
        val out = StringBuilder()
        var at = 0
        for (size in sizes) {
            if (at >= n.length) break
            if (out.isNotEmpty()) out.append(' ')
            out.append(n, at, minOf(at + size, n.length))
            at += size
        }
        if (at < n.length) out.append(' ').append(n, at, n.length)
        return out.toString()
    }

    /** For the field's visual transformation: where raw digit [offset] lands in [group]'s output. */
    fun groupedOffset(number: String, offset: Int): Int {
        val n = digits(number)
        val sizes = groups(brand(n), n.length)
        var spaces = 0
        var edge = 0
        for (size in sizes) {
            edge += size
            if (offset > edge && edge < n.length) spaces++ else break
        }
        return offset + spaces
    }

    /** The reverse of [groupedOffset]. */
    fun rawOffset(number: String, grouped: Int): Int {
        val text = group(number)
        return text.take(grouped.coerceIn(0, text.length)).count { it != ' ' }
    }

    /** How many digits a complete number of [brand] has (the form stops there): 15 Amex, 14-19 others (16 typical). */
    fun maxLength(brand: CardBrand): Int = when (brand) {
        CardBrand.AMEX -> 15
        else -> MAX_DIGITS
    }

    /** Whether the typed number can be saved: 12 to 19 digits that pass the check digit. */
    fun valid(number: String): Boolean {
        val n = digits(number)
        return n.length in MIN_DIGITS..MAX_DIGITS && luhn(n)
    }

    fun last4(number: String): String = digits(number).takeLast(4)
}

/** The CVC: 4 digits on American Express, 3 on the others (the server takes 3 or 4). */
object Cvc {
    fun length(brand: CardBrand): Int = if (brand == CardBrand.AMEX) 4 else 3
    fun clean(text: String, brand: CardBrand): String = text.filter { it in '0'..'9' }.take(length(brand))
    fun valid(cvc: String, brand: CardBrand): Boolean = cvc.length == length(brand) && cvc.all { it in '0'..'9' }
}

/** What the expiry field holds after a check. */
sealed interface ExpiryCheck {
    data class Ok(val month: Int, val year: Int) : ExpiryCheck
    data object Incomplete : ExpiryCheck
    data object BadMonth : ExpiryCheck
    data object Expired : ExpiryCheck
    data object TooFar : ExpiryCheck
}

/** The "MM/YY" field: typed as up to 4 digits, shown with the slash. */
object Expiry {
    /** Up to 4 digits; a first digit 2-9 means a single-digit month, so a 0 is put in front ("4" -> "04"). */
    fun clean(text: String): String {
        val d = text.filter { it in '0'..'9' }
        val fixed = if (d.length == 1 && d[0] in '2'..'9') "0$d" else d
        return fixed.take(4)
    }

    /** "0428" -> "04/28" (the slash shows once the month is complete). */
    fun format(digits: String): String = if (digits.length <= 2) digits else digits.take(2) + "/" + digits.drop(2)

    /** A card is good through the last day of its month (wallet.py check_expiry). */
    fun check(digits: String, now: YearMonth): ExpiryCheck {
        if (digits.length != 4 || digits.any { it !in '0'..'9' }) return ExpiryCheck.Incomplete
        val m = digits.take(2).toInt()
        val y = 2000 + digits.drop(2).toInt()
        if (m !in 1..12) return ExpiryCheck.BadMonth
        val ym = YearMonth.of(y, m)
        if (ym.isBefore(now)) return ExpiryCheck.Expired
        if (y > now.year + 25) return ExpiryCheck.TooFar
        return ExpiryCheck.Ok(m, y)
    }

    fun expired(month: Int, year: Int, now: YearMonth): Boolean =
        runCatching { YearMonth.of(year, month).isBefore(now) }.getOrDefault(false)

    /** "04/28" for display on a saved card. */
    fun display(month: Int, year: Int): String = String.format(java.util.Locale.ROOT, "%02d/%02d", month, year % 100)
}

/** How a saved card is named when only the last digits may show. */
object CardMask {
    const val DOTS = "••"

    fun last4(text: String?): String = text.orEmpty().filter { it in '0'..'9' }.takeLast(4)

    /**
     * The line under a card's nickname: its brand and last 4 ("Visa ••4242"); null when the nickname already says exactly that (a card
     * saved without a nickname is named so by the server). [brandWord] is the brand's display name.
     */
    fun subtitle(label: String, brandWord: String, last4: String): String? {
        val line = "$brandWord $DOTS${last4(last4)}".trim()
        return line.takeUnless { it.equals(label.trim(), ignoreCase = true) }
    }
}

/** The default card: the one the agent pays with unless the owner or the task names another (server wallet.py). */
object DefaultCardRules {
    fun default(cards: List<WalletCard>): WalletCard? = cards.firstOrNull { it.isDefault }

    /**
     * Deleting [card]: null when nothing needs choosing (not the default, or at most one card left, which the server then makes the
     * default); otherwise the cards the owner picks the new default from.
     */
    fun choicesOnDelete(card: WalletCard, cards: List<WalletCard>): List<WalletCard>? {
        if (!card.isDefault) return null
        val rest = cards.filter { it.id != card.id }
        return rest.takeIf { it.size >= 2 }
    }

    /** The first pick offered when choosing a new default: a card that can pay, else the first one. */
    fun suggested(choices: List<WalletCard>): WalletCard? = choices.firstOrNull { it.usable && !it.expired } ?: choices.firstOrNull()
}

/** A card's own payment history, a page at a time (GET /api/wallet/cards/{id}/payments): what is shown after one more page. */
object CardHistoryRules {
    fun merge(shown: List<WalletPayment>, page: WalletPayments): List<WalletPayment> {
        val ids = shown.map { it.id }.toSet()
        return (shown + page.items.filter { it.id !in ids }).sortedByDescending { it.id }
    }

    /** Where a payment leads: its chat ("chat"/"api" turns and the like), its task, or nowhere. */
    fun link(p: WalletPayment): PaymentLink = when {
        p.source.startsWith("schedule:") || p.source.startsWith("task:") || p.source == "background" ->
            p.taskId?.let { PaymentLink.Task(it) } ?: PaymentLink.None
        p.source.isNotEmpty() -> PaymentLink.Chat
        else -> p.taskId?.let { PaymentLink.Task(it) } ?: PaymentLink.None
    }
}

sealed interface PaymentLink {
    data object Chat : PaymentLink
    data class Task(val id: Long) : PaymentLink
    data object None : PaymentLink
}
