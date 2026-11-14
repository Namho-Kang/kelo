package chat.kelo.app.chat

import chat.kelo.app.core.ApiError

/** Which service name the consent text uses for a provider. */
enum class ConsentService { CLAUDE_SUBSCRIPTION, ANTIGRAVITY, CHATGPT_SUBSCRIPTION, QWEN, MINIMAX, OTHER }

object ProviderConsentRules {
    /** Unofficial and not (or not for this version) agreed to. */
    fun needs(p: ProviderInfo): Boolean = p.unofficial && (p.consent == null || p.consent.version < p.consentVersion)

    /** The divider group in a provider list: unofficial ones go last. */
    fun group(p: ProviderInfo): Int = if (p.unofficial) 1 else 0

    fun service(name: String): ConsentService = when {
        name.startsWith("claude-subscription") -> ConsentService.CLAUDE_SUBSCRIPTION
        name.startsWith("antigravity") -> ConsentService.ANTIGRAVITY
        name.startsWith("openai-codex") -> ConsentService.CHATGPT_SUBSCRIPTION
        name.startsWith("qwen") -> ConsentService.QWEN
        name.startsWith("minimax") -> ConsentService.MINIMAX
        else -> ConsentService.OTHER
    }

    /** The Korean subject particle after a vendor's name, as it is read aloud (Anthropic이, Google이, OpenAI가, Alibaba가, MiniMax가). */
    fun subjectParticle(vendor: String): String {
        KNOWN[vendor.trim().lowercase()]?.let { return it }
        val last = vendor.trim().lastOrNull() ?: return "가"
        if (last in '가'..'힣') return if ((last - '가') % 28 != 0) "이" else "가"
        return if (last.lowercaseChar() in "aeiouy") "가" else "이"
    }

    /**
     * [word] as it reads inside "Allow …?": in English a tool label is a capitalized "-ing" phrase ("Adding an event"), which reads
     * as "Allow adding an event?". Other languages and names (Gmail, GitHub) are kept as they are.
     */
    fun askWord(word: String, language: String): String {
        if (language != "en") return word
        val first = word.substringBefore(' ')
        if (first.length < 4 || !first.endsWith("ing") || !first[0].isUpperCase() || first.drop(1).any { it.isUpperCase() }) return word
        return word[0].lowercaseChar() + word.substring(1)
    }

    /** The Korean object particle after [word] (사이트 열기를, 로그인 정보 입력을); Latin endings are read as they sound in most names. */
    fun objectParticle(word: String): String {
        val last = word.trim().lastOrNull { it.isLetterOrDigit() } ?: return "를"
        if (last in '가'..'힣') return if ((last - '가') % 28 != 0) "을" else "를"
        if (last.isDigit()) return if (last in "013678") "을" else "를"
        return if (last.lowercaseChar() in "aeiouy") "를" else "을"
    }

    private val KNOWN = mapOf("anthropic" to "이", "google" to "이", "openai" to "가", "alibaba" to "가", "minimax" to "가")

    /** The providers among the chosen main and fallback that still need the user's agreement, main first. */
    fun pending(main: String, fallback: String, providers: List<ProviderInfo>): List<ProviderInfo> =
        listOf(main, fallback).filter { it.isNotEmpty() }.distinct().mapNotNull { n -> providers.firstOrNull { it.name == n } }.filter(::needs)

    /** "consent_required:<provider>" in a 428 from PUT /api/profile, or null. */
    fun requiredBy(e: ApiError): String? =
        if (e.status == 428) e.detail.substringAfter("consent_required:", "").trim().ifEmpty { null } else null

    /** The key under which this phone remembers it already asked about a saved provider (the model page asks that only once). */
    fun askedKey(p: ProviderInfo): String = "asked." + p.name + "." + p.consentVersion
}
