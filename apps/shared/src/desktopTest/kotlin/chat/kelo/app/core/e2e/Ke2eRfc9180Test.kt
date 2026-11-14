package chat.kelo.app.core.e2e

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RFC 9180 Appendix A.2.1: DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, ChaCha20Poly1305, base mode (the KE2E suite), through the
 * same HPKE code path the envelope uses.
 */
class Ke2eRfc9180Test {
    private val info = hex("4f6465206f6e2061204772656369616e2055726e")
    private val ikmE = hex("909a9b35d3dc4713a5e72a4da274b55d3d3821a37e5d099e74a647db583a904b")
    private val pkEm = hex("1afa08d3dec047a643885163f1180476fa7ddb54c6a8029ea33f95796bf2ac4a")
    private val skEm = hex("f4ec9b33b792c372c1d2c2063507b684ef925b8c75a42dbcbf57d63ccd381600")
    private val ikmR = hex("1ac01f181fdf9f352797655161c58b75c656a6cc2716dcb66372da835542e1df")
    private val pkRm = hex("4310ee97d88cc1f088a5576c77ab0cf5c3ac797f3d95139c6c84b5429c59662a")
    private val skRm = hex("8057991eef8f1f1af18f4a9491d16a1ce333f695d4db8e38da75975c4478e0fb")
    private val enc = hex("1afa08d3dec047a643885163f1180476fa7ddb54c6a8029ea33f95796bf2ac4a")
    private val pt = hex("4265617574792069732074727574682c20747275746820626561757479")

    /** sequence number → (aad, ct) */
    private val encryptions = linkedMapOf(
        0 to ("436f756e742d30" to "1c5250d8034ec2b784ba2cfd69dbdb8af406cfe3ff938e131f0def8c8b60b4db21993c62ce81883d2dd1b51a28"),
        1 to ("436f756e742d31" to "6b53c051e4199c518de79594e1c4ab18b96f081549d45ce015be002090bb119e85285337cc95ba5f59992dc98c"),
        2 to ("436f756e742d32" to "71146bd6795ccc9c49ce25dda112a48f202ad220559502cef1f34271e0cb4b02b4f10ecac6f48c32f878fae86b"),
        4 to ("436f756e742d34" to "63357a2aa291f5a4e5f27db6baa2af8cf77427c7c1a909e0b37214dd47db122bb153495ff0b02e9e54a50dbe16"),
        255 to ("436f756e742d323535" to "18ab939d63ddec9f6ac2b60d61d36a7375d2070c9b683861110757062c52b8880a5f6b3936da9cd6c23ef2a95c"),
        256 to ("436f756e742d323536" to "7a4a13e9ef23978e2c520fd4d2e757514ae160cd0cd05e556ef692370ca53076214c0c40d4c728d6ed9e727a5b"),
    )

    /** exporter_context → exported_value (L = 32) */
    private val exports = listOf(
        "" to "4bbd6243b8bb54cec311fac9df81841b6fd61f56538a775e7c80a9f40160606e",
        "00" to "8c1df14732580e5501b00f82b10a1647b40713191b7c1240ac80e2b68808ba69",
        "54657374436f6e74657874" to "5acb09211139c43b3090489a9da433e8a30ee7188ba8b0a9a1ccf0c229283e53",
    )

    @Test fun deriveKeyPairGivesTheVectorKeys() {
        // BouncyCastle hands the private key out clamped (RFC 7748); X25519 clamps on use, so compare clamped
        val e = X25519KeyPair.derive(ikmE)
        assertArrayEquals(pkEm, e.publicKey)
        assertArrayEquals(clamp(skEm), clamp(e.privateKey))
        val r = X25519KeyPair.derive(ikmR)
        assertArrayEquals(pkRm, r.publicKey)
        assertArrayEquals(clamp(skRm), clamp(r.privateKey))
        assertArrayEquals(pkRm, X25519KeyPair.fromPrivate(skRm).publicKey)
    }

    @Test fun senderMatchesTheVectors() {
        val ctx = Ke2eContext.senderWithInfo(pkRm, info, X25519KeyPair(skEm, pkEm))
        assertArrayEquals(enc, ctx.enc)
        var seq = 0
        for ((n, v) in encryptions) {
            while (seq < n) { ctx.seal(ByteArray(0), ByteArray(0)); seq++ }   // move the sequence number on
            assertEquals("seq $n", v.second, hexOf(ctx.seal(hex(v.first), pt)))
            seq++
        }
        for ((c, v) in exports) assertEquals("export '$c'", v, hexOf(ctx.exportRaw(hex(c))))
    }

    @Test fun receiverMatchesTheVectors() {
        val ctx = Ke2eContext.receiverWithInfo(enc, X25519KeyPair(skRm, pkRm), info)
        // the receiver's sequence moves only on a successful open: the skipped numbers are opened from a twin sender
        val twin = Ke2eContext.senderWithInfo(pkRm, info, X25519KeyPair(skEm, pkEm))
        var seq = 0
        for ((n, v) in encryptions) {
            while (seq < n) { ctx.open(ByteArray(0), twin.seal(ByteArray(0), ByteArray(0))); seq++ }
            twin.seal(hex(v.first), pt)
            assertArrayEquals("seq $n", pt, ctx.open(hex(v.first), hex(v.second)))
            seq++
        }
        for ((c, v) in exports) assertEquals("export '$c'", v, hexOf(ctx.exportRaw(hex(c))))
    }

    @Test fun ke2eSuiteIdsAreTheSpecs() {
        val h = Ke2e.hpke()
        assertEquals(32, h.encSize)
        assertEquals(0x0003.toShort(), h.aeadId)
    }
}

private fun clamp(k: ByteArray): ByteArray = k.copyOf().also {
    it[0] = (it[0].toInt() and 248).toByte()
    it[31] = ((it[31].toInt() and 127) or 64).toByte()
}

internal fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
internal fun hexOf(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
