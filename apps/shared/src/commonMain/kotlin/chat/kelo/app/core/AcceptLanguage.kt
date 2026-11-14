package chat.kelo.app.core

import okhttp3.Interceptor
import java.util.Locale

/**
 * The `Accept-Language` header every request to the Kelo server carries, so the server writes its own words (connector names,
 * schedule lines, notifications) in the app's language and features the services of the phone's region.
 *
 * The language picked in the app comes first (with the phone's region when the phone has that language, e.g. "ko-KR"), then the
 * phone's own languages in order; each tagged region is followed by its bare language: "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7".
 */
object AcceptLanguage {
    const val HEADER = "Accept-Language"
    private const val MAX_ENTRIES = 6

    /** What [interceptor] sends; empty sends nothing. Set by the app on start, on a language pick and when the phone's languages change. */
    @Volatile var current: String = ""

    fun build(appLanguage: String?, deviceTags: List<String>): String {
        val device = deviceTags.mapNotNull(::clean)
        val app = appLanguage?.let(::clean)
        val ordered = mutableListOf<String>()
        if (app != null) {
            // the app's language with the phone's region for it, when the phone has one ("ko" + "ko-KR" -> "ko-KR")
            val regional = if ('-' in app) app else device.firstOrNull { base(it) == app && '-' in it }
            ordered += regional ?: app
        }
        ordered += device
        val tags = mutableListOf<String>()
        fun add(t: String) { if (tags.none { it.equals(t, ignoreCase = true) }) tags += t }
        ordered.forEach { t -> add(t); if ('-' in t) add(base(t)) }
        val kept = tags.take(MAX_ENTRIES)
        return kept.mapIndexed { i, t -> if (i == 0) t else "$t;q=${q(i)}" }.joinToString(",")
    }

    /** 1.0, 0.9, 0.8 … never below 0.1. */
    private fun q(i: Int): String = String.format(Locale.ROOT, "%.1f", (10 - i).coerceAtLeast(1) / 10.0)

    private fun base(tag: String): String = tag.substringBefore('-')

    /** A language tag as language[-REGION] in ASCII ("ko_kr" -> "ko-KR", "zh-Hant" -> "zh-TW"); null for nothing usable ("und", "", "*"). */
    private fun clean(tag: String): String? {
        val bits = tag.trim().replace('_', '-').split('-').filter { it.isNotEmpty() }
        val lang = bits.firstOrNull()?.lowercase(Locale.ROOT) ?: return null
        if (lang == "und" || !lang.all { it in 'a'..'z' } || lang.length !in 2..3) return null
        val region = bits.drop(1).firstOrNull { (it.length == 2 && it.all(Char::isLetter)) || (it.length == 3 && it.all(Char::isDigit)) }
            ?: when (bits.drop(1).firstOrNull { it.length == 4 && it.all(Char::isLetter) }?.lowercase(Locale.ROOT)) {
                "hant" -> "TW"   // a script alone (the app's zh-Hant / zh-Hans) still tells the server which Chinese
                "hans" -> "CN"
                else -> null
            }
        return if (region != null) "$lang-${region.uppercase(Locale.ROOT)}" else lang
    }

    /** Adds the header to a request that has none (the chat, the WebSocket of a live tab, pushes and background checks alike). */
    val interceptor = Interceptor { chain ->
        val req = chain.request()
        val value = current
        if (value.isEmpty() || req.header(HEADER) != null) chain.proceed(req)
        else chain.proceed(req.newBuilder().header(HEADER, value).build())
    }
}
