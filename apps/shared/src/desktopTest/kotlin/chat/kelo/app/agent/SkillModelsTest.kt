package chat.kelo.app.agent

import chat.kelo.app.core.KeloApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillModelsTest {
    private val body = """{"items": [
        {"id": "a1", "skill": "renew-library-book", "kind": "new", "description": "Renew a book.", "text": "## When to use\nA loan runs out.\n\n## Steps\n1. Sign in.",
         "diff": [], "reason": "Renew my book", "origin": {"type": "draft", "task_id": 7}, "outside_text": true, "created_at": 1700000100.0,
         "secrets_removed": 1, "something_newer": {"x": 1}},
        {"id": "b2", "skill": "tidy-inbox", "kind": "change", "description": "Tidy.", "text": "x",
         "diff": [{"op": "gap", "text": ""}, {"op": "del", "text": "2. Old."}, {"op": "add", "text": "2. New."}, {"op": "add", "text": "3. More."}, {"op": "same", "text": "4. Same."}],
         "origin": {"type": "chat", "task_id": 3}, "outside_text": false, "created_at": 1700000200.0},
        {"id": "edited-hand-made", "skill": "hand-made", "kind": "edited", "description": "By hand.", "text": "y", "diff": [{"op": "file", "text": "SKILL.md"}],
         "origin": {"type": "disk"}, "outside_text": true, "created_at": null, "content_hash": "abc", "never_approved": true}
      ], "drafting": [{"task_id": 9}, {"worker_id": "w1", "task_id": 4}]}"""

    @Test fun `the proposals parse with unknown fields ignored and missing ones defaulted`() {
        val p = KeloApi.json.decodeFromString(SkillProposals.serializer(), body)
        assertEquals(3, p.items.size)
        val draft = p.items[0]
        assertEquals("draft", draft.origin.type)
        assertEquals(7L, draft.origin.taskId)
        assertTrue(draft.outsideText)
        assertEquals(1, draft.secretsRemoved)
        assertEquals("abc", p.items[2].contentHash)
        assertTrue(p.items[2].neverApproved)
        assertTrue(p.available)
        val bare = KeloApi.json.decodeFromString(SkillProposals.serializer(), """{"items": [{"id": "z"}]}""")
        assertEquals(SkillProposal.NEW, bare.items[0].kind)
        assertEquals(emptyList<SkillDrafting>(), bare.drafting)
    }

    @Test fun `changes made outside the app come first, then the newest proposals, and blank ones are left out`() {
        val p = KeloApi.json.decodeFromString(SkillProposals.serializer(), body)
        assertEquals(listOf("edited-hand-made", "b2", "a1"), SkillRules.ordered(p.items + SkillProposal(id = "", skill = "x")).map { it.id })
        assertEquals(listOf("a1", "edited-hand-made"), SkillRules.without(p, "b2").items.map { it.id })
    }

    @Test fun `a change shows its lines with counts and a new skill its text`() {
        val p = KeloApi.json.decodeFromString(SkillProposals.serializer(), body)
        assertFalse(SkillRules.showsDiff(p.items[0]))
        assertTrue(SkillRules.showsDiff(p.items[1]))
        assertEquals(2 to 1, SkillRules.counts(p.items[1]))
        val long = (1..30).map { "line $it" }
        assertEquals(SkillRules.PREVIEW_LINES, SkillRules.preview(long, all = false).size)
        assertEquals(30, SkillRules.preview(long, all = true).size)
        assertTrue(SkillRules.hasMore(long))
        assertFalse(SkillRules.hasMore(long.take(3)))
        assertEquals(listOf("## When to use", "A loan runs out.", "", "## Steps", "1. Sign in."), SkillRules.textLines(p.items[0].text + "\n\n"))
    }

    @Test fun `save as skill shows only on finished runs and knows when a draft is being written`() {
        val p = KeloApi.json.decodeFromString(SkillProposals.serializer(), body)
        assertTrue(SkillRules.canSave("done"))
        listOf("running", "failed", "stopped", "waiting_approval", "interrupted").forEach { assertFalse(SkillRules.canSave(it)) }
        assertTrue(SkillRules.drafting(p, taskId = 9))
        assertFalse(SkillRules.drafting(p, taskId = 4))      // that one is a job's draft: the job's page shows it
        assertTrue(SkillRules.drafting(p, workerId = "w1"))
        assertFalse(SkillRules.drafting(null, taskId = 9))
        assertEquals("/api/tasks/9/skill-draft", SkillRules.draftPath(taskId = 9))
        assertEquals("/api/workers/w1/skill-draft", SkillRules.draftPath(workerId = "w1"))
        assertEquals("/api/skills/proposals/a1/approve", SkillRules.approvePath(p.items[0]))
        assertEquals("/api/skills/proposals/a1/discard", SkillRules.discardPath(p.items[0]))
    }

    @Test fun `a skill is shown by its name in words, never its raw id`() {
        assertEquals("Renew library book", SkillRules.title("renew-library-book"))
        assertEquals("Check parcel 2", SkillRules.title("check-parcel-2"))
        assertEquals("X", SkillRules.title("x"))
        assertEquals("도서관 책 연장", SkillRules.name(SkillProposal(id = "a", skill = "renew-library-book", name = "도서관 책 연장")))
        assertEquals("Renew library book", SkillRules.name(SkillProposal(id = "a", skill = "renew-library-book", name = " ")))
    }
}
