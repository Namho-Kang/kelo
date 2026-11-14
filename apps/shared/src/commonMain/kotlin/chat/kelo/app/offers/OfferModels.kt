package chat.kelo.app.offers

import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One ready-to-run task Kelo offers (server offers.py): its sentence, what it still needs from the owner, whether it names amounts. */
@Serializable
data class Offer(
    val id: String = "",
    val kind: String = "",
    val sentence: String = "",
    val needs: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: Double = 0.0,
    @SerialName("has_numbers") val hasNumbers: Boolean = false,
    @SerialName("checked_at") val checkedAt: Double? = null,
)

@Serializable
data class OfferList(val items: List<Offer> = emptyList())

@Serializable
data class OfferStarted(@SerialName("turn_id") val turnId: String = "")

/** Why an offer is dismissed; [wire] is the server's word. */
enum class OfferReason(val wire: String) { NOT_INTERESTED("not_interested"), DONE("done_already"), LATER("not_now") }

/** What starting an offer came to. */
sealed interface OfferStart {
    data class Started(val turnId: String) : OfferStart
    /** 409 without a code of its own: a reply is already running */
    data object Busy : OfferStart
    data object Failed : OfferStart
}

object OfferCalls {
    suspend fun list(api: KeloApi): List<Offer> = api.get<OfferList>("/api/offers").items

    suspend fun start(api: KeloApi, id: String): OfferStart = try {
        val r = api.post<OfferStarted>("/api/offers/${KeloApi.segment(id)}/start")
        if (r.turnId.isNotBlank()) OfferStart.Started(r.turnId) else OfferStart.Failed
    } catch (e: ApiError) {
        if (e.status == 409 && e.code.isNullOrBlank()) OfferStart.Busy else OfferStart.Failed
    }

    suspend fun dismiss(api: KeloApi, id: String, reason: OfferReason) {
        api.post<kotlinx.serialization.json.JsonElement>("/api/offers/${KeloApi.segment(id)}/dismiss", buildJsonObject { put("reason", reason.wire) })
    }
}
