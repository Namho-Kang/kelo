package chat.kelo.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingLinksTest {
    private val code = "AbCdEfGh_ijkl-MNOP1234"

    @Test fun aSignInCodeTypedInTheKeyFieldIsRecognized() {
        assertTrue(chat.kelo.app.start.ConnectViewModel.looksLikeCode("ABCD-EFGH-JKMN-PQRS"))
        assertTrue(chat.kelo.app.start.ConnectViewModel.looksLikeCode("  abcdefghjkmnpqrs "))
        assertFalse(chat.kelo.app.start.ConnectViewModel.looksLikeCode("short"))
        assertTrue(chat.kelo.app.start.ConnectViewModel.looksLikeCode("ABCD EFGH JKMN PQRS"))
        assertEquals("ABCDEFGHJKMNPQRS", chat.kelo.app.start.ConnectViewModel.codeOf(" ABCD EFGH JKMN PQRS "))
        assertFalse(chat.kelo.app.start.ConnectViewModel.looksLikeCode("ABCD\tEFGH\nJKMN PQRS"))
    }

    @Test fun parsesTheServersLink() {
        assertEquals(PairingLink("https://kelo.example.com", code), PairingLinks.parse("https://kelo.example.com/#pair=$code"))
    }

    @Test fun theServersKeyIsSplitOffTheCode() {
        val k = "OFJ52Ces7PFBXbc107iFdZtEkPYt3bDrO1FM3Msw01k"
        assertEquals(PairingLink("https://kelo.example.com", code, k), PairingLinks.parse("https://kelo.example.com/#pair=$code&k=$k"))
        // parameters the app does not know are ignored, wherever they are
        assertEquals(PairingLink("https://kelo.example.com", code, k), PairingLinks.parse("https://kelo.example.com/#pair=$code&v=2&k=$k&x"))
        assertEquals(PairingLink("https://kelo.example.com", code), PairingLinks.parse("https://kelo.example.com/#pair=$code&later=1"))
        // a damaged key, an empty one or two keys: not a link to pair with
        assertNull(PairingLinks.parse("https://kelo.example.com/#pair=$code&k=short"))
        assertNull(PairingLinks.parse("https://kelo.example.com/#pair=$code&k="))
        assertNull(PairingLinks.parse("https://kelo.example.com/#pair=$code&k=${k.dropLast(1)}+"))
        assertNull(PairingLinks.parse("https://kelo.example.com/#pair=$code&k=$k&k=$k"))
        // the code alone keeps its old rules
        assertNull(PairingLinks.parse("https://kelo.example.com/#pair=short&k=$k"))
    }

    @Test fun keepsPortAndLowercasesHost() {
        assertEquals(PairingLink("https://me.tail1234.ts.net:8443", code), PairingLinks.parse("https://Me.Tail1234.TS.net:8443/#pair=$code"))
    }

    @Test fun acceptsNoSlashBeforeHashAndSurroundingSpace() {
        assertEquals(PairingLink("https://kelo.example.com", code), PairingLinks.parse("  https://kelo.example.com#pair=$code \n"))
    }

    @Test fun decodesPercentEscapesButNotPlus() {
        assertEquals("abc+def/ghi=jklmnop", PairingLinks.parse("https://h.example/#pair=abc+def%2Fghi%3Djklmnop")?.code)
    }

    @Test fun httpForLocalDevelopment() {
        val link = PairingLinks.parse("http://10.0.2.2:8000/#pair=$code")!!
        assertEquals("http://10.0.2.2:8000", link.serverUrl)
        assertTrue(ServerAddress.isAllowed(link.serverUrl, allowLocalHttp = true))   // a debug build (the release rule is below)
    }

    @Test fun rejectsWhatIsNotAPairingLink() {
        assertNull(PairingLinks.parse(""))
        assertNull(PairingLinks.parse("https://kelo.example.com/"))
        assertNull(PairingLinks.parse("#pair=$code"))                              // no server
        assertNull(PairingLinks.parse("kelo.example.com/#pair=$code"))             // no scheme
        assertNull(PairingLinks.parse("ftp://kelo.example.com/#pair=$code"))
        assertNull(PairingLinks.parse("https://user:pw@kelo.example.com/#pair=$code"))
        assertNull(PairingLinks.parse("https://kelo.example.com/?x=1#pair=$code"))
        assertNull(PairingLinks.parse("https://kelo.example.com/#pair=short"))      // under 16 characters
        assertNull(PairingLinks.parse("https://kelo.example.com/#pair=" + "x".repeat(129)))
        assertNull(PairingLinks.parse("https://kelo.example.com/#pair=abcdefgh ijklmnopq"))
        assertNull(PairingLinks.parse("https://kelo.example.com/#pair=%ZZbadescapes1234567"))
    }

    @Test fun normalizesTypedServerAddresses() {
        assertEquals("https://kelo.example.com", ServerAddress.normalize("kelo.example.com/"))
        assertEquals("https://kelo.example.com", ServerAddress.normalize(" HTTPS://Kelo.Example.com "))
        assertEquals("https://h.example/kelo", ServerAddress.normalize("https://h.example/kelo/"))
        assertEquals("http://localhost:8000", ServerAddress.normalize("http://localhost:8000"))
        assertNull(ServerAddress.normalize(""))
        assertNull(ServerAddress.normalize("kelo example"))
        assertNull(ServerAddress.normalize("mailto:me@example.com"))
        assertNull(ServerAddress.normalize("kelo.example.com", defaultScheme = null))
    }

    @Test fun httpsOnlyExceptLocalHosts() {
        assertTrue(ServerAddress.isAllowed("https://kelo.example.com", allowLocalHttp = true))
        assertTrue(ServerAddress.isAllowed("http://127.0.0.1:8000", allowLocalHttp = true))
        assertTrue(ServerAddress.isAllowed("http://localhost", allowLocalHttp = true))
        assertFalse(ServerAddress.isAllowed("http://kelo.example.com", allowLocalHttp = true))
        assertFalse(ServerAddress.isAllowed("http://100.101.102.103:8000", allowLocalHttp = true))
        // a release build talks https only, even to the emulator's host
        assertFalse(ServerAddress.isAllowed("http://10.0.2.2:8000", allowLocalHttp = false))
        assertTrue(ServerAddress.isAllowed("https://kelo.example.com", allowLocalHttp = false))
    }

    @Test fun languageSuggestion() {
        assertEquals("ko", Languages.suggested(listOf("ko-KR")))
        assertEquals("en", Languages.suggested(listOf("en-US", "ko-KR")))   // the phone's first language the app has
        assertEquals("en", Languages.suggested(listOf("sv-SE")))           // English when the app lacks the phone's language
    }
}
