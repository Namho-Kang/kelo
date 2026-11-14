package chat.kelo.app.connectors

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.core.KeloApi
import chat.kelo.app.ui.platform.decodeImage
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The services' app icons from the server (GET /api/connectors/icons/…), decoded once and kept in memory, and the files kept on
 * disk in [dir] so the list shows them at once next time and offline. A URL that cannot be fetched gives null and the tile keeps
 * its initials. Each app makes one (its own cache folder) and hands it out as [chat.kelo.app.AppServices.connectorIcons].
 */
class ConnectorIcons(private val dir: File) {
    // least recently used first; bounded by the decoded size
    private val memory = LinkedHashMap<String, ImageBitmap>(16, 0.75f, true)
    private var used = 0L
    private val lock = Mutex()

    fun cached(url: String): ImageBitmap? = synchronized(memory) { memory[url] }

    private fun keep(url: String, img: ImageBitmap) = synchronized(memory) {
        memory.put(url, img)?.let { used -= bytes(it) }
        used += bytes(img)
        while (used > MEMORY_BYTES && memory.size > 1) {
            val eldest = memory.entries.first()
            used -= bytes(eldest.value)
            memory.remove(eldest.key)
        }
    }

    private fun bytes(img: ImageBitmap) = img.width.toLong() * img.height * 4

    suspend fun load(api: KeloApi?, url: String): ImageBitmap? {
        if (!ConnectorIconRules.valid(url)) return null
        cached(url)?.let { return it }
        return lock.withLock {
            cached(url)?.let { return@withLock it }
            val file = File(dir, ConnectorIconRules.fileName(url))
            val img = withContext(Dispatchers.IO) {
                val kept = runCatching { if (file.isFile) file.readBytes() else null }.getOrNull()
                kept?.let(::decode) ?: run {
                    val bytes = runCatching { api?.download(url) }.getOrNull() ?: return@run null
                    decode(bytes)?.also {
                        runCatching {
                            dir.mkdirs()
                            val name = ConnectorIconRules.fileName(url)
                            dir.listFiles()?.filter { f -> ConnectorIconRules.stale(url, f.name) }?.forEach { f -> f.delete() }   // older versions
                            val tmp = File(dir, "$name.tmp")
                            tmp.writeBytes(bytes)
                            tmp.renameTo(file)
                        }
                    }
                }
            }
            img?.also { keep(url, it) }
        }
    }

    private fun decode(bytes: ByteArray): ImageBitmap? = runCatching { decodeImage(bytes) }.getOrNull()

    private companion object {
        const val MEMORY_BYTES = 16L * 1024 * 1024
    }
}

/**
 * A service's picture: its own app icon in a rounded square when the server has one, else [fallback] (the initials tile). While the
 * icon loads, a soft square in its colour holds the place, so the row does not jump.
 */
@Composable
fun ServiceIcon(icon: String, small: String, color: String, size: Dp, fallback: @Composable () -> Unit) {
    val url = ConnectorIconRules.pick(icon, small, size.value)
    if (url.isEmpty()) {
        fallback()
        return
    }
    val services = LocalAppServices.current
    // null = still loading, Unit = failed
    val state by produceState<Any?>(services.connectorIcons.cached(url), url) {
        if (value == null) value = services.connectorIcons.load(services.sessions.api.value, url) ?: Unit
    }
    val shape = RoundedCornerShape(size * 0.28f)
    when (val s = state) {
        is ImageBitmap -> Image(
            s, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(shape).border(1.dp, Kelo.colors.line, shape),
        )
        null -> {
            val tint = ConnectorIconRules.color(color)?.let { Color(it).copy(alpha = 0.18f) } ?: Kelo.colors.bubble
            Box(Modifier.size(size).clip(shape).background(tint))
        }
        else -> fallback()
    }
}
