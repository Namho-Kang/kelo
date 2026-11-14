package chat.kelo.app.wallet

import kotlinx.coroutines.sync.withLock
import java.time.YearMonth

/**
 * Card scanning, the parts that are plain rules (no camera, no Play services): what a frame of recognised text says, when several
 * frames agree, what a scan puts into the form, and which way of scanning this phone offers. The CVC is never read: it is printed
 * on the back and the owner types it. Nothing here keeps, logs or sends a number; a reading lives only until the form takes it.
 */

/** What a scan fills in: the number (digits only) and, when it was read, the expiry as the form's "MMYY" digits. */
class CardScanResult(val number: String, val expiry: String?) {
    override fun toString(): String = "CardScanResult(…)"   // never the number, not even in a debugger's or crash's text
}

/** What one camera frame said: a card number and the expiry dates printed on it (both may be missing). */
class CardScanRead(val number: String?, val expiries: List<String>)

object CardScanText {
    // digits with single spaces or dashes between them; the pieces around are checked separately
    private val RUN = Regex("""\d(?:[ \-]?\d)*""")
    private val DIGIT_LINE = Regex("""\d{3,6}(?: \d{3,7})*""")
    private val DATE =Regex("""(?<![\d/.\-])(0[1-9]|1[0-2])\s?[/\-.]\s?(20\d{2}|\d{2})(?![\d/.\-])""")
    // how OCR often misreads a digit inside a run of digits ("4O12" -> "4012")
    private val LOOKALIKE = mapOf('O' to '0', 'o' to '0', 'D' to '0', 'Q' to '0', 'I' to '1', 'l' to '1', '|' to '1', 'S' to '5', 'B' to '8', 'Z' to '2')

    /** Reads one frame's text lines. */
    fun read(lines: List<String>): CardScanRead {
        var number: String? = null
        val dates = ArrayList<String>(2)
        for (line in lines) {
            if (number == null) number = number(line)
            dates += expiries(line)
        }
        // the recognizer sometimes splits the number's groups over lines ("4242 4242" / "4242 4242"): try neighbouring lines
        // made only of digits together
        if (number == null) {
            val groups = lines.map { it.trim() }
            for (i in groups.indices) {
                if (!DIGIT_LINE.matches(groups[i])) continue
                var joined = groups[i]
                for (j in i + 1 until minOf(groups.size, i + 4)) {
                    if (!DIGIT_LINE.matches(groups[j])) break
                    joined += " " + groups[j]
                    number = number(joined)
                    if (number != null) break
                }
                if (number != null) break
            }
        }
        return CardScanRead(number, dates)
    }

    /**
     * A card number in [line]: 13 to 19 digits, written together or in groups split by spaces or dashes, passing the check digit,
     * with a length its brand uses. A longer run of digits (an account number, a barcode) is not cut down to fit.
     */
    fun number(line: String): String? {
        val fixed = lookalikes(line)
        for (m in RUN.findAll(fixed)) {
            val run = m.value
            val digits = run.filter { it in '0'..'9' }
            if (digits.length !in 13..19) continue
            if (!groupsLookLikeACard(run)) continue
            if (plausible(digits)) return digits
        }
        return null
    }

    /** The checks a candidate number passes: check digit, no leading 0, not one digit repeated, a length its brand has. */
    fun plausible(digits: String): Boolean {
        if (digits.length !in 13..19 || digits.any { it !in '0'..'9' }) return false
        if (digits[0] == '0' || digits.all { it == digits[0] }) return false
        if (!CardNumber.luhn(digits)) return false
        return when (CardNumber.brand(digits)) {
            CardBrand.AMEX -> digits.length == 15
            CardBrand.VISA -> digits.length == 13 || digits.length == 16 || digits.length == 19
            CardBrand.MASTERCARD -> digits.length == 16
            CardBrand.DINERS -> digits.length in 14..19
            else -> digits.length in 14..19
        }
    }

    /** Groups of 3 to 6 digits (4-4-4-4, 4-6-5, 4-6-4, …) or one block; a run like "12 345 6 78 …" is not a card number. */
    private fun groupsLookLikeACard(run: String): Boolean {
        val parts = run.split(' ', '-')
        if (parts.size == 1) return true
        if (parts.size > 5) return false
        return parts.dropLast(1).all { it.length in 3..6 } && parts.last().length in 1..7
    }

    /** Expiry dates in [line] as "MMYY" ("04/28", "04-28", "04 / 2028", "04.28"). */
    fun expiries(line: String): List<String> = DATE.findAll(line).map { m ->
        val month = m.groupValues[1]
        val year = m.groupValues[2].takeLast(2)
        month + year
    }.toList()

    private fun lookalikes(line: String): String {
        if (line.none { it in LOOKALIKE }) return line
        val chars = line.toCharArray()
        for (i in chars.indices) {
            val to = LOOKALIKE[chars[i]] ?: continue
            // only between digits (or a digit and a separator), so words like "VISA" or "BANK" stay as they are
            val before = chars.getOrNull(i - 1)
            val after = chars.getOrNull(i + 1)
            val nearDigit = (before != null && before in '0'..'9') || (after != null && after in '0'..'9')
            val nearLetter = (before != null && before.isLetter() && before !in LOOKALIKE) || (after != null && after.isLetter() && after !in LOOKALIKE)
            if (nearDigit && !nearLetter) chars[i] = to
        }
        return String(chars)
    }

