package chat.kelo.app.upload

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * What the server takes in one upload on THIS route (GET /api/uploads/limits).
 * [singleMax]: the most one PUT may carry (null: no per-request limit, e.g. Tailscale or a direct own domain);
 * [partSize]: the size of one part of a part-upload (null: the server has no parts); [imageMax]: the most a photo may be.
 * The same table, the same rules and the same test vectors (apps/shared/test-vectors/upload-plan.json) are in the iPhone app (UploadPlan.swift).
 */
data class UploadLimits(val singleMax: Long?, val partSize: Long?, val imageMax: Long) {
    companion object {
        /** An old server (the limits route answers 404): one request up to 99 MB, no parts, photos up to 15 MB: as before parts existed. */
        val LEGACY = UploadLimits(singleMax = 99_000_000L, partSize = null, imageMax = 15_000_000L)

        /** The server's answer, or null when it cannot be read (the caller then uses [LEGACY]). */
        fun parse(answer: JsonElement): UploadLimits? {
            val o = answer as? JsonObject ?: return null
            fun number(key: String): Long? = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
            val image = number("image_max") ?: return null
            if (image <= 0) return null
            // an unreadable or non-positive size is "none", never a division by zero
            return UploadLimits(number("single_max")?.takeIf { it > 0 }, number("part_size")?.takeIf { it > 0 }, image)
        }
    }
}

/** How one file goes up: decided from its size and the route's [UploadLimits], with no I/O (so it is tested with plain numbers). */
sealed interface UploadPlan {
    /** One PUT /api/uploads/{name}. */
    data object Single : UploadPlan

    /** A part-upload: [sizes] are the byte counts of the parts in order (all [partSize] but the last). */
    data class Parts(val partSize: Long, val sizes: List<Long>) : UploadPlan {
        val count: Int get() = sizes.size
    }

    /** Refused here, before anything is sent: more than [limit] bytes. */
    data class TooLarge(val limit: Long) : UploadPlan

    /** The size is not known and the route has a per-request limit with parts: the size must be found out first (a copy to a temp file). */
    data object NeedsSize : UploadPlan

    companion object {
        /** [size] < 0 is "not known". [isImage]: a photo, which has its own limit. */
        fun of(size: Long, isImage: Boolean, limits: UploadLimits): UploadPlan {
            if (isImage && size > limits.imageMax) return TooLarge(limits.imageMax)
            val single = limits.singleMax
            val part = limits.partSize
            if (size < 0) return when {
                single == null -> Single
                part == null -> TooLarge(single)
                else -> NeedsSize
            }
            if (single == null || size <= single) return Single
            if (part == null) return TooLarge(single)
            return Parts(part, partSizes(size, part))
        }

        /** The byte counts of the parts of a [size]-byte file cut in [part]-byte parts: all [part] but the last (the rest). */
        fun partSizes(size: Long, part: Long): List<Long> {
            val count = ((size + part - 1) / part).toInt()
            return List(count) { i -> if (i < count - 1) part else size - part * (count - 1) }
        }
    }
}

/** A size limit in words for a message: 99_000_000 -> "99 MB" (decimal, like the server's own "Files can be up to 100 MB"). */
object UploadSizeText {
    fun of(bytes: Long): String = if (bytes >= 1_000_000_000L && bytes % 1_000_000_000L == 0L) "${bytes / 1_000_000_000L} GB" else "${bytes / 1_000_000L} MB"
}
