package chat.kelo.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

/** Plain JVM: the same AES-GCM code with a software key in place of the Android Keystore one. */
class SecureStoreTest {
    private class MapStore : KeyValueStore {
        val map = HashMap<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private fun softwareKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun roundTripsTheSession() {
        val kv = MapStore()
        val key = softwareKey()
        val store = SecureStore(kv, AesGcmCipher { key })
        val session = Session("https://kelo.example.com", "device-token-0123456789-secret")
        store.saveSession(session)

        assertEquals(session, store.session())
        assertEquals(session, SecureStore(kv, AesGcmCipher { key }).session())   // read back by a fresh instance
    }

    @Test fun theServerKeyIsPinnedPerServerAndKeptOnSignOut() {
        val kv = MapStore()
        val key = softwareKey()
        val store = SecureStore(kv, AesGcmCipher { key })
        val pin = chat.kelo.app.core.e2e.KeyPin(chat.kelo.app.core.e2e.ServerKey(ByteArray(32) { 7 }), verified = false)
        store.saveSession(Session("https://kelo.example.com", "device-token-0123456789-secret", pin))
        assertEquals(pin, store.session()?.pin)
        assertEquals(pin, store.pinFor("https://Kelo.example.com/"))     // the same server however it is written
        assertNull(store.pinFor("https://other.example.com"))
        store.clearSession()
        assertNull(store.session())
        assertEquals(pin, store.pinFor("https://kelo.example.com"))      // once seen, never plain again (spec 5.5)
        assertTrue(kv.map.values.none { it.contains(pin.key.base64Url) })   // kept encrypted like the session
        store.forgetPin("https://kelo.example.com")
        assertNull(store.pinFor("https://kelo.example.com"))
    }

    @Test fun nothingSecretIsStoredInPlainText() {
        val kv = MapStore()
        val key = softwareKey()
        SecureStore(kv, AesGcmCipher { key }).saveSession(Session("https://kelo.example.com", "device-token-0123456789-secret"))
        kv.map.values.forEach { v ->
            assertFalse(v.contains("kelo.example.com"))
            assertFalse(v.contains("device-token"))
        }
    }

    @Test fun freshIvEachTime() {
        val key = softwareKey()
        val c = AesGcmCipher { key }
        assertNotEquals(c.encrypt("same".toByteArray()).toList(), c.encrypt("same".toByteArray()).toList())
    }

    @Test fun signOutKeepsTheAddressOnly() {
        val kv = MapStore()
        val key = softwareKey()
        val store = SecureStore(kv, AesGcmCipher { key })
        store.saveSession(Session("https://kelo.example.com", "tok-0123456789abcdef"))
        store.clearSession()
        assertNull(store.session())
        assertEquals("https://kelo.example.com", store.lastServerUrl())
    }

    @Test fun aLostKeyMeansSignedOutNotACrash() {
        val kv = MapStore()
        val first = softwareKey()
        SecureStore(kv, AesGcmCipher { first }).saveSession(Session("https://kelo.example.com", "tok-0123456789abcdef"))
        val other = softwareKey()
        val store = SecureStore(kv, AesGcmCipher { other })
        assertNull(store.session())
        assertNull(kv.map[SecureStore.KEY_TOKEN])   // the unreadable value is dropped
    }

    @Test fun languageIsKept() {
        val key = softwareKey()
        val store = SecureStore(MapStore(), AesGcmCipher { key })
        assertNull(store.language)
        store.language = "ko"
        assertEquals("ko", store.language)
    }

    @Test fun keepsTheDraftEncryptedAndDropsItOnSignOut() {
        val kv = MapStore()
        val key = softwareKey()
        val store = SecureStore(kv, AesGcmCipher { key })
        store.saveSession(Session("https://kelo.example.com", "device-token-0123456789-secret"))
        store.saveDraft("내일 회의 자료 정리해 줘")
        assertEquals("내일 회의 자료 정리해 줘", SecureStore(kv, AesGcmCipher { key }).loadDraft())   // survives a restart
        kv.map.values.forEach { v -> assertFalse(v.contains("회의")) }

        store.saveDraft("")
        assertEquals("", store.loadDraft())
        assertNull(kv.map[SecureStore.KEY_DRAFT])

        store.saveDraft("다시 쓰는 중")
        store.clearSession()
        assertEquals("", store.loadDraft())
    }
}
