package chat.kelo.app.connectors

import chat.kelo.app.core.KeloApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** GET /api/caldav: the CalDAV calendars added here with their own address (iCloud, Yahoo and Naver come with their mail). */
@Serializable
data class CalDavAccount(val name: String = "", val address: String = "", val url: String = "")

@Serializable
data class CalDavAccounts(val accounts: List<CalDavAccount> = emptyList())

object CalDavCalls {
    suspend fun accounts(api: KeloApi): List<CalDavAccount> = api.get<CalDavAccounts>("/api/caldav").accounts

    /** The server signs in once to check the address, user name and app password; only then is anything saved. */
    suspend fun add(api: KeloApi, url: String, username: String, password: String) {
        api.post<JsonElement>("/api/caldav/accounts", buildJsonObject { put("url", url); put("username", username); put("password", password) })
    }
}
