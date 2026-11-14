package chat.kelo.app.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * One kind of thing a screen keeps between launches: the file it goes in, how it is written, and how it is cut down to size
 * before writing ([trim], e.g. only the newest 200 chat messages).
 */
class CacheSlot<T>(val name: String, val serializer: KSerializer<T>, val trim: (T) -> T = { it }) {
    init {
        require(name.matches(Regex("[a-z0-9_]{1,32}"))) { "bad slot name $name" }
    }
}

/**
 * The last good data of each screen, so a screen can show it at once on the next open (and while the server cannot be reached)
 * and refresh it in the background.
 *
 * - Kept per server: a file is named by a hash of the server address, and the address is also stored inside the encrypted data
 *   and checked on reading, so one server's data never shows for another.
 * - Kept only encrypted, with the same AES-GCM [ValueCipher] as the session (an Android Keystore key on the phone).
 * - A file that cannot be read (corrupt, written with a key that is gone, too large, an older format) counts as empty and is
 *   deleted.
 * - [clear] (signing out) forgets everything, in memory and on disk.
 *
 * Reads are served from memory after the first one (or after [warm]). Writes update memory at once and reach the disk on
 * [writer] (one background thread on the phone, so writes keep their order and never block the screen).
 */
class ScreenCache(
    private val dir: File,
    private val cipher: ValueCipher,
    private val writer: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "kelo-cache").apply { isDaemon = true } },
    private val maxFileBytes: Int = MAX_FILE_BYTES,
) {
    /** "<server hash>.<slot>" -> the decoded value; [Missing] when the disk has nothing usable */
    private val mem = ConcurrentHashMap<String, Any>()
    private val diskLock = Any()
    /** Bumped by [clear]: a write queued before it must not bring old data back */
    @Volatile private var generation = 0

    private object Missing

    @Serializable
    private class Envelope(val v: Int, val server: String, val slot: String, val data: JsonElement)

    /** The kept value of [slot] for [server], or null when there is none. Reads the disk only the first time. */
    fun <T> get(address: String, slot: CacheSlot<T>): T? {
        val server = norm(address)
        val key = key(server, slot)
        mem[key]?.let { v -> @Suppress("UNCHECKED_CAST") return if (v === Missing) null else v as T }
        val read = readDisk(server, slot)
        mem.putIfAbsent(key, read ?: Missing)
        @Suppress("UNCHECKED_CAST")
        return mem[key].let { if (it === Missing) null else it as T? }
    }

    /** Loads [slots] of [server] into memory (on a background thread at start-up), so the first screens read them without the disk. */
    fun warm(server: String, slots: List<CacheSlot<*>>) {
        slots.forEach { get(server, it) }
    }

    /** Keeps [value] (after its slot's trim): in memory now, on disk soon. */
    fun <T> put(address: String, slot: CacheSlot<T>, value: T) {
        val server = norm(address)
        val trimmed = slot.trim(value)
        val key = key(server, slot)
        mem[key] = trimmed as Any
        val gen = generation
        writer.execute {
            if (gen == generation) writeDisk(server, slot, trimmed, gen)
        }
    }

    /** Forgets everything (signing out). */
    fun clear() {
        synchronized(diskLock) {
            generation++
            mem.clear()
            dir.listFiles()?.forEach { if (it.name.endsWith(SUFFIX) || it.name.endsWith(TMP)) it.delete() }
        }
    }

    private fun norm(server: String) = server.trimEnd('/')

    private fun key(server: String, slot: CacheSlot<*>) = serverKey(server) + "." + slot.name

    private fun file(server: String, slot: CacheSlot<*>) = File(dir, key(server, slot) + SUFFIX)

    private fun <T> readDisk(server: String, slot: CacheSlot<T>): T? = synchronized(diskLock) {
        val f = file(server, slot)
        if (!f.exists()) return null
        try {
            if (f.length() > maxFileBytes + OVERHEAD) throw IllegalStateException("too large")
            val plain = cipher.decrypt(f.readBytes()).toString(Charsets.UTF_8)
            val env = KeloApi.json.decodeFromString(Envelope.serializer(), plain)
            if (env.v != VERSION || env.server != server || env.slot != slot.name) throw IllegalStateException("not this one")
            KeloApi.json.decodeFromJsonElement(slot.serializer, env.data)
        } catch (e: Exception) {
            f.delete()   // unreadable: treat as empty
            null
        }
    }

    private fun <T> writeDisk(server: String, slot: CacheSlot<T>, value: T, gen: Int) {
        val bytes = try {
            val env = Envelope(VERSION, server, slot.name, KeloApi.json.encodeToJsonElement(slot.serializer, value))
            val plain = KeloApi.json.encodeToString(Envelope.serializer(), env).toByteArray(Charsets.UTF_8)
            if (plain.size > maxFileBytes) null else cipher.encrypt(plain)
        } catch (e: Exception) {
            null
        }
        synchronized(diskLock) {
            if (gen != generation) return
            val f = file(server, slot)
            if (bytes == null) {   // too large to keep (or not encodable): better nothing than something old
                f.delete()
                return
            }
            try {
                dir.mkdirs()
                val tmp = File(dir, f.name + TMP)
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            } catch (e: Exception) {
                f.delete()
            }
        }
    }

    companion object {
        const val VERSION = 1
        /** The most one screen keeps (before encryption) */
        const val MAX_FILE_BYTES = 1_500_000
        private const val OVERHEAD = 64
        private const val SUFFIX = ".cache"
        private const val TMP = ".tmp"

        /** A file name part for a server address (the address itself is not written in the name). */
        fun serverKey(server: String): String =
            MessageDigest.getInstance("SHA-256").digest(server.trimEnd('/').toByteArray(Charsets.UTF_8))
                .take(12).joinToString("") { "%02x".format(it) }
    }
}
