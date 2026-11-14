package chat.kelo.app.platform

import chat.kelo.app.core.KeloApi
import kotlinx.serialization.json.JsonObject

/** How asking for the phone's location came to. */
enum class LocationAccess { GRANTED, DENIED, BLOCKED }

/** The phone's own location, for the briefing's weather and place. Null in [chat.kelo.app.AppServices] = this device has none. */
interface PhoneLocation {
    /** The phone's own location switch (for every app) is on. When it is off, no app permission helps: the phone's location settings do. */
    fun servicesOn(): Boolean

    fun granted(): Boolean

    /** The system will not ask again (asked before, and no more questions are shown): only the settings page can allow it. */
    fun blocked(): Boolean

    /** Asks the system (once; [LocationAccess.BLOCKED] = it will not ask again, only the settings page can allow it). */
    suspend fun request(): LocationAccess

    /** Sends the phone's place to the server now ([force]) or when due. */
    suspend fun send(api: KeloApi, force: Boolean)

    /** The phone's place now (position, accuracy, town, country, neighbourhood), or null when it cannot be read; the chat's location card answers with it. */
    suspend fun read(): JsonObject?
}
