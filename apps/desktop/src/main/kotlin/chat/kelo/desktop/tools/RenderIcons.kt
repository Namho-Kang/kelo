package chat.kelo.desktop.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import chat.kelo.app.ui.character.KeloCharacterAt
import chat.kelo.app.ui.character.KeloMood
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Draws Kelo's icons from the character itself (chat.kelo.app.ui.character, the first frame of the resting pose on the launcher
 * icon's warm cream, as on the phone) into [dir]:
 *  - kelo-<n>.png (16…1024), kelo.ico (Windows, PNG entries 16…256), kelo.icns (macOS)
 *  - tray.png: the character alone on transparent (the system tray / menu bar), also copied into the app's resources
 *  - msix/: the tiles the Microsoft Store package needs (packaging/windows/AppxManifest.xml names them)
 *
 *   ./gradlew renderIcons
 */
fun main(args: Array<String>) {
    val dir = File(args.firstOrNull() ?: "packaging/icons").apply { mkdirs() }
    val pngs = linkedMapOf<Int, ByteArray>()
    for (n in listOf(16, 24, 32, 48, 64, 128, 256, 512, 1024)) {
        val png = render(n, n) { Tile() }
        pngs[n] = png
        File(dir, "kelo-$n.png").writeBytes(png)
    }
    File(dir, "kelo.ico").writeBytes(ico(pngs.filterKeys { it <= 256 }))
    File(dir, "kelo.icns").writeBytes(icns(pngs))
    val tray = render(64, 64) { Box(Modifier.fillMaxSize()) { KeloCharacterAt(KeloMood.Idle, 0L, Modifier.fillMaxSize()) } }
    File(dir, "tray.png").writeBytes(tray)
    File("src/main/resources/icons").apply { mkdirs() }.let { res ->
        File(res, "tray.png").writeBytes(tray)
        File(res, "kelo-256.png").writeBytes(pngs.getValue(256))
    }
    val msix = File(dir, "msix").apply { mkdirs() }
    listOf("Square44x44Logo" to 44, "Square71x71Logo" to 71, "Square150x150Logo" to 150, "Square310x310Logo" to 310, "StoreLogo" to 50).forEach { (name, n) ->
        File(msix, "$name.png").writeBytes(render(n, n) { Tile(rounded = false) })
    }
    // the taskbar's own size, without the plate behind it (Windows draws it on its own colour)
    File(msix, "Square44x44Logo.targetsize-44_altform-unplated.png").writeBytes(render(44, 44) { Box(Modifier.fillMaxSize()) { KeloCharacterAt(KeloMood.Idle, 0L, Modifier.fillMaxSize()) } })
    File(msix, "Wide310x150Logo.png").writeBytes(render(310, 150) { Wide() })
    File(msix, "SplashScreen.png").writeBytes(render(620, 300) { Wide() })
    println("icons written to ${dir.absolutePath}")
}

private val CreamTop = Color(0xFFFFF8E1)
private val CreamBottom = Color(0xFFFFE8B0)

@Composable
private fun Tile(rounded: Boolean = true) {
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(if (rounded) 22 else 0)).background(Brush.verticalGradient(listOf(CreamTop, CreamBottom))),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            KeloCharacterAt(KeloMood.Idle, 0L, Modifier.fillMaxSize().padding(maxWidth * 0.04f))
        }
    }
}

@Composable
private fun Wide() {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CreamTop, CreamBottom))), contentAlignment = Alignment.Center) {
        KeloCharacterAt(KeloMood.Idle, 0L, Modifier.fillMaxHeight().aspectRatio(1f).padding(6.dp))
    }
}

/** One picture of [w] × [h] pixels (density 1: a dp is a pixel). */
private fun render(w: Int, h: Int, content: @Composable () -> Unit): ByteArray =
    ImageComposeScene(w, h, Density(1f)) { content() }.use { scene ->
        val img = scene.render()
        img.encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

/** A Windows .ico with PNG entries (allowed since Windows Vista). */
private fun ico(pngs: Map<Int, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
    fun le32(v: Int) { le16(v and 0xFFFF); le16((v shr 16) and 0xFFFF) }
    le16(0); le16(1); le16(pngs.size)
    var offset = 6 + 16 * pngs.size
    pngs.forEach { (n, png) ->
        out.write(if (n >= 256) 0 else n); out.write(if (n >= 256) 0 else n)
        out.write(0); out.write(0)
        le16(1); le16(32)
        le32(png.size); le32(offset)
        offset += png.size
    }
    pngs.values.forEach { out.write(it) }
    return out.toByteArray()
}

/** A macOS .icns with PNG entries. */
private fun icns(pngs: Map<Int, ByteArray>): ByteArray {
    val types = mapOf(16 to "icp4", 32 to "icp5", 64 to "icp6", 128 to "ic07", 256 to "ic08", 512 to "ic09", 1024 to "ic10")
    val body = ByteArrayOutputStream()
    val d = DataOutputStream(body)
    types.forEach { (n, t) ->
        val png = pngs[n] ?: return@forEach
        d.writeBytes(t); d.writeInt(png.size + 8); d.write(png)
    }
    val all = ByteArrayOutputStream()
    val h = DataOutputStream(all)
    h.writeBytes("icns"); h.writeInt(body.size() + 8); h.write(body.toByteArray())
    return all.toByteArray()
}
