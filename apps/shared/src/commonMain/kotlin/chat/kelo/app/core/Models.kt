package chat.kelo.app.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GET/PUT /api/profile. */
@Serializable
data class Profile(
    @SerialName("user_name") val userName: String = "",
    @SerialName("agent_name") val agentName: String = "",
    val provider: String = "",
    val model: String = "",
    @SerialName("fallback_provider") val fallbackProvider: String = "",
    @SerialName("fallback_model") val fallbackModel: String = "",
    /** The model for work the agent does on its own (look-back, memory, journal, ...); both empty = the main model */
    @SerialName("background_provider") val backgroundProvider: String = "",
    @SerialName("background_model") val backgroundModel: String = "",
    val effort: String = "",
    /** How the agent talks to the user: "friendly" or "polite" (an older server says nothing: polite, as before). See my/ToneRules. */
    val tone: String = "polite",
    /** The agent's picture, 0 = none (GET /api/agent/avatar) */
    @SerialName("avatar_version") val avatarVersion: Long = 0,
    /** A main model is chosen (in the app, or as the server's default). Null from a server too old to say: then nothing is asked. */
    @SerialName("model_ready") val modelReady: Boolean? = null,
    /** The oldest app build the server works with, per platform ("android": a versionCode); empty = none (older servers say nothing) */
    @SerialName("min_app_version") val minAppVersion: Map<String, Long> = emptyMap(),
)

/** GET /api/goals items. status is "tracking" or "done". */
@Serializable
data class Goal(
    val id: Long,
    val title: String,
    val category: String = "",
    val note: String = "",
    val status: String = STATUS_TRACKING,
    /** user | suggestion | agent: how the goal came to be */
    val origin: String = "user",
    /** The plan's steps: how many are done of how many (0/0: no plan yet) */
    val plan: GoalPlan = GoalPlan(),
    /** The next open step's words, null without one */
    @SerialName("next_step") val nextStep: String? = null,
    /** The agent asked the user something for this goal and waits for the answer (the server says false while it works on it) */
    val asking: Boolean = false,
    /** A reply or job on this goal is running now (an older server never says so) */
    val working: Boolean = false,
    /** Linked schedules that are on: deleting the goal first asks whether to turn them off too (0, or an older server: no question) */
    @SerialName("schedules_on") val schedulesOn: Int = 0,
) {
    val isDone: Boolean get() = status == STATUS_DONE

    companion object {
        const val STATUS_TRACKING = "tracking"
        const val STATUS_DONE = "done"
    }
}

@Serializable
data class GoalPlan(val done: Int = 0, val total: Int = 0)

/** POST /api/pair answer. */
@Serializable
data class PairResult(val token: String, @SerialName("device_id") val deviceId: Long? = null)

/** GET /api/devices. */
@Serializable
data class Device(
    val id: Long,
    val name: String = "",
    @SerialName("created_at") val createdAt: Double = 0.0,
    @SerialName("last_seen") val lastSeen: Double? = null,
    /** Marked on the server machine as the owner's own phone (`assistant phone owner`): it may open the operator settings. */
    val owner: Boolean = false,
)

@Serializable
data class DeviceList(val devices: List<Device> = emptyList(), val current: Long? = null)
