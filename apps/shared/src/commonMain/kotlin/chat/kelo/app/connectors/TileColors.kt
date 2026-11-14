package chat.kelo.app.connectors

import androidx.compose.ui.graphics.Color

/** The colour of a service's initials tile, made from its id (the same hue the connector list gives it). */
object TileColors {
    fun hue(id: String): Float {
        var h = 0x811C9DC5.toInt()   // FNV-1a
        id.forEach { ch -> h = (h xor ch.code) * 0x01000193 }
        return ((h ushr 1) % 360).toFloat()
    }

    fun of(hue: Float, dark: Boolean): Color = Color.hsl(hue, 0.42f, if (dark) 0.34f else 0.38f)
}
