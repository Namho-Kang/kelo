package chat.kelo.app.wallet

import chat.kelo.app.core.ApiError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Encrypt appends a tag; decrypt checks and strips it (a wrong key fails like GCM). */
private class Tagged(override val iv: ByteArray, private val encrypt: Boolean, private val key: Byte) : CardCipher {
    override fun doFinal(input: ByteArray): ByteArray {
        val x = { b: ByteArray -> ByteArray(b.size) { (b[it].toInt() xor key.toInt()).toByte() } }
        return if (encrypt) x(input) + ByteArray(16) { key } else {
            if (!input.copyOfRange(input.size - 16, input.size).all { it == key }) throw javax.crypto.AEADBadTagException("bad tag")
            x(input.copyOfRange(0, input.size - 16))
        }
    }
}

class PinVaultTest {
    private val iv = ByteArray(12) { (it * 5 + 3).toByte() }
    private val pin = "731946"

    @Test fun onlyFourToEightDigitsAndNeverPrinted() {
        assertNull(PinSecret.of("123"))
        assertNull(PinSecret.of("123456789"))
        assertNull(PinSecret.of("12a456"))
        val s = PinSecret.of(pin)!!
        assertFalse(s.toString().contains(pin))
        assertEquals(6, s.length)
        s.wipe()
        assertEquals(0, s.length)
    }

    @Test fun sealOpenRoundTripAndWrongKeyFails() {
        val e2e = PinBlob.seal(PinSecret.of(pin)!!, Tagged(iv, true, 7), "android-k")
        assertEquals(PinBlob.ALG, e2e.alg)
        assertFalse(String(Base64.getDecoder().decode(e2e.blob), Charsets.ISO_8859_1).contains(pin))
        assertTrue(PinBlob.ivOf(e2e)!!.contentEquals(iv))
        val open = PinBlob.open(e2e, Tagged(iv, false, 7))!!
        val body = PinJson.body(buildJsonObject { put("pin_id", "pin_1") }, open)
        val json = Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
        assertEquals(pin, json["pin"]!!.jsonPrimitive.content)
        assertEquals("pin_1", json["pin_id"]!!.jsonPrimitive.content)
        assertNull(PinBlob.open(e2e, Tagged(iv, false, 8)))
        assertNull(PinBlob.ivOf(e2e.copy(alg = CardBlob.ALG)))   // a card's blob is not a PIN's
    }

    @Test fun releaseNeedsThisPhonesKeyTheCheckAndSendsOnce() = runBlocking {
        val e2e = PinBlob.seal(PinSecret.of(pin)!!, Tagged(iv, true, 7), "android-k")
        val p = PaymentPin("pin_1", "shop.example", "생활", 6, PinBlob.ALG, "android-k")
        var sent: String? = null
        val ok = PinRelease.run(p, "android-k", { e2e }, { CardAuth.Ok(Tagged(iv, false, 7), "android-k") }) { s ->
            sent = PinJson.body(buildJsonObject {}, s).toString(Charsets.UTF_8)
        }
        assertNull(ok)
        assertTrue(sent!!.contains(pin))
        assertEquals(CardSendError.NOT_ON_PHONE, PinRelease.run(p, "android-other", { e2e }, { error("no") }) {})
        assertEquals(CardSendError.CANCELLED, PinRelease.run(p, "android-k", { e2e }, { CardAuth.Cancelled }) {})
        assertEquals(CardSendError.NOT_WAITING, PinRelease.run(p, "android-k", { e2e }, { CardAuth.Ok(Tagged(iv, false, 7), "android-k") }) {
            throw ApiError(404, "gone")
        })
    }

    @Test fun formRules() {
        assertEquals("123456", PinRules.clean("12-34 56"))
        assertEquals("12345678", PinRules.clean("1234567890"))
        assertTrue(PinRules.ready("123456", "123456", "shop.example.com"))
        assertFalse(PinRules.ready("123456", "123457", "shop.example.com"))
        assertFalse(PinRules.ready("123", "123", "shop.example.com"))
        assertFalse(PinRules.ready("123456", "123456", "not a site"))
        assertTrue(PinRules.siteOk("https://pay.shop.example.co.kr/checkout"))
        assertTrue(PinRules.mismatch("1234", "1235"))
        assertFalse(PinRules.mismatch("123456", "12"))
    }
}
