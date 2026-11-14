package chat.kelo.app.upload

import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** A part-upload the server opened: [partSize] is the size of every part but the last. */
class PartUpload(val id: String, val partSize: Long, val parts: Int)

/** The server's part-upload routes (src/assistant/api/files.py), one call each. No retrying and no planning here. */
class PartCalls(private val api: KeloApi) {
    /** POST /api/uploads/{name}/parts: 413 photo too large, 507 no room, 409 three part-uploads open, 422 refused (all as [ApiError]). */
    suspend fun start(name: String, size: Long): PartUpload {
        val a = api.request("POST", "/api/uploads/${KeloApi.segment(name)}/parts", buildJsonObject { put("size", size) }) as? JsonObject
            ?: throw ApiError(ApiError.NO_RESPONSE, "unreadable answer")
        fun text(k: String) = (a[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val id = text("upload_id") ?: throw ApiError(ApiError.NO_RESPONSE, "unreadable answer")
        val part = (a["part_size"] as? JsonPrimitive)?.long ?: throw ApiError(ApiError.NO_RESPONSE, "unreadable answer")
        val parts = (a["parts"] as? JsonPrimitive)?.int ?: throw ApiError(ApiError.NO_RESPONSE, "unreadable answer")
        return PartUpload(id, part, parts)
    }

    /** PUT /api/uploads/parts/{id}/{index}: [body] is exactly this part. 422 wrong size, 404 unknown id, 507 no room. */
    suspend fun put(id: String, index: Int, body: PartBody) {
        api.send(api.requestBuilder("/api/uploads/parts/${KeloApi.segment(id)}/$index").put(body).build())
    }

    /** GET /api/uploads/parts/{id}: the indices the server holds. */
    suspend fun received(id: String): Set<Int> {
        val a = api.request("GET", "/api/uploads/parts/${KeloApi.segment(id)}") as? JsonObject
        return (a?.get("received") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }?.toSet().orEmpty()
    }

    /** POST …/finish: the stored path. 422 when a part is missing (the upload stays open). */
    suspend fun finish(id: String): String {
        val a = api.request("POST", "/api/uploads/parts/${KeloApi.segment(id)}/finish") as? JsonObject
        return (a?.get("path") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    }

    /** DELETE …: cancels the part-upload. */
    suspend fun cancel(id: String) {
        api.request("DELETE", "/api/uploads/parts/${KeloApi.segment(id)}")
    }
}
