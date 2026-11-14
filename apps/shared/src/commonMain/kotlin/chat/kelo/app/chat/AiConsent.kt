package chat.kelo.app.chat

import chat.kelo.app.core.ApiError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Asking once before chats go to an AI service (server ai_consent.py; App Store guideline 5.1.2(i)). The app sends every message
 * with `"ai_consent": "check"`; while nobody has agreed on this server, the server holds it back with
 * 428 `{"detail": {"code": "ai_consent_required", "company": "Anthropic", "privacy_url": "https://kelo.chat/privacy"}}`, before it is
 * stored or sent. The chat then asks: Agree (PUT /api/ai-consent, then the same message again) or Not now (the text back in the
 * composer, nothing sent). One answer covers the whole server; changing the model does not ask again (the model picker says where
 * chats go instead).
 */
sealed interface ChatsGo {
    data class To(val company: String) : ChatsGo
    data object Local : ChatsGo
}

data class AiConsentNeed(val company: String, val privacyUrl: String)

/** A message held back for the question; memory only (toString leaves the text out). */
class ConsentAsk(
    val need: AiConsentNeed, val text: String, val answers: List<ReplyAnswer>, val fromComposer: Boolean, val tookAttachments: Boolean,
    val secrets: String?,
) {
    override fun toString(): String = "ConsentAsk(${need.company})"
}

object AiConsent {
    const val CODE = "ai_consent_required"
    /** The request field value that asks the server to check */
    const val CHECK = "check"
    const val PRIVACY_URL = "https://kelo.chat/privacy"

    /** The 428 answer of a chat send, or null for any other failure. */
    fun of(e: Throwable): AiConsentNeed? = (e as? ApiError)?.takeIf { it.status == 428 }?.detailJson?.let(::parse)

    fun parse(detail: JsonObject?): AiConsentNeed? {
        if (detail == null || str(detail["code"]) != CODE) return null
        return AiConsentNeed(str(detail["company"]), str(detail["privacy_url"]).ifEmpty { PRIVACY_URL })
    }

    /** Providers whose address the owner types: without a company they stay on the owner's own network. */
    private val LOCAL = setOf("custom", "lmstudio")

    /**
     * The line under a chosen provider in the model picker and on the Model page: "Your chats go to Google" ([ChatsGo.To]),
     * "Your chats stay on your own network" ([ChatsGo.Local]), or nothing (no provider chosen, or an older server that does not say).
     */
    fun chatsGo(provider: String?, company: String?): ChatsGo? = when {
        provider.isNullOrBlank() -> null
        !company.isNullOrBlank() -> ChatsGo.To(company.trim())
        provider in LOCAL -> ChatsGo.Local
        else -> null
    }

    private fun str(e: kotlinx.serialization.json.JsonElement?): String =
        ((e as? JsonPrimitive)?.takeIf { it.isString })?.content.orEmpty()
}
