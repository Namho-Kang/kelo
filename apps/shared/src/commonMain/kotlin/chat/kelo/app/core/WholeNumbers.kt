package chat.kelo.app.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToLong

/**
 * A whole number (a size in bytes, a count) that may come as 536870912, 536870912.0 or "536870912". Python servers sent some sizes
 * as floats (GET /api/resources minimum/recommended until 2026-11), and a plain Long field cannot read "536870912.0": the whole
 * answer failed to parse and the page stayed blank. A fraction is rounded to the nearest whole number. Written as a plain integer.
 */
typealias WholeLong = @Serializable(with = WholeLongSerializer::class) Long

object WholeLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("chat.kelo.app.core.WholeLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long {
        if (decoder !is JsonDecoder) return decoder.decodeLong()
        val p = decoder.decodeJsonElement() as? JsonPrimitive ?: throw SerializationException("expected a number")
        return wholeOrNull(p) ?: throw SerializationException("expected a number, got ${p.content}")
    }

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)

    /** [p] as a whole number: an integer as it is, a finite float rounded; null for anything else (text, true, NaN). */
    fun wholeOrNull(p: JsonPrimitive): Long? {
        p.longOrNull?.let { return it }
        val d = p.doubleOrNull ?: return null
        return if (d.isFinite() && d >= Long.MIN_VALUE.toDouble() && d <= Long.MAX_VALUE.toDouble()) d.roundToLong() else null
    }
}
