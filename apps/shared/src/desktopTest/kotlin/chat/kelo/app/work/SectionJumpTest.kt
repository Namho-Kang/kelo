package chat.kelo.app.work

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A jump to a section waits for that section's answer, and only then can go. */
class SectionJumpTest {
    @Test fun theWatchersJumpWaitsWhileTheyLoadAndThenGoes() {
        // requested while the watchers are still loading: not yet
        assertFalse(SectionJump.ready(SECTION_WATCH, tasks = true, perms = true, schedules = true, watchers = false))
        // the watchers answered (found, or none): the same pending jump can go
        assertTrue(SectionJump.ready(SECTION_WATCH, tasks = true, perms = true, schedules = true, watchers = true))
    }

    @Test fun otherSectionsWaitForTheirOwnLists() {
        assertFalse(SectionJump.ready(SECTION_ASKS, tasks = true, perms = false, schedules = true, watchers = true))
        assertTrue(SectionJump.ready(SECTION_ASKS, tasks = true, perms = true, schedules = false, watchers = false))
        assertFalse(SectionJump.ready(SECTION_UPCOMING, tasks = true, perms = true, schedules = false, watchers = true))
        assertTrue(SectionJump.ready(SECTION_NOW, tasks = true, perms = false, schedules = false, watchers = false))
        assertFalse(SectionJump.ready(SECTION_NOW, tasks = false, perms = true, schedules = true, watchers = true))
    }
}