    /** The later of the dates (a card may show both "valid from" and "valid thru"), or null. */
    fun latest(expiries: Collection<String>): String? = expiries.maxByOrNull { it.drop(2) + it.take(2) }
}

/**
 * Several frames must agree before a scan counts: the same number in [NUMBER_FRAMES] frames. The expiry is taken when the same date
 * was seen in [EXPIRY_FRAMES] frames; after the number is settled, up to [EXPIRY_GRACE] more frames are given to the date, and then
 * the scan ends without it (the owner types it). [clear] forgets everything read so far.
 */
class CardScanAgreement(private val now: () -> YearMonth = { YearMonth.now() }) {
    private val numbers = HashMap<String, Int>()
    private val dates = HashMap<String, Int>()
    private var settled: String? = null
    private var since = 0

    /** One frame's reading; returns the result once the frames agree, else null. */
    fun offer(read: CardScanRead): CardScanResult? {
        for (d in read.expiries.toSet()) {
            if (Expiry.check(d, now()) !is ExpiryCheck.Ok) continue   // a "valid from" date in the past, a date that cannot be one
            dates[d] = (dates[d] ?: 0) + 1
        }
        val n = read.number
        if (settled == null && n != null) {
            numbers[n] = (numbers[n] ?: 0) + 1
            if (numbers[n]!! >= NUMBER_FRAMES) settled = n
        }
        if (numbers.size > MAX_KEPT) numbers.clear()   // a moving camera reading noise: start over rather than grow
        if (dates.size > MAX_KEPT) dates.clear()
        val number = settled ?: return null
        val date = CardScanText.latest(dates.filterValues { it >= EXPIRY_FRAMES }.keys)
        if (date != null || since >= EXPIRY_GRACE) {
            val result = CardScanResult(number, date)
            clear()
            return result
        }
        since++
        return null
    }

    fun clear() {
        numbers.clear(); dates.clear(); settled = null; since = 0
    }

    companion object {
        const val NUMBER_FRAMES = 3
        const val EXPIRY_FRAMES = 2
        const val EXPIRY_GRACE = 10
        private const val MAX_KEPT = 12
    }
}

/** What a scan does to the card form. */
object CardScanFill {
    /** Google Pay's card recognition: its number and expiry month/year (year with four digits), checked like any other reading. */
    fun fromGooglePay(pan: String?, month: Int?, year: Int?): CardScanResult? {
        val digits = pan.orEmpty().filter { it in '0'..'9' }
        if (!CardScanText.plausible(digits)) return null
        val expiry = if (month != null && year != null && month in 1..12 && year in 2000..2099) {
            String.format(java.util.Locale.ROOT, "%02d%02d", month, year % 100)
        } else null
        return CardScanResult(digits, expiry)
    }

    /**
     * The add form after a scan: the number and (when read) the expiry are filled in; the CVC, the names and the step are left as
     * they are, so the owner checks the number and then types the CVC.
     */
    fun apply(draft: AddCardDraft, scan: CardScanResult): AddCardDraft = draft.copy(
        number = CardNumber.digits(scan.number).take(CardNumber.maxLength(CardNumber.brand(scan.number))),
        expiry = scan.expiry?.let { Expiry.clean(it) } ?: draft.expiry,
    )
}

/** How this phone scans a card: Google Pay's card recognition, the camera with on-device text recognition, or not at all. */
enum class ScanPath { GOOGLE_PAY, ON_DEVICE, NONE }

/** What the one-time check found. */
class ScanProbe(
    /** a back camera exists */
    val camera: Boolean,
    /** Google Play services is installed and current */
    val playServices: Boolean,
    /** Google Pay handed out its card recognition screen */
    val googlePay: Boolean,
    /** the on-device text recognizer can be used (installed, or Play services can install it) */
    val textRecognizer: Boolean,
)

object CardScanChoice {
    fun path(p: ScanProbe): ScanPath = when {
        !p.camera || !p.playServices -> ScanPath.NONE
        p.googlePay -> ScanPath.GOOGLE_PAY
        p.textRecognizer -> ScanPath.ON_DEVICE
        else -> ScanPath.NONE
    }

    /** Google Pay's screen failed to open when asked for: the camera scan instead, when this phone has it. */
    fun afterGooglePayFailed(p: ScanProbe): ScanPath = path(ScanProbe(p.camera, p.playServices, googlePay = false, p.textRecognizer))
}

/**
 * The check is made once per app run ([probe] is the Android part); later calls get the same answer. [googlePayFailed] downgrades it
 * when Google Pay's screen fails later, so the next tap goes straight to the camera.
 */
class ScanPathCache(private val probe: suspend () -> ScanProbe) {
    private val lock = kotlinx.coroutines.sync.Mutex()
    @Volatile private var found: ScanProbe? = null
    @Volatile var path: ScanPath? = null
        private set

    suspend fun get(): ScanPath {
        path?.let { return it }
        return lock.withLock {
            path ?: run {
                val p = runCatching { probe() }.getOrNull() ?: ScanProbe(camera = false, playServices = false, googlePay = false, textRecognizer = false)
                found = p
                CardScanChoice.path(p).also { path = it }
            }
        }
    }

    fun googlePayFailed(): ScanPath {
        val p = found ?: return ScanPath.NONE
        val next = CardScanChoice.afterGooglePayFailed(p)
        found = ScanProbe(p.camera, p.playServices, googlePay = false, p.textRecognizer)
        path = next
        return next
    }
}
