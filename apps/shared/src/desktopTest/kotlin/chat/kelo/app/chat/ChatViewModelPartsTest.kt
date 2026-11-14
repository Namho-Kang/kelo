package chat.kelo.app.chat

import chat.kelo.app.core.ApiError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure pieces the chat view model's helpers use ([AttachErrors], [referencedInput], [toChatItem], [ChatState]). */
class ChatViewModelPartsTest {
    @Test fun theServersOwnWordsForAFileItRefusesAreShown() {
        assertEquals(AttachError.Server("Photos can be up to 15 MB."), AttachErrors.of(ApiError(413, "Photos can be up to 15 MB.")))
        assertEquals(AttachError.Server("The server is out of space."), AttachErrors.of(ApiError(507, "The server is out of space.")))
    }

    @Test fun aLostConnectionAndAServerRefusalAreToldApart() {
        assertEquals(AttachError.Network, AttachErrors.of(ApiError(ApiError.NO_RESPONSE, "no network")))
        assertEquals(AttachError.Server("nope"), AttachErrors.of(ApiError(400, "nope")))
    }

    @Test fun aReferenceReplacesTheSecretOnlyWhileTheComposerIsAsItWasPutBack() {
        val found = SecretFound(listOf(SecretSpan(6, 12, SecretSpan.PASSWORD)), "x", SecretTarget(SecretTarget.VAULT, ""))
        val ask = SecretAsk(found, "token hunter", emptyList(), fromComposer = true, tookAttachments = false, composer = "token hunter",
            composerSpans = found.spans)
        assertEquals("token (saved)", referencedInput("token hunter", ask, "(saved)", null))
        // the owner rewrote the text meanwhile: left alone
        assertEquals("other text", referencedInput("other text", ask, "(saved)", null))
        // a button's text never lives in the composer
        val fromButton = SecretAsk(found, "token hunter", emptyList(), fromComposer = false, tookAttachments = false)
        assertEquals("token hunter", referencedInput("token hunter", fromButton, "(saved)", null))
    }

    @Test fun aStoredMessageBecomesARowKeyedByItsId() {
        val row = toChatItem(StoredMessage(id = 42, role = "user", content = "hi", createdAt = 1.5))
        assertTrue(row is ChatItem.Msg)
        row as ChatItem.Msg
        assertEquals("m42", row.key)
        assertTrue(row.mine)
        assertEquals(1500L, row.at)
        assertEquals("hi", row.text)
    }

    @Test fun theTrayCountsOnlyUploadsThatAreDone() {
        val s = ChatState(attachments = listOf(
            Attachment("a1", "one", true, state = AttachState.DONE, stored = "one.jpg"),
            Attachment("a2", "two", false, state = AttachState.UPLOADING),
        ))
        assertTrue(s.uploading)
        assertEquals(listOf("a1"), s.ready.map { it.id })
    }
}
