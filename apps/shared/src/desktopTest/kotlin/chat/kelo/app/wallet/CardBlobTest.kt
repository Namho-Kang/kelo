package chat.kelo.app.wallet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** A stand-in for the Keystore cipher on the JVM: XOR with a key stream derived from the IV, plus a 16-byte "tag". */
private class FakeCipher(override val iv: ByteArray, private val key: Byte = 0x5A) : CardCipher {
    var calls = 0
    override fun doFinal(input: ByteArray): ByteArray {
        calls++
        return ByteArray(input.size) { (input[it].toInt() xor key.toInt() xor iv[it % iv.size].toInt()).toByte() }
    }
}

/** Encrypt: appends a tag; decrypt: checks and strips it (so a wrong key fails like GCM does). */
private class TaggedCipher(override val iv: ByteArray, private val encrypt: Boolean, private val key: Byte) : CardCipher {
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

class CardBlobTest {
    private val iv = ByteArray(12) { (it * 7 + 1).toByte() }

    @Test fun sealAndOpenRoundTrip() {
        val secret = CardSecret.of("4242424242424242", "123")!!
        val e2e = CardBlob.seal(secret, TaggedCipher(iv, true, 9), "android-abc")
        assertEquals(CardBlob.ALG, e2e.alg)
        assertEquals("android-abc", e2e.keyId)
        assertTrue(Regex("[A-Za-z0-9+/=]+").matches(e2e.blob))   // the server's e2e.blob alphabet
        assertTrue(e2e.blob.length <= 8192)
        assertFalse(String(Base64.getDecoder().decode(e2e.blob), Charsets.ISO_8859_1).contains("4242"))
        assertArrayEquals(iv, CardBlob.ivOf(e2e))
        val opened = CardBlob.open(e2e, TaggedCipher(CardBlob.ivOf(e2e)!!, false, 9))
        assertNotNull(opened)
        assertEquals("4242", opened!!.last4)
        assertEquals("""{"n":"4242424242424242","c":"123"}""", String(opened.payload(), Charsets.UTF_8))
    }

    @Test fun aWrongKeyOrAForeignBlobDoesNotOpen() {
        val e2e = CardBlob.seal(CardSecret.of("5555555555554444", "321")!!, TaggedCipher(iv, true, 3), "k")
        assertNull(CardBlob.open(e2e, TaggedCipher(iv, false, 4)))
        assertNull(CardBlob.ivOf(e2e.copy(alg = "something-else")))
        assertNull(CardBlob.ivOf(e2e.copy(blob = "!!notbase64")))
        assertNull(CardBlob.ivOf(e2e.copy(blob = Base64.getEncoder().encodeToString(byteArrayOf(2) + ByteArray(30)))))
    }

    @Test fun xorFakeRoundTripUsesTheCipherOnceEachWay() {
        val enc = FakeCipher(iv)
        val e2e = CardBlob.seal(CardSecret.of("4242424242424242", "999")!!, enc, "id")
        assertEquals(1, enc.calls)
        val dec = FakeCipher(CardBlob.ivOf(e2e)!!)
        assertEquals("4242", CardBlob.open(e2e, dec)!!.last4)
        assertEquals(1, dec.calls)
    }

    @Test fun secretOnlyTakesDigitsAndWipes() {
        assertNull(CardSecret.of("4242 4242", "123"))
        assertNull(CardSecret.of("4242", "12a"))
        assertNull(CardSecret.of("", "123"))
        val s = CardSecret.of("4242424242424242", "123")!!
        assertEquals("CardSecret(••4242)", s.toString())
        s.wipe()
        assertTrue(s.empty)
    }

    @Test fun parseWipesThePlainBytes() {
        val plain = """{"n":"4242424242424242","c":"123"}""".toByteArray()
        val s = CardSecret.parse(plain)
        assertEquals("4242", s!!.last4)
        assertTrue(plain.all { it == 0.toByte() })
        assertNull(CardSecret.parse("""{"n":"42x","c":"1"}""".toByteArray()))
        assertNull(CardSecret.parse("garbage".toByteArray()))
    }

    @Test fun secretJsonAppendsTheCardToTheOtherFields() {
        val s = CardSecret.of("5555555555554444", "321")!!
        val body = SecretJson.body(buildJsonObject { put("card_id", "card_1"); put("auto_pay", true) }, s)
        val o = Json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject
        assertEquals(JsonPrimitive("card_1"), o["card_id"])
        assertEquals(JsonPrimitive(true), o["auto_pay"])
        assertEquals(JsonPrimitive("5555555555554444"), o["number"])
        assertEquals(JsonPrimitive("321"), o["cvc"])
        val alone = Json.parseToJsonElement(String(SecretJson.body(JsonObject(emptyMap()), s), Charsets.UTF_8)).jsonObject
        assertEquals(setOf("number", "cvc"), alone.keys)
    }

    @Test fun oursComparesAlgAndKeyId() {
        val card = WalletCard("c", e2e = true, e2eAlg = CardBlob.ALG, e2eKeyId = "android-1")
        assertTrue(CardBlob.ours(card, "android-1"))
        assertFalse(CardBlob.ours(card, "android-2"))
        assertFalse(CardBlob.ours(card, null))
        assertFalse(CardBlob.ours(card.copy(e2e = false), "android-1"))
        assertFalse(CardBlob.ours(card.copy(e2eAlg = "x"), "android-1"))
    }
}
