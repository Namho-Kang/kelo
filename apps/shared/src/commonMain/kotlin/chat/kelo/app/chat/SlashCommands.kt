package chat.kelo.app.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.kelo.app.res.*
import chat.kelo.app.ui.text.string
import chat.kelo.app.ui.theme.Kelo
import org.jetbrains.compose.resources.StringResource

/** The chat's "/" commands. The server knows the same words (server.py NEW_SESSION_WORDS); pure, so it is tested. */
object SlashCommands {
    data class Command(val word: String, val about: StringResource)

    val ALL: List<Command> = listOf(Command("/new", Res.string.slash_new_about))

    /** The commands that start with what is typed: only while the text starts with "/" and has no space or line break yet. */
    fun matches(input: String): List<Command> {
        if (!input.startsWith("/") || input.any { it.isWhitespace() }) return emptyList()
        val typed = input.lowercase()
        return ALL.filter { it.word.startsWith(typed) }
    }

    /** The field after picking [c]: the word and a space (the space closes the list). */
    fun picked(c: Command): String = c.word + " "
}

/** The matching commands in a small card above the composer; a tap puts the command in the field. */
@Composable
fun SlashMenu(commands: List<SlashCommands.Command>, onPick: (SlashCommands.Command) -> Unit, modifier: Modifier = Modifier) {
    val c = Kelo.colors
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.shadow(6.dp, shape).clip(shape).background(c.card).border(1.dp, c.line, shape).padding(vertical = 4.dp)) {
        commands.forEach { cmd ->
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(cmd) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(cmd.word, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = c.ink)
                Text(string(cmd.about), style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.weight(1f))
            }
        }
    }
}
