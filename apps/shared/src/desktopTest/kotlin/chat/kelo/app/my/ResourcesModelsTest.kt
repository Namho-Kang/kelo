package chat.kelo.app.my

import chat.kelo.app.chat.ModelFacts
import chat.kelo.app.core.KeloApi
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * GET /api/resources as servers really send it. Until 2026-11 the server sent minimum/recommended memory as floats (536870912.0);
 * a plain Long field could not read that, the whole answer failed and 마이 → Kelo 서버 자원 stayed blank. Shared with the desktop.
 */
class ResourcesModelsTest {
    /** The answer from the phone that showed the blank page (Android 0.1.402102), as it was sent. */
    private val withFloats = """{"memory":{"used":109551616,"limit":null},"cpus":null,"disk":{"used":1609985,"limit":null,""" +
        """"parts":{"database":765952,"notes":763710,"uploads":42978,"other":37066,"history":279},"free_on_host":16335097856},""" +
        """"host":{"memory":12514037760,"cpus":4,"disk_free":16335097856,"disk_total":83953606656},"profile":"unlimited",""" +
        """"minimum":{"memory":536870912.0,"cpus":0.5,"disk":5637144576},"recommended":{"memory":1073741824.0,"cpus":1.0,"disk":13555990528}}"""

    /** The same answer from a fixed server: every size a whole number. */
    private val withInts = withFloats.replace("536870912.0", "536870912").replace("1073741824.0", "1073741824")

    private fun decode(json: String): Resources = KeloApi.json.decodeFromString(Resources.serializer(), json)

    private fun assertThePhonesNumbers(r: Resources) {
        assertEquals(109551616L, r.memory.used)
        assertNull(r.memory.limit)
        assertNull(r.cpus)
        assertEquals(1609985L, r.disk.used)
        assertNull(r.disk.limit)
        assertEquals(mapOf("database" to 765952L, "notes" to 763710L, "uploads" to 42978L, "other" to 37066L, "history" to 279L), r.disk.parts)
        assertEquals(12514037760L, r.host.memory)
        assertEquals(4.0, r.host.cpus!!, 0.0)
        assertEquals(16335097856L, r.host.diskFree)
        assertEquals("unlimited", r.profile)
        assertEquals(Sizes(memory = 536870912L, cpus = 0.5, disk = 5637144576L), r.minimum)
        assertEquals(Sizes(memory = 1073741824L, cpus = 1.0, disk = 13555990528L), r.recommended)
    }

    @Test fun readsTheAnswerThatLeftThePageBlank() = assertThePhonesNumbers(decode(withFloats))

    @Test fun readsTheSameAnswerWithWholeNumbers() {
        assertThePhonesNumbers(decode(withInts))
        assertEquals(decode(withFloats), decode(withInts))
    }

    @Test fun everySizeMayComeAsAFloatOrAsText() {
        val r = decode(
            """{"memory":{"used":1.0E8,"limit":"2147483648"},"disk":{"used":2048.0,"limit":4096.0,"parts":{"uploads":12.0,"notes":"7"},""" +
                """"free_on_host":1.6335097856E10},"host":{"memory":1.2514037760E10,"disk_free":10.0,"disk_total":20.0},""" +
                """"minimum":{"memory":5.36870912E8,"disk":5.637144576E9},"recommended":{"memory":1.6E9,"disk":1.0E10}}""",
        )
        assertEquals(100_000_000L, r.memory.used)
        assertEquals(2147483648L, r.memory.limit)
        assertEquals(DiskUse(used = 2048, limit = 4096, parts = mapOf("uploads" to 12L, "notes" to 7L)), r.disk)
        assertEquals(HostCapacity(memory = 12514037760L, diskFree = 10), r.host)
        assertEquals(536870912L, r.minimum.memory)
        assertEquals(10_000_000_000L, r.recommended.disk)
        // a fraction of a byte is rounded, not refused
        assertEquals(3L, decode("""{"disk":{"used":2.6}}""").disk.used)
    }

    @Test fun missingAndNullSizesTakeTheDefaults() {
        val r = decode("""{"memory":{},"disk":{"used":null,"parts":{}},"host":{"memory":null}}""")
        assertEquals(MemoryUse(), r.memory)
        assertEquals(0L, r.disk.used)
        assertNull(r.host.memory)
        assertEquals(Sizes(), r.minimum)
    }

    @Test fun writesSizesBackAsWholeNumbers() {
        val text = KeloApi.json.encodeToString(Resources.serializer(), decode(withFloats))
        assertTrue(text, "\"memory\":536870912," in text && "\"memory\":1073741824," in text)
        assertFalse(text, "536870912.0" in text)
    }

    @Test fun anUploadsSizeAndAModelsContextWindowMayBeFloatsToo() {
        val u = KeloApi.json.decodeFromString(Upload.serializer(), """{"path":"uploads/a.pdf","bytes":42978.0}""")
        assertEquals(42978L, u.bytes)
        val m = KeloApi.json.decodeFromString(ModelFacts.serializer(), """{"images":true,"context":1048576.0}""")
        assertEquals(1048576L, m.context)
    }

    @Test fun textThatIsNoNumberStillFails() {
        try {
            decode("""{"disk":{"used":"lots"}}""")
            fail("\"lots\" is no size")
        } catch (_: SerializationException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    // ---- what the page shows ------------------------------------------------------------------------------------------------------

    @Test fun beforeTheFirstAnswerThePageIsLoadingThenShowsTheNumbers() {
        val start = ResourcesLoad()
        assertTrue(start.loading)
        assertFalse(start.showError)
        val shown = start.loaded(decode(withFloats))
        assertFalse(shown.loading)
        assertFalse(shown.showError)
        assertEquals("unlimited", shown.data?.profile)
    }

    @Test fun aFailedFirstReadShowsTheErrorAndTryAgainLoadsAgain() {
        val failed = ResourcesLoad().readFailed()
        assertTrue(failed.showError)
        assertFalse(failed.loading)
        assertNull(failed.data)
        val retrying = failed.retrying()
        assertTrue(retrying.loading)
        assertFalse(retrying.showError)
        assertEquals(decode(withInts), retrying.loaded(decode(withInts)).data)
    }

    @Test fun aFailedRefreshAfterASuccessKeepsTheLastNumbers() {
        val shown = ResourcesLoad().loaded(decode(withFloats))
        val after = shown.readFailed()
        assertSame(shown, after)
        assertFalse(after.showError)
        assertEquals(decode(withFloats), after.data)
    }

    /** The page reads with [KeloApi.json]; an answer it cannot read must end in the error state, not in an empty page. */
    @Test fun anUnreadableAnswerEndsInTheErrorState() {
        val load = runCatching { decode("""{"memory":[]}""") }.fold({ ResourcesLoad().loaded(it) }, { ResourcesLoad().readFailed() })
        assertTrue(load.showError)
    }
}
