package chat.kelo.app.my

import chat.kelo.app.agent.ServerNotification
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import chat.kelo.app.notify.NoteTypes
import chat.kelo.app.notify.NotifyRules
import chat.kelo.app.notify.OpenTarget
import chat.kelo.app.notify.PushData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The storage warning, the cleanup screen's choices and the storage notification (shared with the desktop). */
class StorageRulesTest {
    private val list = KeloApi.json.decodeFromString(
        CleanupList.serializer(),
        """{"groups":[
            {"id":"backups","name":"Older backups","bytes":3000,"count":1,"items":[{"id":"backups:update/b1","name":"0.1.4","bytes":3000,"date":1.7e9,"kind":"update"}]},
            {"id":"uploads","name":"Old upload originals","bytes":8000.0,"count":2,"items":[
              {"id":"uploads:a.pdf","name":"a.pdf","bytes":5000,"date":1700000000.5,"keeps_text":true},
              {"id":"uploads:b.jpg","name":"b.jpg","bytes":3000,"date":1700000000}]},
            {"id":"images","name":"Generated images","bytes":0,"count":0,"items":[]},
            {"id":"future_kind","name":"Something new","bytes":10,"count":1,"items":[{"id":"future_kind:x","name":"x","bytes":10,"date":0}]},
            {"id":"results","name":"Old results and trash","bytes":600,"count":1,"items":[{"id":"results:trash/9","name":"gone.txt","bytes":600,"date":1.7e9,"trashed":true}]}
        ],"total":11610,"older_than_days":14}""",
    )

    @Test fun `the alert maps to a state with the server's own disk worst`() {
        assertEquals(StorageState.OK, StorageRules.state(null))
        assertEquals(StorageState.OK, StorageRules.state(StorageAlert()))
        assertEquals(StorageState.WARN, StorageRules.state(StorageAlert(level = "warn", percent = 82)))
        assertEquals(StorageState.CRITICAL, StorageRules.state(StorageAlert(level = "critical", percent = 96)))
        assertEquals(StorageState.HOST_LOW, StorageRules.state(StorageAlert(level = "warn", hostLow = true)))
        assertEquals(StorageState.HOST_LOW, StorageRules.state(StorageAlert(level = "ok", hostLow = true)))
        assertTrue(StorageRules.urgent(StorageState.CRITICAL) && StorageRules.urgent(StorageState.HOST_LOW))
        assertFalse(StorageRules.urgent(StorageState.WARN) || StorageRules.urgent(StorageState.OK))
    }

    @Test fun `the state survives the overview cache as a short key`() {
        for (s in StorageState.entries) assertEquals(s, StorageRules.fromKey(StorageRules.key(s)))
        assertNull(StorageRules.key(StorageState.OK))
        assertEquals("host_low", StorageRules.key(StorageState.HOST_LOW))
        assertEquals(StorageState.OK, StorageRules.fromKey("something else"))
    }

    @Test fun `the resources answer reads the alert and an older server's answer reads as nothing to warn about`() {
        val now = KeloApi.json.decodeFromString(
            Resources.serializer(),
            """{"disk":{"used":900,"limit":1000,"parts":{"uploads":600}},"alert":{"level":"critical","percent":96,"host_low":false,"top":"uploads"},"can_clean":true}""",
        )
        assertEquals(StorageState.CRITICAL, StorageRules.state(now.alert))
        assertEquals("uploads", now.alert.top)
        assertTrue(now.canClean)
        val older = KeloApi.json.decodeFromString(Resources.serializer(), """{"disk":{"used":900,"limit":1000}}""")
        assertEquals(StorageState.OK, StorageRules.state(older.alert))
        assertFalse(older.canClean)
    }

    @Test fun `groups show in a fixed order, empty ones hidden, unknown ones last`() {
        assertEquals(listOf("uploads", "results", "backups", "future_kind"), StorageRules.shown(list).map { it.id })
        assertEquals(8000L, list.groups.first { it.id == "uploads" }.bytes)                    // a float size still reads
        assertTrue(list.groups.first { it.id == "uploads" }.items.first().keepsText)
        assertTrue(StorageRules.shown(null).isEmpty())
    }

    @Test fun `ticking items and whole groups, and the size of what is chosen`() {
        val uploads = list.groups.first { it.id == "uploads" }
        var sel = emptySet<String>()
        sel = StorageRules.toggle(sel, "uploads:a.pdf")
        assertFalse(StorageRules.groupChecked(sel, uploads))
        assertEquals(5000L, StorageRules.selectedBytes(list, sel))
        sel = StorageRules.toggleGroup(sel, uploads)                // not all chosen yet: chooses the rest
        assertTrue(StorageRules.groupChecked(sel, uploads))
        assertEquals(8000L, StorageRules.selectedBytes(list, sel))
        sel = StorageRules.toggleGroup(sel, uploads)                // all chosen: clears the group
        assertTrue(sel.isEmpty())
        sel = StorageRules.toggle(StorageRules.toggle(sel, "backups:update/b1"), "backups:update/b1")
        assertTrue(sel.isEmpty())
        assertFalse(StorageRules.groupChecked(sel, list.groups.first { it.id == "images" }))   // an empty group is never "all chosen"
    }

    @Test fun `choices that are no longer on a fresh list are dropped`() {
        val fresh = list.copy(groups = list.groups.filter { it.id != "uploads" })
        assertEquals(setOf("results:trash/9"), StorageRules.keep(setOf("uploads:a.pdf", "results:trash/9"), fresh))
    }

    @Test fun `the delete request always says it was confirmed and names the items`() {
        val body = StorageRules.body(listOf("uploads:a.pdf", "results:trash/9")).toString()
        assertEquals("""{"items":["uploads:a.pdf","results:trash/9"],"confirm":true}""", body)
    }

    @Test fun `the result of a delete reads`() {
        val r = KeloApi.json.decodeFromString(
            CleanupResult.serializer(),
            """{"removed":["uploads:a.pdf"],"skipped":["database:assistant.db"],"freed":5000,"groups":[],"total":0}""",
        )
        assertEquals(listOf("uploads:a.pdf"), r.removed)
        assertEquals(5000L, r.freed)
    }

    @Test fun `a storage notification opens the resources page and offers the cleanup screen`() {
        val n = ServerNotification(id = 7, type = NoteTypes.STORAGE, title = "Kelo storage is 82% full", body = "820MB of 1GB used.")
        assertEquals(OpenTarget.Storage, NotifyRules.target(n))
        assertEquals(listOf(OpenTarget.StorageCleanup), NotifyRules.actions(NoteTypes.STORAGE))
        assertTrue(NotifyRules.actions(NoteTypes.TASK_DONE).isEmpty())
        assertEquals(OpenTarget.Storage, OpenTarget.decode(OpenTarget.Storage.encode()))
        assertEquals(OpenTarget.StorageCleanup, OpenTarget.decode(OpenTarget.StorageCleanup.encode()))
        assertTrue(NotifyRules.fresh(listOf(n), 0).isNotEmpty())                 // a known type: posted
        assertEquals(NoteTypes.STORAGE, PushData.parse(mapOf("type" to "storage", "id" to "7"))?.type)
    }

    @Test fun `a full disk is not retried as if it were a passing server error`() {
        val full = ApiError(507, "The Kelo server's disk is full")
        assertFalse(full.isTransient)
        assertTrue(ApiError(503, "busy").isTransient)
    }
}
