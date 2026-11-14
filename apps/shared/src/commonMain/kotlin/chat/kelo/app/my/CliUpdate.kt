package chat.kelo.app.my

import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The owner updating the claude / agy CLI in its container from the app (the CLI box in My → Model and the model setup screens).
 * POST /api/providers/{name}/cli/update starts it on the server (the sidecar installs the newer CLI into its own home volume), then
 * GET of the same path is asked every [POLL_MS] until it is done or failed. When it fails the old version keeps working.
 * Plain Kotlin (shared with the desktop app); the screens only show [CliUpdateRules.outcome].
 */
@Serializable
data class CliUpdateStatus(
    /** idle | running | done | failed */
    val state: String = "idle",
    /** checking | installing | verifying | switching (while running) */
    val step: String? = null,
    val from: String? = null,
    val to: String? = null,
    val ok: Boolean? = null,
    /** why it failed, already in the user's language */
    val error: String? = null,
    /** the newest version was already in use: nothing was installed */
    val unchanged: Boolean = false,
    /** "Claude Code" / "Antigravity CLI" */
    val product: String = "",
)

object CliUpdateRules {
    const val POLL_MS = 2_000L
    /** An install is cut off after 10 minutes on the server; the app stops asking a little later. */
    const val GIVE_UP_MS = 12 * 60_000L

    sealed interface Outcome {
        data object Running : Outcome
        data class Updated(val product: String, val version: String) : Outcome
        data class Unchanged(val product: String) : Outcome
        data class Failed(val error: String) : Outcome
    }

    /** The 업데이트 button: a newer CLI is out, this sign-in is the owner's and the CLI runs in a container that can update itself. */
    fun showButton(cli: CliStatus, busy: Boolean): Boolean = cli.installed && cli.updateAvailable && cli.updatable && !busy

    fun finished(st: CliUpdateStatus): Boolean = st.state == "done" || st.state == "failed"

    /**
     * Which CLI gets its own card at the top of 마이 → 모델: the one a newer-CLI notice named ([asked], a provider id), when it is not
     * the [selected] service (that one shows its box in the form already) and the server lists it with a CLI ([withCli]). Null: none.
     */
    fun focus(asked: String?, selected: String, withCli: Collection<String>): String? =
        asked?.takeIf { it.isNotBlank() && it != selected && it in withCli }

    fun outcome(st: CliUpdateStatus, fallbackProduct: String = ""): Outcome {
        val product = st.product.ifEmpty { fallbackProduct }
        return when {
            st.state == "failed" || st.ok == false -> Outcome.Failed(st.error.orEmpty())
            st.state == "done" && st.unchanged -> Outcome.Unchanged(product)
            st.state == "done" -> Outcome.Updated(product, st.to.orEmpty())
            else -> Outcome.Running
        }
    }

    fun path(provider: String): String = "/api/providers/${KeloApi.segment(provider)}/cli/update"

    /** Start the update and follow it to the end. A refusal (409: a chat is using the CLI, another update runs) comes back as Failed. */
    suspend fun run(api: KeloApi, provider: String, now: () -> Long = System::currentTimeMillis,
                    onProgress: (CliUpdateStatus) -> Unit = {}): CliUpdateStatus {
        var st = try {
            api.post<CliUpdateStatus>(path(provider), JsonObject(emptyMap()))
        } catch (e: ApiError) {
            return CliUpdateStatus(state = "failed", ok = false, error = e.detail)
        }
        onProgress(st)
        val until = now() + GIVE_UP_MS
        var misses = 0
        while (!finished(st) && now() < until) {
            delay(POLL_MS)
            st = try {
                api.get<CliUpdateStatus>(path(provider)).also { misses = 0 }
            } catch (e: ApiError) {
                if (!e.isTransient || ++misses > 5) return CliUpdateStatus(state = "failed", ok = false, error = e.detail, product = st.product)
                st
            }
            onProgress(st)
        }
        return st
    }
}
