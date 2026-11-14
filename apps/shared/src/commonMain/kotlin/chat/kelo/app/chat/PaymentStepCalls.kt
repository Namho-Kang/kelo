package chat.kelo.app.chat

import chat.kelo.app.core.KeloApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** The payment step endpoints (server.py PAYMENT STEPS). */
class PaymentStepCalls(private val api: KeloApi) {
    suspend fun list(): List<PaymentStep> {
        val o = api.request("GET", "/api/payment-steps") as? JsonObject ?: return emptyList()
        return (o["items"] as? JsonArray).orEmpty().mapNotNull { PaymentStep.parse(it) }
    }

    suspend fun answer(stepId: String, answer: String, code: String? = null) {
        api.request("POST", "/api/payment-steps/${KeloApi.segment(stepId)}", buildJsonObject {
            put("answer", answer)
            if (code != null) put("code", code)
        })
    }

    suspend fun pad(stepId: String): ByteArray = api.download("/api/payment-steps/${KeloApi.segment(stepId)}/pad")

    suspend fun tap(stepId: String, x: Float, y: Float): Int {
        val o = api.request("POST", "/api/payment-steps/${KeloApi.segment(stepId)}/pad/tap", buildJsonObject {
            put("x", x.toDouble()); put("y", y.toDouble())
        }) as? JsonObject
        return (o?.get("taps") as? JsonPrimitive)?.intOrNull ?: 0
    }

    suspend fun clear(stepId: String): Int {
        val o = api.request("POST", "/api/payment-steps/${KeloApi.segment(stepId)}/pad/clear") as? JsonObject
        return (o?.get("taps") as? JsonPrimitive)?.intOrNull ?: 0
    }
}
