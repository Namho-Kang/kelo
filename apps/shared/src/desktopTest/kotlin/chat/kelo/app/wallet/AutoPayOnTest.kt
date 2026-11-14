package chat.kelo.app.wallet

import chat.kelo.app.core.ApiError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning auto-pay on without typing the card again (AutoPayOn): this phone opens its own end-to-end copy after the owner's check
 * and sends the number and CVC once; a card added on another device says so. A fake key store stands in for the Keystore.
 */
class AutoPayOnTest {
    /** The fake key store: one key (an XOR byte) under one key id; decrypting checks a 16-byte "tag" like GCM does. */
    private class FakeKeys(val keyId: String, private val key: Byte) {
        fun cipher(iv: ByteArray, encrypt: Boolean) = object : CardCipher {
            override val iv: ByteArray = iv
            override fun doFinal(input: ByteArray): ByteArray {
                val x = { b: ByteArray -> ByteArray(b.size) { (b[it].toInt() xor key.toInt()).toByte() } }
                return if (encrypt) x(input) + ByteArray(16) { key }
                else {
                    val tag = input.copyOfRange(input.size - 16, input.size)
                    if (!tag.all { it == key }) throw javax.crypto.AEADBadTagException("bad tag")
                    x(input.copyOfRange(0, input.size - 16))
                }
            }
        }
    }

    private val iv = ByteArray(12) { (it + 3).toByte() }
    private val keys = FakeKeys("android-k1", 7)
    private val blob = CardBlob.seal(CardSecret.of("4242424242424242", "123")!!, keys.cipher(iv, true), keys.keyId)
    private val json = Json { ignoreUnknownKeys = true }

    private fun card(keyId: String = keys.keyId, alg: String = CardBlob.ALG) = json.decodeFromString<WalletCard>(
        """{"id":"c1","label":"Everyday","brand":"visa","last4":"4242","usable":true,"auto_pay":false,"e2e":true,"e2e_alg":"$alg","e2e_key_id":"$keyId"}""",
    )

    @Test fun theOwnersCheckOpensThisPhonesCopyAndSendsItOnce() = runBlocking {
        val sent = mutableListOf<String>()
        var asked = 0
        var kept: CardSecret? = null
        val r = AutoPayOn.run(card(), keys.keyId, { blob }, { v -> asked++; CardAuth.Ok(keys.cipher(v, false), keys.keyId) }) { s ->
            sent += String(s.payload(), Charsets.UTF_8)
            kept = s
        }
        assertNull(r)
        assertEquals(1, asked)
        assertEquals(listOf("""{"n":"4242424242424242","c":"123"}"""), sent)
        assertTrue(kept!!.empty)                                                       // wiped once sent
    }

    @Test fun aCardAddedOnAnotherDeviceIsNotOnThisPhone() = runBlocking {
        var fetched = false
        val unlock: suspend (ByteArray) -> CardAuth = { error("never asked") }
        assertEquals(CardSendError.NOT_ON_PHONE, AutoPayOn.run(card(keyId = "ios-other"), keys.keyId, { fetched = true; blob }, unlock) {})
        assertEquals(CardSendError.NOT_ON_PHONE, AutoPayOn.run(card(), null, { blob }, unlock) {})      // this phone has no key
        assertEquals(false, fetched)
        val gone: suspend () -> E2eBlob = { throw ApiError(404, "no copy") }
        assertEquals(CardSendError.NOT_ON_PHONE, AutoPayOn.run(card(), keys.keyId, gone, unlock) {})
    }

    @Test fun backingOutOrAWrongKeySendsNothing() = runBlocking {
        var sent = false
        assertEquals(CardSendError.CANCELLED, AutoPayOn.run(card(), keys.keyId, { blob }, { CardAuth.Cancelled }) { sent = true })
        assertEquals(CardSendError.NO_LOCK, AutoPayOn.run(card(), keys.keyId, { blob }, { CardAuth.NoLock }) { sent = true })
        val wrong = FakeKeys(keys.keyId, 9)
        assertEquals(CardSendError.NOT_ON_PHONE, AutoPayOn.run(card(), keys.keyId, { blob }, { v -> CardAuth.Ok(wrong.cipher(v, false), keys.keyId) }) { sent = true })
        assertEquals(false, sent)
    }

    @Test fun theServerRefusingSaysWhy() = runBlocking {
        val ok: suspend (ByteArray) -> CardAuth = { v -> CardAuth.Ok(keys.cipher(v, false), keys.keyId) }
        assertEquals(CardSendError.MISMATCH, AutoPayOn.run(card(), keys.keyId, { blob }, ok) { throw ApiError(422, "last 4 differ") })
        assertEquals(CardSendError.NETWORK, AutoPayOn.run(card(), keys.keyId, { blob }, ok) { throw ApiError(ApiError.NO_RESPONSE, "") })
    }
}
