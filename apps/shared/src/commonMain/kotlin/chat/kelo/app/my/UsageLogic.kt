package chat.kelo.app.my

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** One block of limits for the models it covers. [name] is already put into words. */
data class UsageGroup(val group: String?, val name: String, val active: Boolean, val windows: List<UsageWindow>)

/** When a limit resets: the server's own text, or a time and how far off it is. */
sealed interface ResetInfo {
    data class Text(val text: String) : ResetInfo
    data class At(val rel: Rel, val epochMillis: Long) : ResetInfo

    sealed interface Rel {
        data object Soon : Rel
        data class Minutes(val n: Long) : Rel
        data class Hours(val n: Long) : Rel
        data class Days(val n: Long) : Rel
    }
}

/** The usage rules without the UI, so they can be tested. Wording comes in from string resources. */
object UsageLogic {
    /** The periods the usage card offers, in days; [DEFAULT_DAYS] is chosen first. */
    val PERIODS = listOf(1, 7, 30)
    const val DEFAULT_DAYS = 1

    /** Tokens read fresh: the whole prompt minus what the provider read from its cache (never below 0). */
    fun fresh(input: Long, cacheRead: Long): Long = (input - cacheRead).coerceAtLeast(0)

    /** The big number: fresh input and the output. */
    fun used(input: Long, output: Long, cacheRead: Long): Long = fresh(input, cacheRead) + output

    /** One legend row: a model (or, with [others] > 0, the rest together) with its tokens and its share of the total in %. */
    data class ModelShare(val provider: String, val model: String, val tokens: Long, val percent: Int, val others: Int = 0)

    /** The legend: by tokens, largest first; with 6 or more models the top [top] and one "N others" row. Empty when nothing was used. */
    fun modelShares(models: List<ModelUsage>, top: Int = 4): List<ModelShare> {
        val rows = models.map { ModelShare(it.provider, it.model, used(it.inputTokens, it.outputTokens, it.cacheReadTokens), 0) }
            .filter { it.tokens > 0 }.sortedByDescending { it.tokens }
        val total = rows.sumOf { it.tokens }
        if (total <= 0L) return emptyList()
        val shown = if (rows.size > top + 1) rows.take(top) + ModelShare("", "", rows.drop(top).sumOf { it.tokens }, 0, others = rows.size - top)
                    else rows
        return shown.map { it.copy(percent = ((it.tokens * 100.0) / total).roundToInt()) }
    }

    /**
     * A token count in a few characters: Korean/Japanese/Chinese count by 만/万 (10^4) and 억/億 (10^8), others by K / M / B; one decimal
     * below 10 of the unit ("2.9M", "299만", "1.2억"), whole numbers under 10,000 as they are ("8,512").
     */
    fun compactTokens(n: Long, language: String): String {
        fun one(v: Double) = if (v < 10) String.format(java.util.Locale.ROOT, "%.1f", v).removeSuffix(".0") else v.roundToLong().toString()
        if (n < 10_000) return java.text.NumberFormat.getIntegerInstance(java.util.Locale.ROOT).format(n)
        return when (language) {
            "ko" -> if (n >= 100_000_000) one(n / 1e8) + "억" else one(n / 1e4) + "만"
            "ja", "zh" -> if (n >= 100_000_000) one(n / 1e8) + "億" else one(n / 1e4) + "万"
            else -> when {
                n >= 1_000_000_000 -> one(n / 1e9) + "B"
                n >= 1_000_000 -> one(n / 1e6) + "M"
                else -> one(n / 1e3) + "K"
            }
        }
    }

    /** The English labels the CLIs report, in the order they are replaced; the UI pairs each with its translation. */
    val LABEL_SOURCES = listOf("Current session", "Current week", "all models", "Weekly Limit Remaining", "Five Hour Limit Remaining")

    /** Each known English phrase replaced by its translation. */
    fun label(s: String, replacements: List<Pair<String, String>>): String =
        replacements.fold(s) { acc, (en, local) -> acc.replaceFirst(en, local) }

    private val MODELS_SUFFIX = Regex("\\s+models?$", RegexOption.IGNORE_CASE)
    private val AND = Regex("\\s+and\\s+", RegexOption.IGNORE_CASE)

    /** "Gemini Models" -> "Gemini 모델", "Claude and GPT models" -> "Claude · GPT 모델" ([models] = "%s 모델", [joiner] = " · "). */
    fun groupName(g: String, models: (String) -> String, joiner: String): String {
        val m = MODELS_SUFFIX.find(g)
        val named = if (m != null) models(g.substring(0, m.range.first)) else g
        return named.replace(AND, joiner)
    }

