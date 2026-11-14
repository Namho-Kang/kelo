package chat.kelo.app.keyguard

import chat.kelo.app.core.AesGcmCipher
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import chat.kelo.app.core.KeyValueStore
import chat.kelo.app.core.SecureStore
import chat.kelo.app.core.Session
import chat.kelo.app.core.Sessions
import chat.kelo.app.wallet.AutoPayGate
import chat.kelo.app.wallet.WalletCard
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** Keyguard on the phone (docs/keyguard.md, spec 2.4/5.14/5.15) and the auto-pay rule that depends on it. */
class KeyguardRulesTest {
    @Test fun theBlobIsVersionIvAndCiphertextWithTheSpecsAad() {
        // what the phone does with its Keystore key, here with a software AES-GCM key: wrap, encode, decode, unwrap
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val secret = "Zm9vYmFyYmF6cXV4cXV1eGNvcmdlZ3JhdWx0Z2FycGx5d2Fs".toByteArray()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key); updateAAD(KeyguardBlob.AAD) }
        val blob = KeyguardBlob.encode(c.iv, c.doFinal(secret))
        val raw = Base64.getDecoder().decode(blob)
        assertEquals(1, raw[0].toInt())                       // 0x01 = Android (the server tells phone kinds apart by it)
        val (iv, sealed) = KeyguardBlob.decode(blob)!!
        assertEquals(12, iv.size)
        val d = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)); updateAAD(KeyguardBlob.AAD) }
        assertArrayEquals(secret, d.doFinal(sealed))
        assertEquals("kelo-keyguard v1", String(KeyguardBlob.AAD))
        // an iPhone blob (0x02) or junk is not this phone's
        assertNull(KeyguardBlob.decode(Base64.getEncoder().encodeToString(byteArrayOf(2) + ByteArray(40))))
        assertNull(KeyguardBlob.decode("not base64!"))
        assertNull(KeyguardBlob.decode(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))))
    }

    @Test fun recoveryCodesAreReadTheWayPeopleTypeThem() {
        assertEquals("ABCDEFGHJKLMNPQRSTUVWXYZ", KeyguardRules.normalizeCode(" abcd-efgh jklm-npqr stuv-wxyz "))
        assertEquals("ABCD-EFGH-JKLM-NPQR-STUV-WXYZ", KeyguardRules.groupCode("ABCDEFGHJKLMNPQRSTUVWXYZ"))
        assertEquals("ABCD-EFGH-JKLM\nNPQR-STUV-WXYZ", KeyguardRules.codeLines("ABCDEFGHJKLMNPQRSTUVWXYZ"))
        assertEquals("ABCDEFGHJKLMNPQRSTUVWXYZ", KeyguardRules.normalizeCode(KeyguardRules.codeLines("ABCDEFGHJKLMNPQRSTUVWXYZ")))   // copied back in
        assertTrue(KeyguardRules.codeReady("ABCD-EFGH-JKLM-NPQR-STUV-WXYZ"))
        assertFalse(KeyguardRules.codeReady("ABCD-EFGH"))
    }

    @Test fun theViewAndTheLockedAnswer() {
        val v = Json { ignoreUnknownKeys = true }.decodeFromString(KeyguardView.serializer(),
            """{"mode":"phone","locked":true,"env_key":false,"recovery":true,"phones":[{"device_id":7,"name":"Pixel","alg":1,"created":1.0}]}""")
        assertTrue(v.on && v.locked && v.recovery)
        assertTrue(v.holds(7)); assertFalse(v.holds(8)); assertFalse(v.holds(null))
        assertFalse(KeyguardView().on)
        assertTrue(KeyguardRules.isLocked(ApiError(423, "locked", code = "locked")))
        assertTrue("last_keyguard_phone" in KeyguardRules.LAST_PHONE_CODES && "last_phone" in KeyguardRules.LAST_PHONE_CODES)
        assertFalse(EnrollAnswer("e", "secret-key-text", true, "CODE").toString().contains("secret-key-text"))
    }

    @Test fun autoPayNeedsKeyguard() {
        assertTrue(AutoPayGate.mayTurnOn(true))
        assertFalse(AutoPayGate.mayTurnOn(false))
        assertFalse(AutoPayGate.mayTurnOn(null))   // not known: the dialog too (the server refuses it anyway)
        val auto = WalletCard("c1", autoPay = true, usable = true)
        assertTrue(AutoPayGate.pausedForKeyguard(auto, keyguardOn = false))
        assertFalse(AutoPayGate.pausedForKeyguard(auto, keyguardOn = true))
        assertFalse(AutoPayGate.pausedForKeyguard(auto, keyguardOn = null))
        assertTrue(AutoPayGate.pausedForKeyguard(auto.copy(askReason = WalletCard.REASON_KEYGUARD), keyguardOn = null))   // the server's word
        assertFalse(AutoPayGate.pausedForKeyguard(auto.copy(autoPay = false), keyguardOn = false))
        assertFalse(AutoPayGate.pausedForKeyguard(auto.copy(expired = true), keyguardOn = false))
    }

    private class MapStore : KeyValueStore {
        val map = HashMap<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    @Test fun a423AnywhereShowsKeloIsLockedAndTheUnlockSendsTheKeyAsBytes() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val store = SecureStore(MapStore(), AesGcmCipher { key }).apply { saveSession(Session(server.url("/").toString().trimEnd('/'), "tok")) }
            val sessions = Sessions(store, requireE2e = false)
            server.enqueue(MockResponse().setResponseCode(423).setBody("""{"detail":"Kelo is locked","code":"locked"}"""))
            try { sessions.requireApi().get<JsonElement>("/api/vault"); fail() } catch (e: ApiError) { assertTrue(KeyguardRules.isLocked(e)) }
            assertTrue(sessions.locked.value)
            server.enqueue(MockResponse().setBody("""{"ok":true,"locked":false}"""))
            KeyguardCalls.unlock(sessions.requireApi(), "Zm9vYmFyYmF6cXV4cXV1eGNvcmdlZ3JhdWx0Z2FycGx5d2Fs".toByteArray())
            server.takeRequest()
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("Zm9vYmFyYmF6cXV4cXV1eGNvcmdlZ3JhdWx0Z2FycGx5d2Fs", body["key"]!!.jsonPrimitive.content)
            sessions.setLocked(false)
            assertFalse(sessions.locked.value)
        } finally {
            server.shutdown()
        }
    }

    @Test fun theLockedNotificationOpensTheUnlock() {
        val t = chat.kelo.app.notify.OpenTarget.decode(chat.kelo.app.notify.OpenTarget.Unlock.encode())
        assertEquals(chat.kelo.app.notify.OpenTarget.Unlock, t)
        assertEquals(chat.kelo.app.notify.OpenTarget.Unlock,
            chat.kelo.app.notify.NotifyRules.target(chat.kelo.app.agent.ServerNotification(1, type = "keyguard_locked")))
        val status = KeloApi.json.decodeFromString(chat.kelo.app.chat.AgentStatus.serializer(), """{"busy":false,"locked":true}""")
        assertTrue(status.locked)
        assertFalse(KeloApi.json.decodeFromString(chat.kelo.app.chat.AgentStatus.serializer(), """{"busy":false}""").locked)
    }
}
