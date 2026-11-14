package chat.kelo.desktop.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import chat.kelo.app.ui.character.KeloCharacterAt
import chat.kelo.app.ui.character.KeloMood
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Draws the Microsoft Store's optional images from the character itself (same character and cream as the app icon):
 * 1:1 box art (1080, 2160) and app tile (300), 9:16 poster art (720x1080, 1440x2160; the character with the name "Kelo AI").
 *
 *   ./gradlew renderStoreLogos           (writes store/logos)
 */
fun main(args: Array<String>) {
    val dir = File(args.firstOrNull() ?: "store/logos").apply { mkdirs() }
    // the layouts are drawn on a 360 dp wide design, scaled by the density: the same picture at every size
    fun write(name: String, w: Int, h: Int, content: @Composable () -> Unit) {
        File(dir, name).writeBytes(
            ImageComposeScene(w, h, Density(w / 360f)) { content() }.use { it.render().encodeToData(EncodedImageFormat.PNG)!!.bytes },
        )
    }
    for ((n, name) in listOf(1080 to "box-art-1080x1080.png", 2160 to "box-art-2160x2160.png", 300 to "app-tile-300x300.png")) write(name, n, n) { Square() }
    write("poster-720x1080.png", 720, 1080) { Poster() }
    write("poster-1440x2160.png", 1440, 2160) { Poster() }
    println("store logos written to ${dir.absolutePath}")
}

private val CreamTop = Color(0xFFFFF8E1)
private val CreamBottom = Color(0xFFFFE8B0)
private val Ink = Color(0xFF1B2430)

@Composable
private fun Square() {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CreamTop, CreamBottom))), contentAlignment = Alignment.Center) {
        KeloCharacterAt(KeloMood.Idle, 0L, Modifier.size(330.dp))
    }
}

@Composable
private fun Poster() {
    // 360 x 540 dp
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CreamTop, CreamBottom)))) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            KeloCharacterAt(KeloMood.Idle, 0L, Modifier.size(290.dp))
            Text("Kelo AI", Modifier.fillMaxWidth().padding(top = 8.dp), color = Ink, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
