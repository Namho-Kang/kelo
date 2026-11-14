package chat.kelo.app.agent

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.theme.Kelo

/** A filled button as tall as [SecondaryButton] beside it. */
@Composable
fun SmallPrimary(text: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Kelo.colors
    androidx.compose.material3.Button(
        onClick = onClick, modifier = modifier, shape = RoundedCornerShape(50),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) { Text(text, style = MaterialTheme.typography.bodyLarge) }
}
