package chat.kelo.app.my

import chat.kelo.app.core.KeloApi
import chat.kelo.app.core.Profile
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 마이 → 에이전트 → 말투: how the agent talks to the user, "friendly" (친근, informal forms such as Korean 반말) or "polite" (공손, polite
 * forms such as Korean 해요체; the server's default). The page reloads the profile when it opens; a change the agent made in the chat
 * ("반말로 해 줘" → set_tone) or another device made reaches the profile through GET /api/agent/status ([synced]).
 */
object ToneRules {
    const val FRIENDLY = "friendly"
    const val POLITE = "polite"
    val ALL = listOf(FRIENDLY, POLITE)

    /** The choice shown as selected: anything but "friendly" (empty from an older server, unknown) reads as polite. */
    fun selected(profile: Profile): String = if (profile.tone == FRIENDLY) FRIENDLY else POLITE

    /** The polled status's tone when it differs from the profile on screen: the profile to show; null = nothing to change. */
    fun synced(profile: Profile, polled: String?): Profile? =
        polled?.takeIf { it in ALL && it != selected(profile) }?.let { profile.copy(tone = it) }

    /** The page opened: the server's profile as it is now (the agent may have changed the tone in the chat). */
    suspend fun reload(api: KeloApi): Profile = api.get("/api/profile")

    /** A choice tapped: PUT /api/profile {"tone"}; the saved profile. The next reply already uses it. */
    suspend fun save(api: KeloApi, tone: String): Profile {
        require(tone in ALL) { "unknown tone $tone" }
        return api.put("/api/profile", buildJsonObject { put("tone", tone) })
    }
}
