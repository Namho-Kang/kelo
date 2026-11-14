package chat.kelo.app.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlashCommandsTest {
    @Test fun `a slash lists the commands`() = assertEquals(listOf("/new"), SlashCommands.matches("/").map { it.word })
    @Test fun `the typed start filters`() {
        assertEquals(listOf("/new"), SlashCommands.matches("/N").map { it.word })
        assertTrue(SlashCommands.matches("/x").isEmpty())
    }
    @Test fun `a space or plain text closes it`() {
        assertTrue(SlashCommands.matches("/new ").isEmpty())
        assertTrue(SlashCommands.matches("hello /new").isEmpty())
        assertTrue(SlashCommands.matches("").isEmpty())
    }
    @Test fun `picking puts the word and a space`() = assertEquals("/new ", SlashCommands.picked(SlashCommands.ALL.first()))
}
