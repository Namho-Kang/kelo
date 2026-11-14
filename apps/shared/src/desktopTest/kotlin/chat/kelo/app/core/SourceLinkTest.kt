package chat.kelo.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The same vectors as apps/ios/KeloCore/Tests/KeloCoreTests/Core/SourceLinkTests.swift. */
class SourceLinkTest {
    private val sha = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678"

    @Test fun aCleanFullShaGivesTheUrlAndTheShortHash() {
        assertEquals("https://github.com/kelo-chat/kelo/tree/$sha", SourceLink.url(sha, dirty = false))
        assertEquals("a1b2c3d", SourceLink.label(sha, dirty = false, modified = "modified"))
    }

    @Test fun aDirtyBuildHasNoUrlAndSaysModified() {
        assertNull(SourceLink.url(sha, dirty = true))
        assertEquals("a1b2c3d (modified)", SourceLink.label(sha, dirty = true, modified = "modified"))
        assertEquals("a1b2c3d (수정됨)", SourceLink.label(sha, dirty = true, modified = "수정됨"))
    }

    @Test fun anEmptyShortUppercaseOrNonHexValueGivesNothing() {
        val bad = listOf("", "a1b2c3d", sha.dropLast(1), sha + "0", sha.uppercase(), "g".repeat(40), " " + sha.dropLast(1), "\$(KELO_SOURCE_COMMIT)")
        for (b in bad) {
            assertNull(SourceLink.url(b, dirty = false), b)
            assertNull(SourceLink.url(b, dirty = true), b)
            assertNull(SourceLink.short(b), b)
            assertNull(SourceLink.label(b, dirty = false, modified = "modified"), b)
            assertNull(SourceLink.label(b, dirty = true, modified = "modified"), b)
        }
    }
}
