package chat.kelo.app.agent

import chat.kelo.app.core.KeloApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPassRulesTest {
    @Test fun `the reflection answer carries the last memory pass and an older server none`() {
        val body = """{"enabled": true, "rules": [], "last_run": null, "guide": "",
            "memory": {"last_pass": {"at": 1.7e9, "status": "done", "code": "", "added": 3, "tidied": 2, "files": ["MEMORY.md", "user.md"],
            "trigger": "nightly", "undone": false, "can_undo": true}, "last_attempt": {"at": 1.7e9, "status": "skipped", "code": "no_news"}}}"""
        val look = KeloApi.json.decodeFromString(Lookback.serializer(), body)
        val pass = look.memory?.lastPass!!
        assertEquals(3, pass.added)
        assertEquals(2, pass.tidied)
        assertTrue(pass.canUndo)
        assertTrue(MemoryPassRules.shown(pass))
        assertNull(KeloApi.json.decodeFromString(Lookback.serializer(), """{"enabled": true, "rules": []}""").memory)
        assertNull(KeloApi.json.decodeFromString(Lookback.serializer(), """{"memory": {"last_pass": null}}""").memory?.lastPass)
    }

    @Test fun `the line shows a pass that changed something or was undone`() {
        assertFalse(MemoryPassRules.shown(null))
        assertFalse(MemoryPassRules.shown(MemoryPass(status = "done")))
        assertFalse(MemoryPassRules.shown(MemoryPass(status = "failed", added = 2)))
        assertTrue(MemoryPassRules.shown(MemoryPass(status = "done", tidied = 1)))
        assertTrue(MemoryPassRules.shown(MemoryPass(status = "done", undone = true)))
    }

    @Test fun `changes are grouped by what they are about, never by file name`() {
        val groups = MemoryPassRules.group(listOf(
            MemoryChange("memory/people/jun.md", added = listOf("Leads the team")),
            MemoryChange("MEMORY.md", added = listOf("Likes tea"), removed = listOf("Old log", "")),
            MemoryChange("user.md", added = listOf("Timezone: Asia/Seoul")),
            MemoryChange("memory/people/ana.md", removed = listOf("Moved away")),
            MemoryChange("AGENTS.md"),
        ))
        assertEquals(listOf(MemoryArea.ABOUT_YOU, MemoryArea.TO_REMEMBER, MemoryArea.PEOPLE), groups.map { it.area })
        assertEquals(listOf("Old log"), groups[1].removed)
        assertEquals(listOf("Leads the team"), groups[2].added)
        assertEquals(listOf("Moved away"), groups[2].removed)
        assertEquals(MemoryArea.WORKING_NOTES, MemoryPassRules.area("AGENTS.md"))
    }
}
