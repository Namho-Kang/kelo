package chat.kelo.app.wallet

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Amounts in a currency's own minor units: KRW, JPY and other zero-decimal currencies without decimals, USD/EUR and the like
 * with two. The number is grouped for [Locale]; the currency itself is put around it by a string resource (wallet_money_*), so
 * every language can place it its own way.
 */
object Money {
    /** Currencies paid in whole units even where ISO 4217 lists minor units nobody uses (the server's limits are whole too). */
    private val WHOLE = setOf("KRW", "JPY", "VND", "IDR", "CLP", "ISK", "HUF", "TWD", "UGX", "PYG", "XAF", "XOF")

    fun decimals(currency: String): Int {
        val code = currency.uppercase(Locale.ROOT)
        if (code in WHOLE) return 0
        return runCatching { Currency.getInstance(code).defaultFractionDigits }.getOrNull()?.takeIf { it >= 0 } ?: 2
    }

    /** 30000.0 KRW -> "30,000"; 25.0 USD -> "25.00"; 3000.0 JPY -> "3,000" (in [locale]'s digits and separators). */
    fun number(amount: Double, currency: String, locale: Locale): String {
        val d = decimals(currency)
        val f = (NumberFormat.getNumberInstance(locale) as? DecimalFormat) ?: DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(locale))
        f.isGroupingUsed = true
        f.minimumFractionDigits = d
        f.maximumFractionDigits = d
        f.roundingMode = RoundingMode.HALF_UP
        return f.format(BigDecimal.valueOf(amount).setScale(d, RoundingMode.HALF_UP))
    }

    /** The number to put back in an edit field: no grouping, "." as the decimal point, no trailing ".00" on whole amounts. */
    fun plain(amount: Double, currency: String): String {
        val d = decimals(currency)
        val v = BigDecimal.valueOf(amount).setScale(d, RoundingMode.HALF_UP)
        return if (v.stripTrailingZeros().scale() <= 0) v.setScale(0, RoundingMode.HALF_UP).toPlainString() else v.toPlainString()
    }

    /**
     * What the user typed in a limit field: digits with grouping commas or spaces, and a decimal point only where the currency has
     * minor units ("1,000" KRW -> 1000.0; "12.5" USD -> 12.5; "12.5" KRW -> null). Negative or empty -> null.
     */
    fun parse(text: String, currency: String): Double? {
        val t = text.trim().replace(",", "").replace(" ", "").replace(" ", "")
        if (t.isEmpty()) return null
        val d = decimals(currency)
        val pattern = if (d == 0) Regex("\\d{1,13}") else Regex("\\d{1,13}(\\.\\d{1,$d})?")
        if (!pattern.matches(t)) return null
        return t.toDouble()
    }

    /** The currency code is a 3-letter ISO code (the server's rule). */
    fun validCode(text: String): Boolean = Regex("[A-Z]{3}").matches(text)
}

/** One row of GET /api/wallet/limits as the limits page shows it. */
object LimitRules {
    /**
     * Which currencies the page lists: those the user changed or spent in this month, plus [home] (the phone's own currency),
     * plus any the user just added on this visit. The rest are one tap away under "다른 통화 추가". The server's order is kept.
     */
    fun visible(rows: List<WalletLimit>, home: String?, added: Set<String> = emptySet()): List<WalletLimit> =
        visible(rows, setOfNotNull(home), added)

    fun visible(rows: List<WalletLimit>, homes: Set<String>, added: Set<String> = emptySet()): List<WalletLimit> =
        rows.filter { it.custom || it.spentThisMonth > 0 || it.currency in homes || it.currency in added }

    /** Currencies that could be added: known defaults not listed yet. */
    fun addable(rows: List<WalletLimit>, shown: List<WalletLimit>): List<String> {
        val listed = shown.mapTo(HashSet()) { it.currency }
        return rows.map { it.currency }.filter { it !in listed }
    }

    /** The two numbers can be saved: both 0 or more, and the per-payment one not above the monthly one (server rule). */
    fun check(perPayment: Double?, monthly: Double?): LimitCheck = when {
        perPayment == null || monthly == null -> LimitCheck.MISSING
        perPayment < 0 || monthly < 0 -> LimitCheck.MISSING
        perPayment > monthly -> LimitCheck.PER_ABOVE_MONTHLY
        else -> LimitCheck.OK
    }

    /** How much of the monthly limit is used (0..1; a 0 limit counts as full). */
    fun used(spent: Double, monthly: Double): Float = if (monthly <= 0) 1f else (spent / monthly).toFloat().coerceIn(0f, 1f)

    /**
     * The phone's own currency (KRW in Korea), or null where the locale has none. An app language chosen without a region ("ko")
     * takes the region its speakers mostly live in.
     */
    fun homeCurrency(locale: Locale): String? {
        val region = locale.country.ifEmpty { LIKELY_REGION[locale.language].orEmpty() }
        if (region.isEmpty()) return null
        return runCatching { Currency.getInstance(Locale(locale.language, region)).currencyCode }.getOrNull()
    }

    /** The app language's currency and the phone's region's currency (they differ for a traveller or a second language). */
    fun homeCurrencies(app: Locale, system: Locale): Set<String> = setOfNotNull(homeCurrency(app), homeCurrency(system))

    private val LIKELY_REGION = mapOf(
        "ko" to "KR", "ja" to "JP", "zh" to "CN", "en" to "US", "de" to "DE", "fr" to "FR", "es" to "ES", "it" to "IT", "pt" to "BR",
        "ru" to "RU", "vi" to "VN", "th" to "TH", "id" to "ID", "in" to "ID", "tr" to "TR", "pl" to "PL", "nl" to "NL", "sv" to "SE",
        "hi" to "IN", "ar" to "SA", "ms" to "MY", "uk" to "UA", "cs" to "CZ", "da" to "DK", "nb" to "NO", "fi" to "FI", "he" to "IL",
    )
}

enum class LimitCheck { OK, MISSING, PER_ABOVE_MONTHLY }
