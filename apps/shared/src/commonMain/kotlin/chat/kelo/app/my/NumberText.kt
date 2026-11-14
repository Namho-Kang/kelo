package chat.kelo.app.my

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Sizes, CPU counts, whole numbers and money as text in a given language: the rules of 마이 → 사용량 and 마이 → Kelo 서버 자원.
 * Pure, shared with the desktop app (so both round and write numbers the same way); the iPhone app has the same rules in Swift
 * (KeloMy MyFormat + KeloShared JavaNumbers) and the same cases in its tests.
 *
 * What "the same way" means, since the platforms differ by default:
 *  - one decimal ("%.1f"): half up, on the number's shortest decimal form (1.25 -> "1.3", 0.15 -> "0.2"), the language's decimal
 *    sign and digits ("1,3" in German, "١٫٣" in Arabic), no grouping;
 *  - whole sizes (MB from 10, KB): rounded half up and written with plain digits;
 *  - money: the currency's own symbol, always two decimals, half even, in the app's language (not the computer's).
 */
object NumberText {
    const val GB = "gb"
    const val MB = "mb"
    const val KB = "kb"

    private const val KIB = 1024.0
    private const val MIB = 1024.0 * 1024
    private const val GIB = 1024.0 * 1024 * 1024

    /** 1.25 -> "1.3" ("%.1f" in [locale]). */
    fun oneDecimal(v: Double, locale: Locale): String = String.format(locale, "%.1f", v)

    /** 1234567 -> "1,234,567" in [locale]. */
    fun whole(n: Long, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(n)

    /** (number, unit): GB with one decimal, MB with one decimal under 10 MB and whole from there, else whole KB. */
    fun storage(n: Long, locale: Locale): Pair<String, String> = when {
        n >= GIB -> oneDecimal(n / GIB, locale) to GB
        n >= MIB -> (if (n >= 10 * MIB) (n / MIB).roundToLong().toString() else oneDecimal(n / MIB, locale)) to MB
        else -> max(0L, (n / KIB).roundToLong()).toString() to KB
    }

    /** (number, unit) of a file: MB with one decimal from 1 MB, else whole KB (at least 1). */
    fun file(n: Long, locale: Locale): Pair<String, String> =
        if (n >= MIB) oneDecimal(n / MIB, locale) to MB else max(1L, (n / KIB).roundToLong()).toString() to KB

    /** A CPU count: "2" for 2.0, "1.5" otherwise. */
    fun cpu(n: Double): String = if (n == Math.floor(n)) n.toLong().toString() else n.toString()

    /** "$0.19", "-$0.19", "¥12.30" in [locale]: two decimals, the currency's own symbol when it is known. */
    fun money(v: Double, currency: String, locale: Locale): String {
        val f = runCatching {
            NumberFormat.getCurrencyInstance(locale).apply {
                this.currency = Currency.getInstance(currency.ifBlank { "USD" })
                minimumFractionDigits = 2; maximumFractionDigits = 2
            }
        }.getOrNull()
        return f?.format(v) ?: String.format(Locale.ROOT, "%.2f %s", v, currency)
    }
}