    private val STOP = setOf("model", "models", "and", "the", "of")
    private val NON_WORD = Regex("[^a-z0-9]+")

    /** The limit group that covers the model in use ("Gemini Models" for gemini-3.8-flash-high), or null when that cannot be told. */
    fun activeGroup(groups: List<String>, model: String): String? {
        val m = model.lowercase()
        val hits = groups.filter { g ->
            g.lowercase().split(NON_WORD).any { word -> word.length > 1 && word !in STOP && m.contains(word) }
        }
        return if (hits.size == 1) hits[0] else null
    }

    /** The limit windows split by the models they cover; the group of the model in use comes first. Empty when the provider reports none. */
    fun groups(u: Usage?, model: String, name: (String) -> String): List<UsageGroup> {
        val sub = u?.subscription ?: return emptyList()
        if (sub.error != null) return emptyList()
        val names = sub.windows.map { it.group }.distinct()
        val active = activeGroup(names.filterNotNull().filter { it.isNotEmpty() }, model)
        val all = names.map { g ->
            UsageGroup(g, if (!g.isNullOrEmpty()) name(g) else "", g != null && g == active, sub.windows.filter { it.group == g })
        }
        return all.filter { it.active } + all.filterNot { it.active }
    }

    /** In the short form, the groups other than the one in use take a single line each, but only when the one in use is known. */
    fun oneLine(groups: List<UsageGroup>, g: UsageGroup, full: Boolean): Boolean = !full && !g.active && groups.any { it.active }

    /**
     * The parsed time (the server's ISO time, else its words read here: "Nov 7, 11:40am (UTC)", "Nov 6 at 6am (Asia/Seoul)",
     * "11:40am (UTC)", "in 3 hours"), or the server's text when nothing can be read from it.
     */
    fun reset(w: UsageWindow, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): ResetInfo? {
        val at = w.resetsAt?.let { parseTime(it, zone) }
            ?: w.resetsText?.let { parseResetText(it, nowMillis) }
            ?: return w.resetsText?.let { ResetInfo.Text(it) }
        val mins = ((at - nowMillis) / 60000.0).roundToLong()
        val rel = when {
            mins <= 0 -> ResetInfo.Rel.Soon
            mins < 60 -> ResetInfo.Rel.Minutes(mins)
            mins < 2880 -> ResetInfo.Rel.Hours((mins / 60.0).roundToLong())
            else -> ResetInfo.Rel.Days((mins / 1440.0).roundToLong())
        }
        return ResetInfo.At(rel, at)
    }

    /** Within this many minutes a reset is told as "N시간 후"; further off, as a date and time in the phone's zone. */
    const val NEAR_MINUTES = 12 * 60L

    /** How a reset is said: soon, in minutes or hours when near, else on a date and time; or the server's own words. */
    sealed interface Phrase {
        data object Soon : Phrase
        data class InMinutes(val n: Long) : Phrase
        data class InHours(val n: Long) : Phrase
        data class On(val epochMillis: Long) : Phrase
        data class Raw(val text: String) : Phrase
    }

