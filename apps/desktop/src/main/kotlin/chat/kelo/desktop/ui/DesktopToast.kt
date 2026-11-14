package chat.kelo.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import chat.kelo.app.platform.LocalToaster
import chat.kelo.app.platform.Toaster
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.delay

/** The shared screens' short messages (Toaster) as a small pill at the bottom of the window that goes away by itself. */
@Composable
fun DesktopToasts(content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf<Pair<Long, String>?>(null) }
    val toaster = remember { Toaster { text -> shown = System.nanoTime() to text } }
    CompositionLocalProvider(LocalToaster provides toaster) {
        Box(Modifier.fillMaxSize()) {
            content()
            val t = shown
            if (t != null) {
                LaunchedEffect(t.first) { delay(3500); if (shown?.first == t.first) shown = null }
                val c = Kelo.colors
                val shape = RoundedCornerShape(50)
                Box(Modifier.align(Alignment.BottomCenter).padding(24.dp)) {
                    Text(
                        t.second, color = c.ink, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.widthIn(max = 560.dp).shadow(3.dp, shape, clip = false).clip(shape).background(c.card)
                            .border(1.dp, c.line, shape).padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
