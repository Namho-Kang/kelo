package chat.kelo.app.upload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The plan table every app must give (apps/shared/test-vectors/upload-plan.json; the iPhone app reads the same file). */
class UploadPlanTest {
    private fun vectors(): JsonObject {
        val start = File("").absoluteFile
        val file = generateSequence(start) { it.parentFile }.flatMap { sequenceOf(File(it, "test-vectors/upload-plan.json"), File(it, "shared/test-vectors/upload-plan.json")) }
            .firstOrNull { it.isFile } ?: error("apps/shared/test-vectors/upload-plan.json not found from $start")
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun limitsOf(o: JsonObject) = UploadLimits(
        o["single_max"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.long, o["part_size"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.long, o.getValue("image_max").jsonPrimitive.long,
    )

    @Test fun everyVectorGivesItsPlan() {
        val v = vectors()
        val named = v.getValue("limits").jsonObject.mapValues { limitsOf(it.value.jsonObject) }
        val cases = v.getValue("cases").jsonArray
        assertTrue(cases.size >= 15)
        for (c in cases.map { it.jsonObject }) {
            val name = c.getValue("name").jsonPrimitive.content
            val got = UploadPlan.of(c.getValue("size").jsonPrimitive.long, c.getValue("image").jsonPrimitive.boolean, named.getValue(c.getValue("limits").jsonPrimitive.content))
            val want: UploadPlan = when (val kind = c.getValue("plan").jsonPrimitive.content) {
                "single" -> UploadPlan.Single
                "needs_size" -> UploadPlan.NeedsSize
                "too_large" -> UploadPlan.TooLarge(c.getValue("limit").jsonPrimitive.long)
                "parts" -> UploadPlan.Parts(c.getValue("part_size").jsonPrimitive.long, (c.getValue("sizes") as JsonArray).map { it.jsonPrimitive.long })
                else -> error("unknown plan $kind")
            }
            assertEquals(want, got, name)
        }
    }

    @Test fun partsAddUpToTheFile() {
        for (size in listOf(1L, 99_000_001L, 225_000_000L, 5_000_000_000L)) {
            val p = UploadPlan.of(size, false, UploadLimits(99_000_000, 90_000_000, 15_000_000))
            if (p is UploadPlan.Parts) {
                assertEquals(size, p.sizes.sum())
                assertTrue(p.sizes.dropLast(1).all { it == 90_000_000L } && p.sizes.last() in 1..90_000_000L)
                assertEquals(p.sizes.size, p.count)
            }
        }
    }

    @Test fun theServersAnswerIsRead() {
        assertEquals(UploadLimits(99_000_000, 90_000_000, 15_000_000), UploadLimits.parse(Json.parseToJsonElement("""{"single_max":99000000,"part_size":90000000,"image_max":15000000}""")))
        assertNull(UploadLimits.parse(JsonPrimitive("x")))
        assertNull(UploadLimits.parse(Json.parseToJsonElement("""{"single_max":99000000}""")))
        assertNull(UploadLimits.parse(Json.parseToJsonElement("[1]")))
        assertEquals(UploadLimits(null, null, 15_000_000), UploadLimits.parse(Json.parseToJsonElement("""{"single_max":null,"part_size":0,"image_max":15000000}""")))
    }

    @Test fun aSizeIsWrittenInDecimalMegabytes() {
        assertEquals("15 MB", UploadSizeText.of(15_000_000))
        assertEquals("99 MB", UploadSizeText.of(99_000_000))
        assertEquals("2 GB", UploadSizeText.of(2_000_000_000))
    }
}
