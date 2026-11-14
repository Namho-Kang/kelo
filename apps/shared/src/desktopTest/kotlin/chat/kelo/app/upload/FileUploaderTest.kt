package chat.kelo.app.upload

import chat.kelo.app.chat.AttachmentTooLarge
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.CRC32
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileUploaderTest {
    private val servers = mutableListOf<FakeUploadServer>()
    private fun server(limits: String? = """{"single_max": 10, "part_size": 10, "image_max": 1000}""") = FakeUploadServer(limits).also { servers += it }
    @AfterTest fun stop() { servers.forEach { it.close() } }

    private fun uploader(s: FakeUploadServer, tempDir: File? = null): FileUploader {
        val api = KeloApi(s.url(), "tok")
        return FileUploader(api, UploadLimitsSource(api), backoffMs = listOf(0, 0, 0), tempDir = tempDir)
    }

    private fun bytes(n: Int) = ByteArray(n) { (it * 7 + 3).toByte() }
    private fun streamOf(b: ByteArray): () -> InputStream = { ByteArrayInputStream(b) }

    @Test fun aSmallFileGoesInOneRequest() = runBlocking {
        val s = server()
        val path = uploader(s).upload("a.pdf", 5, "application/pdf", streamOf(bytes(5)))
        assertEquals("uploads/a.pdf", path)
        assertEquals(listOf(5L), s.singles)
        assertEquals(0, s.count("POST"))
    }

    @Test fun noLimitOnThisRouteMeansOneRequestWhateverTheSize() = runBlocking {
        val s = server("""{"single_max": null, "part_size": 10, "image_max": 1000}""")
        uploader(s).upload("big.mp4", 500, "video/mp4", streamOf(bytes(500)))
        assertEquals(listOf(500L), s.singles)
    }

    @Test fun partsGoInOrderWithTheExactSizesThenFinish() = runBlocking {
        val s = server()
        val data = bytes(25)
        val seen = mutableListOf<Pair<Long, Long>>()
        val path = uploader(s).upload("v.mp4", 25, "video/mp4", streamOf(data)) { sent, total -> seen += sent to total }
        assertEquals("uploads/x", path)
        assertEquals(
            listOf("GET /api/uploads/limits", "POST /api/uploads/v.mp4/parts", "PUT /api/uploads/parts/u1/0", "PUT /api/uploads/parts/u1/1", "PUT /api/uploads/parts/u1/2", "POST /api/uploads/parts/u1/finish"),
            s.log.toList(),
        )
        assertEquals(listOf(10L, 10L, 5L), (0..2).map { s.parts.getValue(it).first })
        assertContentEquals(data, s.joined())
        assertEquals(0L to 25L, seen.first())
        assertEquals(25L to 25L, seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> b.first >= a.first })
    }

    @Test fun aPartThatFailsTwiceIsSentAgainAndTheFileStillArrivesWhole() = runBlocking {
        val s = server()
        s.putFault = { i, nth -> if (i == 1 && nth <= 2) FakeUploadServer.Fault.Status(503) else null }
        val data = bytes(25)
        uploader(s).upload("v.mp4", 25, "video/mp4", streamOf(data))
        assertEquals(3, s.count("PUT /api/uploads/parts/u1/1"))
        assertContentEquals(data, s.joined())
        assertEquals(0, s.count("DELETE"))
    }

    @Test fun aPartThatKeepsFailingGivesUpAfterThreeNewTriesAndCancelsTheUpload() = runBlocking {
        val s = server()
        s.putFault = { i, _ -> if (i == 1) FakeUploadServer.Fault.Status(500, "broken") else null }
        val e = assertFailsWith<ApiError> { uploader(s).upload("v.mp4", 25, "video/mp4", streamOf(bytes(25))) }
        assertEquals(500, e.status)
        assertEquals(4, s.count("PUT /api/uploads/parts/u1/1"))
        assertEquals(1, s.count("DELETE /api/uploads/parts/u1"))
    }

    @Test fun aWrongSizeAnswerIsNeverSentAgain() = runBlocking {
        val s = server()
        s.putFault = { i, _ -> if (i == 0) FakeUploadServer.Fault.Status(422, "wrong size") else null }
        val e = assertFailsWith<ApiError> { uploader(s).upload("v.mp4", 25, "video/mp4", streamOf(bytes(25))) }
        assertEquals(422, e.status)
        assertEquals("wrong size", e.detail)
        assertEquals(1, s.count("PUT /api/uploads/parts/u1/0"))
    }

    @Test fun aBrokenConnectionAsksWhatArrivedAndSkipsThePartsTheServerHolds() = runBlocking {
        val s = server()
        // part 1 arrives, but the answer is lost
        s.putFault = { i, nth -> if (i == 1 && nth == 1) FakeUploadServer.Fault.DropAfterReceiving else null }
        val data = bytes(25)
        uploader(s).upload("v.mp4", 25, "video/mp4", streamOf(data))
        assertEquals(1, s.count("PUT /api/uploads/parts/u1/1"))   // not sent a second time
        assertEquals(1, s.count("GET /api/uploads/parts/u1"))
        assertContentEquals(data, s.joined())
    }

    @Test fun aBrokenConnectionBeforeThePartArrivedSendsItAgain() = runBlocking {
        val s = server()
        s.putFault = { i, nth -> if (i == 2 && nth == 1) FakeUploadServer.Fault.DropBeforeReceiving else null }
        val data = bytes(25)
        uploader(s).upload("v.mp4", 25, "video/mp4", streamOf(data))
        assertEquals(2, s.count("PUT /api/uploads/parts/u1/2"))
        assertEquals(1, s.count("GET /api/uploads/parts/u1"))
        assertContentEquals(data, s.joined())
    }

    @Test fun aFinishThatSaysAPartIsMissingGetsItOnceMoreAndThenFinishesAgain() = runBlocking {
        val s = server()
        s.forgetAtFinish = 1
        val data = bytes(25)
        val path = uploader(s).upload("v.mp4", 25, "video/mp4", streamOf(data))
        assertEquals("uploads/x", path)
        assertEquals(2, s.count("PUT /api/uploads/parts/u1/1"))
        assertEquals(1, s.count("PUT /api/uploads/parts/u1/0"))
        assertEquals(2, s.count("POST /api/uploads/parts/u1/finish"))
        assertContentEquals(data, s.joined())
    }

    @Test fun finishItselfIsTriedAgainOnAServerError() = runBlocking {
        val s = server()
        s.finishFaults = 1
        assertEquals("uploads/x", uploader(s).upload("v.mp4", 25, "video/mp4", streamOf(bytes(25))))
        assertEquals(2, s.count("POST /api/uploads/parts/u1/finish"))
    }

    @Test fun cancellingDeletesThePartUpload() = runBlocking {
        val s = server()
        s.stallPart = 1   // part 1 never gets an answer
        val job = launch(Dispatchers.IO) { uploader(s).upload("v.mp4", 25, "video/mp4", streamOf(bytes(25))) }
        val until = System.currentTimeMillis() + 10_000
        while (s.count("PUT /api/uploads/parts/u1/1") == 0 && System.currentTimeMillis() < until) Thread.sleep(20)
        job.cancelAndJoin()
        assertEquals(1, s.count("DELETE /api/uploads/parts/u1"))
    }

    @Test fun noRoomOnTheServerShowsTheServersOwnWords() = runBlocking {
        val s = server()
        s.startAnswer = okhttp3.mockwebserver.MockResponse().setResponseCode(507).setHeader("Content-Type", "application/json")
            .setBody("""{"detail":"There is not enough room on the server for this file."}""")
        val e = assertFailsWith<ApiError> { uploader(s).upload("v.mp4", 25, "video/mp4", streamOf(bytes(25))) }
        assertEquals(507, e.status)
        assertEquals("There is not enough room on the server for this file.", e.detail)
        assertEquals(0, s.count("PUT"))
    }

    @Test fun noRoomInTheMiddleIsNotTriedAgain() = runBlocking {
        val s = server()
        s.putFault = { i, _ -> if (i == 1) FakeUploadServer.Fault.Status(507, "Storage is full") else null }
        val e = assertFailsWith<ApiError> { uploader(s).upload("v.mp4", 25, "video/mp4", streamOf(bytes(25))) }
        assertEquals("Storage is full", e.detail)
        assertEquals(1, s.count("PUT /api/uploads/parts/u1/1"))
        assertEquals(1, s.count("DELETE"))
    }

    @Test fun tooManyOpenLargeUploadsShowsTheServersWords() = runBlocking {
        val s = server()
        s.startAnswer = okhttp3.mockwebserver.MockResponse().setResponseCode(409).setHeader("Content-Type", "application/json")
            .setBody("""{"detail":"Three large uploads are open. Wait for one to finish."}""")
        val e = assertFailsWith<ApiError> { uploader(s).upload("v.mp4", 25, "video/mp4", streamOf(bytes(25))) }
        assertEquals(409, e.status)
        assertEquals("Three large uploads are open. Wait for one to finish.", e.detail)
    }

    @Test fun anOldServerRefusesAFileOverItsOneRequestLimitOnThePhone() = runBlocking {
        val s = server(limits = null)   // 404
        val e = assertFailsWith<AttachmentTooLarge> { uploader(s).upload("big.mp4", 150_000_000, "video/mp4", { error("must not be read") }) }
        assertEquals(99_000_000L, e.limit)
        assertEquals(listOf("GET /api/uploads/limits"), s.log.toList())
    }

    @Test fun aPhotoOverTheImageLimitIsRefusedBeforeAnythingIsSent() = runBlocking {
        val s = server()
        val e = assertFailsWith<AttachmentTooLarge> { uploader(s).upload("p.jpg", 1001, "image/jpeg", { error("must not be read") }) }
        assertEquals(1000L, e.limit)
        assertEquals(1, s.log.size)
    }

    @Test fun aFileOfUnknownSizeIsCopiedToATempFileForItsSizeAndTheCopyIsDeleted() = runBlocking {
        val s = server()
        val dir = createTempDir("kelo-spool-test")
        try {
            val data = bytes(25)
            val seenDuringUpload = mutableListOf<Int>()
            val path = uploader(s, dir).upload("v.mp4", -1, "video/mp4", streamOf(data)) { _, _ -> seenDuringUpload += dir.listFiles().orEmpty().size }
            assertEquals("uploads/x", path)
            assertContentEquals(data, s.joined())
            assertEquals(25L, s.started)
            assertTrue(seenDuringUpload.isNotEmpty() && seenDuringUpload.all { it == 1 }, "the copy exists while it goes up")
            assertEquals(0, dir.listFiles().orEmpty().size, "and is gone afterwards")
        } finally { dir.deleteRecursively() }
    }

    @Test fun aFileOfUnknownSizeThatFailsAlsoLeavesNoCopy() = runBlocking {
        val s = server()
        s.startAnswer = okhttp3.mockwebserver.MockResponse().setResponseCode(409).setBody("""{"detail":"busy"}""")
        val dir = createTempDir("kelo-spool-test")
        try {
            assertFailsWith<ApiError> { uploader(s, dir).upload("v.mp4", -1, "video/mp4", streamOf(bytes(25))) }
            assertEquals(0, dir.listFiles().orEmpty().size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun aFileOfUnknownSizeOnAnOldServerIsRefusedOnceItIsKnownToBeOver() = runBlocking {
        val s = server(limits = null)
        val dir = createTempDir("kelo-spool-test")
        try {
            val big = GeneratedStream(150_000_000L)
            val e = assertFailsWith<AttachmentTooLarge> { uploader(s, dir).upload("v.mp4", -1, "video/mp4", { big.open() }) }
            assertEquals(99_000_000L, e.limit)
            assertTrue(big.served <= 99_000_000L + 1_048_576, "stopped soon after the limit, not after 150 MB (${big.served})")
            assertEquals(0, dir.listFiles().orEmpty().size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun aSmallFileOfUnknownSizeOnAnOldServerStillGoesUp() = runBlocking {
        val s = server(limits = null)
        val path = uploader(s).upload("a.pdf", -1, "application/pdf", streamOf(bytes(7)))
        assertEquals("uploads/a.pdf", path)
        assertEquals(listOf(7L), s.singles)
    }

    @Test fun aBigFileIsNeverHeldInMemoryWhileItGoesUpInParts() = runBlocking {
        val total = 50_000_000L
        val s = server("""{"single_max": 5000000, "part_size": 10000000, "image_max": 15000000}""")
        s.partSize = 10_000_000L
        s.keepBytes = false
        val g = GeneratedStream(total)
        val path = uploader(s).upload("movie.mp4", total, "video/mp4", { g.open() })
        assertEquals("uploads/x", path)
        assertEquals(5, g.opened, "one fresh stream per part")
        assertTrue(g.biggestRead <= 1_048_576, "no read asked for more than a chunk (${g.biggestRead})")
        // every part arrived with the bytes of its place in the generated file
        for (i in 0 until 5) {
            val crc = CRC32()
            val buf = ByteArray(1 shl 16)
            val from = i * 10_000_000L
            var at = from
            while (at < from + 10_000_000L) { val n = minOf(buf.size.toLong(), from + 10_000_000L - at).toInt(); GeneratedStream.fill(buf, 0, n, at); crc.update(buf, 0, n); at += n }
            assertEquals(10_000_000L to crc.value, s.parts.getValue(i), "part $i")
        }
    }

    @Test fun aFileThatEndsBeforeItsSizeFailsAtOnceWithoutRetrying() = runBlocking {
        val s = server()
        var opened = 0
        assertFailsWith<java.io.IOException> { uploader(s).upload("v.mp4", 25, "video/mp4", { opened++; ByteArrayInputStream(bytes(12)) }) }
        assertEquals(2, opened, "part 0 and the part that ran out: the file is not opened a third time")
        assertEquals(1, s.count("PUT /api/uploads/parts/u1/0"))
        assertEquals(1, s.count("DELETE"))
    }

    /** A file of [size] bytes that exists only as a pattern: a read makes the bytes, skip is a seek, nothing is kept. */
    class GeneratedStream(private val size: Long) {
        @Volatile var opened = 0
        @Volatile var biggestRead = 0
        @Volatile var served = 0L
        fun open(): InputStream {
            opened++
            return object : InputStream() {
                var at = 0L
                override fun read(): Int = if (at >= size) -1 else (fillOne(at++)).toInt() and 0xff
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    biggestRead = maxOf(biggestRead, len)
                    if (at >= size) return -1
                    val n = minOf(len.toLong(), size - at).toInt()
                    fill(b, off, n, at)
                    at += n
                    served += n
                    return n
                }
                override fun skip(n: Long): Long { val k = minOf(n, size - at); at += k; return k }
            }
        }
        companion object {
            private fun fillOne(pos: Long): Byte = ((pos * 31 + (pos shr 8)) and 0xff).toByte()
            fun fill(b: ByteArray, off: Int, n: Int, from: Long) { for (i in 0 until n) b[off + i] = fillOne(from + i) }
        }
    }
}