    fun phrase(r: ResetInfo, nowMillis: Long): Phrase = when (r) {
        is ResetInfo.Text -> Phrase.Raw(r.text)
        is ResetInfo.At -> {
            val mins = ((r.epochMillis - nowMillis) / 60000.0).roundToLong()
            when {
                mins <= 1 -> Phrase.Soon
                mins < 60 -> Phrase.InMinutes(mins)
                mins < NEAR_MINUTES -> Phrase.InHours((mins / 60.0).roundToLong())
                else -> Phrase.On(r.epochMillis)
            }
        }
    }

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val ZONE = Regex("""\(([^)]+)\)\s*$""")
    private val DATE = Regex("""\b([A-Za-z]{3})[a-z]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s*(\d{4}))?""")
    private val TIME_12 = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*([ap])\.?\s?m\.?\b""", RegexOption.IGNORE_CASE)
    private val TIME_24 = Regex("""\b(\d{1,2}):(\d{2})\b""")
    private val IN = Regex("""\bin\s+(\d+)\s*(minute|min|hour|hr|h|day|d)s?\b""", RegexOption.IGNORE_CASE)

    /**
     * A reset time from a CLI's own words, in the zone it names (UTC when it names none or an unknown one): a month and day with
     * a time ("Nov 7, 11:40am (UTC)", "Nov 6 at 6am (Asia/Seoul)"), a time alone (the next time it comes), or "in 3 hours". Null
     * when nothing can be read.
     */
    fun parseResetText(text: String, nowMillis: Long): Long? {
        val t = text.trim()
        IN.find(t)?.let { m ->
            val n = m.groupValues[1].toLong()
            val unit = m.groupValues[2].lowercase()
            val mins = when { unit.startsWith("m") -> n; unit.startsWith("h") -> n * 60; else -> n * 1440 }
            return nowMillis + mins * 60_000
        }
        val zone = ZONE.find(t)?.groupValues?.get(1)?.trim()?.let { z ->
            runCatching { ZoneId.of(if (z.equals("UTC", true) || z.equals("GMT", true)) "UTC" else z) }.getOrNull()
        } ?: ZoneId.of("UTC")
        val body = ZONE.replace(t, "").trim()
        val time = TIME_12.find(body)?.let { m ->
            val h = m.groupValues[1].toInt() % 12 + if (m.groupValues[3].equals("p", true)) 12 else 0
            h to (m.groupValues[2].ifEmpty { "0" }.toInt())
        } ?: TIME_24.find(body)?.let { m -> m.groupValues[1].toInt() to m.groupValues[2].toInt() } ?: return null
        if (time.first !in 0..23 || time.second !in 0..59) return null
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val date = DATE.find(body)?.let { m ->
            val month = MONTHS.indexOf(m.groupValues[1].lowercase()) + 1
            if (month == 0) return@let null
            val day = m.groupValues[2].toInt()
            val year = m.groupValues[3].toIntOrNull() ?: now.year
            runCatching { java.time.LocalDate.of(year, month, day) }.getOrNull()?.let { d ->
                // no year written: a date long past is next year's (a January reset read in December)
                if (m.groupValues[3].isEmpty() && d.isBefore(now.toLocalDate().minusDays(2))) d.plusYears(1) else d
            }
        }
        var at = (date ?: now.toLocalDate()).atTime(time.first, time.second).atZone(zone)
        if (date == null && at.isBefore(now)) at = at.plusDays(1)
        return at.toInstant().toEpochMilli()
    }

    /** ISO 8601 with an offset or "Z"; a time without one is read in [zone], as a browser's Date does. */
    fun parseTime(s: String, zone: ZoneId): Long? {
        val t = s.trim()
        return try {
            OffsetDateTime.parse(t).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                Instant.parse(t).toEpochMilli()
            } catch (_: DateTimeParseException) {
                try {
                    LocalDateTime.parse(t).atZone(zone).toInstant().toEpochMilli()
                } catch (_: DateTimeParseException) {
                    null
                }
            }
        }
    }

    /** The bar's width and colour: capped at 100, "hot" from 90%. */
    /** The one number for a settings row: the most used window of the group in use (else of the first group), or null. */
    fun summaryPercent(groups: List<UsageGroup>): Int? =
        (groups.firstOrNull { it.active } ?: groups.firstOrNull())?.windows?.maxOfOrNull { it.usedPercent }?.coerceIn(0, 100)

    fun barFraction(usedPercent: Int): Float = (usedPercent.coerceIn(0, 100)) / 100f
    fun isHot(usedPercent: Int): Boolean = usedPercent >= 90
    fun left(usedPercent: Int): Int = maxOf(0, 100 - usedPercent)

    /** The background model's last failure, shown only while that model is still the one chosen on the page. */
    fun fallbackShown(state: BackgroundModelState?, provider: String, model: String): BackgroundFallback? =
        state?.lastFallback?.takeIf { provider.isNotEmpty() && state.provider == provider && state.model == model }

    /** The limits section: the current model's service first (its subscription, else its account), then the others that report something. */
    data class LimitParts(val current: Subscription?, val currentAccount: ProviderAccount?, val others: List<Subscription>, val otherAccounts: List<ProviderAccount>) {
        val otherCount: Int get() = others.size + otherAccounts.size
    }

    fun limitParts(u: Usage?): LimitParts {
        if (u == null) return LimitParts(null, null, emptyList(), emptyList())
        val subs = u.subscriptions.ifEmpty { listOfNotNull(u.subscription?.copy(role = "main")) }
            .filter { it.error != null || it.windows.isNotEmpty() }
        val accounts = u.accounts.filter { it.error == null }
        val current = subs.firstOrNull { it.role == "main" }
        val currentAccount = if (current == null) accounts.firstOrNull { it.role == "main" || it.role.isEmpty() } else null
        return LimitParts(current, currentAccount, subs.filter { it !== current }, accounts.filter { it !== currentAccount })
    }
}
