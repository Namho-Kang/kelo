package chat.kelo.app.chat

import chat.kelo.app.core.ApiError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** DELETE /api/providers/{name}/login answer. */
@Serializable
data class SignOutResult(
    val ok: Boolean = false,
    val removed: Boolean = false,
    /** A token the server's own settings hold: the app cannot take it away */
    @SerialName("still_signed_in") val stillSignedIn: Boolean = false,
    /** The fallback model was on this provider and is now empty */
    @SerialName("fallback_cleared") val fallbackCleared: Boolean = false,
)

/** When the sign-out action shows, and what an answer means. */
object SignOutRules {
    enum class Outcome { DONE, DONE_FALLBACK_CLEARED, DONE_ENV_LEFT, MAIN_MODEL, FAILED }

    /** Only a provider that is signed in now offers it. */
    fun offered(signedIn: Boolean?): Boolean = signedIn == true

    /** The saved main model runs on [name]: the server refuses, so the note shows instead of the action. */
    fun isMain(name: String, mainProvider: String): Boolean = mainProvider.isNotEmpty() && mainProvider == name

    /** The provider list should be loaded again (the sign-in is gone). */
    fun done(o: SignOutRules.Outcome): Boolean = o != Outcome.FAILED && o != Outcome.MAIN_MODEL

    fun outcome(result: SignOutResult?, error: ApiError?): Outcome = when {
        error != null && error.status == 409 -> Outcome.MAIN_MODEL
        error != null && error.status == 404 -> Outcome.DONE          // already signed out somewhere else: nothing left to remove
        error != null -> Outcome.FAILED
        result?.stillSignedIn == true -> Outcome.DONE_ENV_LEFT
        result?.fallbackCleared == true -> Outcome.DONE_FALLBACK_CLEARED
        else -> Outcome.DONE
    }
}

