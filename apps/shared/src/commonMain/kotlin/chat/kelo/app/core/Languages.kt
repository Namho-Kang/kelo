package chat.kelo.app.core

import java.util.Locale

/** A language the app can be shown in, under its own name so anyone can find theirs. [code] is a BCP 47 tag. */
data class Language(val code: String, val name: String)

object Languages {
    /** The choice that follows the phone's own language (English when the phone's language is not one of [ALL]). */
    const val SYSTEM = "system"

    /**
     * Adding one: an entry here, its res/values-<dir>/ strings (apps/android/tools/strings_res.py LOCALE_DIRS), res/xml/locales_config.xml
     * and the server's src/assistant/locales/<code>.json.
     */
    val ALL: List<Language> = listOf(
        Language("en", "English"),
        Language("ko", "한국어"),
        Language("ja", "日本語"),
        Language("zh-Hans", "简体中文"),
        Language("zh-Hant", "繁體中文"),
        Language("es", "Español"),
        Language("fr", "Français"),
        Language("de", "Deutsch"),
        Language("pt-BR", "Português (Brasil)"),
        Language("it", "Italiano"),
        Language("ru", "Русский"),
        Language("id", "Bahasa Indonesia"),
        Language("vi", "Tiếng Việt"),
        Language("th", "ไทย"),
        Language("tr", "Türkçe"),
        Language("ar", "العربية"),
        Language("hi", "हिन्दी"),
        Language("pl", "Polski"),
        Language("nl", "Nederlands"),
        Language("uk", "Українська"),
    )

    fun isKnown(code: String?): Boolean = code == SYSTEM || (code != null && ALL.any { it.code == code })

    /** A picked language (not [SYSTEM]) or null: what the app shows in instead of the phone's language. */
    fun override(code: String?): String? = code?.takeIf { it != SYSTEM && ALL.any { l -> l.code == it } }

    /**
     * Which of [ALL] a device language tag ("en-US", "zh-Hant-TW", "zh-HK", "pt-PT", "in-ID") is shown in; null when the app does
     * not have it (the app is then in English, the default resources).
     */
    fun match(tag: String): String? {
        val loc = Locale.forLanguageTag(tag.replace('_', '-'))
        val lang = when (val l = loc.language.lowercase(Locale.ROOT)) { "in" -> "id"; "iw" -> "he"; else -> l }
        if (lang == "zh") {
            val traditional = loc.script.equals("Hant", true) ||
                (loc.script.isEmpty() && loc.country.uppercase(Locale.ROOT) in setOf("TW", "HK", "MO"))
            return if (traditional) "zh-Hant" else "zh-Hans"
        }
        if (lang == "pt") return "pt-BR"
        return ALL.firstOrNull { it.code == lang }?.code
    }

    /** The first of the device's languages (e.g. ["en-US", "ko-KR"]) the app has; otherwise English. */
    fun suggested(deviceTags: List<String>): String = deviceTags.firstNotNullOfOrNull(::match) ?: "en"

    fun name(code: String?): String = ALL.firstOrNull { it.code == code }?.name.orEmpty()

    /**
     * What a pick in My → Language tells the server (PUT /api/language, the language of notifications, the briefing and scheduled
     * runs): the picked code, or for [SYSTEM] the one the app is shown in for the phone's languages [deviceTags].
     */
    fun forServer(code: String?, deviceTags: List<String>): String = override(code) ?: suggested(deviceTags)
}
