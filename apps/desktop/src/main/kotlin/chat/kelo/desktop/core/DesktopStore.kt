package chat.kelo.desktop.core

import chat.kelo.app.core.AesGcmCipher
import chat.kelo.app.core.KeyValueStore
import java.io.File
import java.security.SecureRandom
import java.util.Properties
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * The app's small key/value file (kelo.properties in [DesktopPlatform.dataDir]). Secret values (the server address and this device's
 * token, the unsent chat text) arrive here already encrypted by chat.kelo.app.core.SecureStore; plain ones are settings.
 * Every change is written at once, through a temporary file, so a crash never leaves half a file.
 */
class FileKeyValueStore(private val file: File) : KeyValueStore {
    private val props = Properties()

    init {
        if (file.isFile) runCatching { file.inputStream().use { props.load(it) } }
    }

    @Synchronized override fun getString(key: String): String? = props.getProperty(key)

    @Synchronized override fun putString(key: String, value: String) {
        if (props.getProperty(key) == value) return
        props.setProperty(key, value)
        save()
    }

    @Synchronized override fun remove(key: String) {
        if (props.remove(key) != null) save()
    }

    private fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { props.store(it, "Kelo for desktop") }
        DesktopPlatform.restrict(tmp)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

/**
 * The AES-256 key that seals the saved sign-in (chat.kelo.app.core.AesGcmCipher, as on the phone).
 *
 * - Windows: the key itself is sealed with DPAPI (CryptProtectData) to the signed-in Windows user, so the file is useless when
 *   copied to another PC or read by another user.
 * - macOS and Linux: kept in an owner-only file next to the settings.
 * A key that can no longer be opened is replaced: the saved sign-in then simply asks to connect again.
 */
object StoreKey {
    private const val BYTES = 32

    fun load(dir: File): SecretKey {
        val raw = when (DesktopPlatform.os) {
            DesktopPlatform.Os.WINDOWS -> windowsKey(File(dir, "store.key.dpapi"))
            else -> fileKey(File(dir, "store.key"))
        }
        return SecretKeySpec(raw, "AES")
    }

    fun cipher(dir: File): AesGcmCipher {
        val key by lazy { load(dir) }
        return AesGcmCipher { key }
    }

    private fun fresh(): ByteArray = ByteArray(BYTES).also { SecureRandom().nextBytes(it) }

    private fun fileKey(f: File): ByteArray {
        if (f.isFile) {
            val b = runCatching { f.readBytes() }.getOrNull()
            if (b != null && b.size == BYTES) return b
        }
        val k = fresh()
        f.writeBytes(k)
        DesktopPlatform.restrict(f)
        return k
    }

    private fun windowsKey(f: File): ByteArray {
        if (f.isFile) {
            val opened = runCatching { com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(f.readBytes()) }.getOrNull()
            if (opened != null && opened.size == BYTES) return opened
        }
        val k = fresh()
        val sealed = runCatching { com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(k) }.getOrNull()
        if (sealed != null) {
            f.writeBytes(sealed)
            DesktopPlatform.restrict(f)
            return k
        }
        return fileKey(File(f.parentFile, "store.key"))   // DPAPI not reachable (very unusual): an owner-only file instead
    }
}
