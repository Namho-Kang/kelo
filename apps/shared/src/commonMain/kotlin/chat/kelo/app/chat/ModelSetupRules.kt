package chat.kelo.app.chat

import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Profile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** POST /api/providers/{name}/login/start, GET …/login/status and POST …/login/finish answer with this. */
@Serializable
data class SignInSession(
    val session: String = "",
    /** "device_code": open [url], type [userCode] there, the app notices by itself. "url_code": open [url], paste back the code */
    val kind: String = "",
    val url: String = "",
    @SerialName("user_code") val userCode: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 0,
    /** pending | done | failed | expired */
    val state: String = "pending",
    val message: String? = null,
    val account: String? = null,
)

/** When the sign-in shows and what the server's answers mean. */
object SignInRules {
    /** Seconds as m:ss. */
    fun mmss(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** Whole seconds left until [deadlineMs], never below zero. */
    fun secondsLeft(deadlineMs: Long, nowMs: Long): Long = Math.round((deadlineMs - nowMs).coerceAtLeast(0) / 1000.0)

    enum class Problem { CONSENT, ALREADY, OFF, FAILED }

    /** A provider the app can sign in (the server says `signin`) that is not signed in now. */
    fun offered(signin: Boolean, signedIn: Boolean?): Boolean = signin && signedIn != true

    fun isDeviceCode(s: SignInSession): Boolean = s.kind == "device_code"

    /** The pasted code (or the address the page ended on) can be sent: one line without spaces. */
    fun pasteReady(text: String): Boolean = text.trim().let { it.isNotEmpty() && it.none(Char::isWhitespace) && it.length <= 4096 }

    fun finished(s: SignInSession): Boolean = s.state == "done"

    /** The attempt ended without signing in; the user starts again. */
    fun ended(s: SignInSession): Boolean = s.state == "failed" || s.state == "expired"

    fun problem(e: ApiError): Problem = when {
        e.status == 428 -> Problem.CONSENT
        e.status == 409 && e.detail.startsWith("already_signed_in") -> Problem.ALREADY
        e.status == 503 -> Problem.OFF
        else -> Problem.FAILED
    }

    /** The server's own sentence, without a leading machine word ("already_signed_in: …"). */
    fun readable(e: ApiError): String = if (e.isNetwork) "" else e.detail.substringAfter("already_signed_in: ").trim()
}


/** A provider + model pair as the sheet edits it. */
data class ModelChoice(val provider: String, val model: String) {
    companion object {
        val NONE = ModelChoice("", "")
    }
}

/** The model sheet's rules, kept apart from the UI so they can be tested. */
object ModelRules {
    /** A fallback on the main model's own provider (from before that rule) is shown as empty. */
    fun initialFallback(p: Profile): ModelChoice =
        if (p.fallbackProvider.isNotEmpty() && p.fallbackProvider == p.provider) ModelChoice.NONE else ModelChoice(p.fallbackProvider, p.fallbackModel)

    /** Moving the main model onto the fallback's provider empties the fallback. Returns the new fallback and whether it was cleared. */
    fun afterMainChange(main: ModelChoice, fallback: ModelChoice): Pair<ModelChoice, Boolean> =
        if (main.provider.isNotEmpty() && main.provider == fallback.provider) ModelChoice.NONE to true else fallback to false

    /** A chosen provider needs a model name. */
    fun complete(c: ModelChoice): Boolean = c.provider.isEmpty() || c.model.isNotBlank()
}

