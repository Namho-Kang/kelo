package chat.kelo.app.my

import chat.kelo.app.core.KeloApi
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The storage warning and the cleanup screen's choices, without any UI (tested on their own).
 *
 * Warning states, worst first: [StorageState.HOST_LOW] (the Kelo server's own disk is almost full: chat stops when it fills),
 * [StorageState.CRITICAL] (95% of the storage cap), [StorageState.WARN] (80%), [StorageState.OK].
 */
enum class StorageState { OK, WARN, CRITICAL, HOST_LOW }

object StorageRules {
    /** The group ids the server sends, in the order they are shown. */
    val GROUPS = listOf("uploads", "images", "browser_history", "results", "backups")

    fun state(alert: StorageAlert?): StorageState = when {
        alert == null -> StorageState.OK
        alert.hostLow -> StorageState.HOST_LOW
        alert.level == "critical" -> StorageState.CRITICAL
        alert.level == "warn" -> StorageState.WARN
        else -> StorageState.OK
    }

    /** As kept in the My overview (a short string survives the encrypted cache as it is); null = nothing to say. */
    fun key(state: StorageState): String? = if (state == StorageState.OK) null else state.name.lowercase()

    fun fromKey(key: String?): StorageState = StorageState.entries.firstOrNull { it.name.lowercase() == key } ?: StorageState.OK

    /** Red (the error colour) for the states where something stops soon; the warning colour for 80%. */
    fun urgent(state: StorageState): Boolean = state == StorageState.CRITICAL || state == StorageState.HOST_LOW

    /** Groups with something in them, known ones in [GROUPS] order, then any newer kind the server sends. */
    fun shown(list: CleanupList?): List<CleanupGroup> {
        val groups = list?.groups.orEmpty().filter { it.items.isNotEmpty() }
        return groups.sortedBy { g -> GROUPS.indexOf(g.id).let { if (it < 0) GROUPS.size else it } }
    }

    fun toggle(selected: Set<String>, id: String): Set<String> = if (id in selected) selected - id else selected + id

    /** Every item of the group, or none when all of them were already chosen. */
    fun toggleGroup(selected: Set<String>, group: CleanupGroup): Set<String> {
        val ids = group.items.map { it.id }.toSet()
        return if (selected.containsAll(ids)) selected - ids else selected + ids
    }

    fun groupChecked(selected: Set<String>, group: CleanupGroup): Boolean =
        group.items.isNotEmpty() && group.items.all { it.id in selected }

    fun selectedBytes(list: CleanupList?, selected: Set<String>): Long =
        list?.groups.orEmpty().flatMap { it.items }.filter { it.id in selected }.sumOf { it.bytes }

    /** The choices still on a fresh list (an item that is gone can no longer be chosen). */
    fun keep(selected: Set<String>, list: CleanupList?): Set<String> {
        val ids = list?.groups.orEmpty().flatMap { g -> g.items.map { it.id } }.toSet()
        return selected.filterTo(mutableSetOf()) { it in ids }
    }

    fun body(selected: Collection<String>) = buildJsonObject {
        put("items", kotlinx.serialization.json.JsonArray(selected.map { JsonPrimitive(it) }))
        put("confirm", true)
    }

    suspend fun list(api: KeloApi): CleanupList = api.get("/api/storage/cleanup")

    /** Only after the user confirmed: the server removes only ids on its own current list. */
    suspend fun remove(api: KeloApi, selected: Collection<String>): CleanupResult = api.post("/api/storage/cleanup", body(selected))
}
