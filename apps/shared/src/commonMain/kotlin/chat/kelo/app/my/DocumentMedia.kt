package chat.kelo.app.my

import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.theme.Kelo
import chat.kelo.app.platform.LocalMediaPlayer
import chat.kelo.app.ui.platform.decodeImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/* Photos, videos and sound in My documents: previews from the server (GET /api/uploads/{name}/thumb), a picture on the whole
 * screen with pinch-zoom, and videos and sound played in the app (Media3 ExoPlayer, streamed with Range requests and the token). */

/** What kind of file a name is, as the server decides it (media.kind_of): image | video | audio | document. */
object MediaKind {
    private val IMAGE = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
    private val VIDEO = setOf("mp4", "m4v", "mov", "webm", "3gp", "mkv")
    private val AUDIO = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "amr")

    fun of(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in IMAGE -> "image"
            in VIDEO -> "video"
            in AUDIO -> "audio"
            else -> "document"
        }
    }

    /** A file name for a pick that has none with a suffix ("IMG_0042" + video/mp4 -> "IMG_0042.mp4"). */
    fun withSuffix(name: String, mime: String?): String {
        if (name.substringAfterLast('.', "").isNotEmpty() && name.contains('.')) return name
        val ext = when (mime?.lowercase()) {
            "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; "image/heic" -> "heic"; "image/heif" -> "heif"
            "video/mp4" -> "mp4"; "video/quicktime" -> "mov"; "video/webm" -> "webm"; "video/3gpp" -> "3gp"; "video/x-matroska" -> "mkv"
            "audio/mpeg" -> "mp3"; "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a"; "audio/aac" -> "aac"; "audio/wav", "audio/x-wav" -> "wav"
            "audio/ogg" -> "ogg"; "audio/opus" -> "opus"; "audio/flac" -> "flac"; "audio/amr" -> "amr"
            "application/pdf" -> "pdf"
            else -> return name
        }
        return "$name.$ext"
    }
}

/** 65.4 -> "1:05", 3723 -> "1:02:03". */
fun clipLength(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(java.util.Locale.ROOT, "%d:%02d", m, sec)
}

/** The server's previews, decoded once and kept in memory (about an eighth of what the app may use, at most 64 MB). */
object Thumbs {
    private val limit = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(64L * 1024 * 1024)
    private var used = 0L
    private val cache = LinkedHashMap<String, ImageBitmap>(16, 0.75f, true)   // least recently used first

    @Synchronized fun cached(key: String): ImageBitmap? = cache[key]

    @Synchronized private fun keep(key: String, img: ImageBitmap) {
        cache.put(key, img)?.let { used -= it.width.toLong() * it.height * 4 }
        used += img.width.toLong() * img.height * 4
        val it = cache.entries.iterator()
        while (used > limit && it.hasNext()) {
            val e = it.next()
            used -= e.value.width.toLong() * e.value.height * 4
            it.remove()
        }
    }

    suspend fun load(api: KeloApi, name: String, size: Int, version: String): ImageBitmap? {
        val key = "$name|$size|$version"
        cached(key)?.let { return it }
        val bytes = try { api.download("/api/uploads/${KeloApi.segment(name)}/thumb?size=$size") } catch (_: ApiError) { return null }
        val img = withContext(Dispatchers.Default) { runCatching { decodeImage(bytes) }.getOrNull() } ?: return null
        keep(key, img)
        return img
    }
}

internal fun Upload.version() = "$bytes-$modified"

/** What the file viewers need from the server, given by the documents page's view model: previews and the player's connection. */
interface DocumentMediaSource {
    /** A preview of [u] at [size] pixels from memory, or null. */
    fun cachedThumb(u: Upload, size: Int): ImageBitmap?

    /** A preview of [u] at [size] pixels: from memory or the server; null when there is none. */
    suspend fun thumb(u: Upload, size: Int): ImageBitmap?

    /** The signed-in connection a media player streams with. */
    fun api(): KeloApi
}

val LocalDocumentMedia = androidx.compose.runtime.staticCompositionLocalOf<DocumentMediaSource> { error("no DocumentMediaSource") }

/** A preview of [u] at [size] pixels, null while it loads or when there is none. */
@Composable
private fun rememberThumb(u: Upload, size: Int): ImageBitmap? {
    val source = LocalDocumentMedia.current
    val result by produceState(source.cachedThumb(u, size), u.name, u.version(), size) {
        if (value == null) value = source.thumb(u, size)
    }
    return result
}

/** One square of the photos-and-videos grid: the preview, and for a video a play mark and its length. */
@Composable
fun MediaTile(u: Upload, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = Kelo.colors
    val thumb = rememberThumb(u, 512)
    Box(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(c.bubble)
            .clickable(role = Role.Image, onClickLabel = u.name, onClick = onOpen),
    ) {
        if (thumb != null) {
            Image(thumb, contentDescription = u.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(if (u.kind == "video") KeloIcons.Video else KeloIcons.Image, contentDescription = u.name, tint = c.muted,
                modifier = Modifier.align(Alignment.Center).size(28.dp))
        }
        if (u.kind == "video") {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x99000000))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(KeloIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                u.duration?.let { Text(clipLength(it), color = Color.White, fontSize = 11.sp, maxLines = 1) }
            }
        }
    }
}

/** The grid: three squares a row, straight in the page's column (the page itself scrolls). */
@Composable
fun MediaGrid(items: List<Upload>, onOpen: (Upload) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { u -> MediaTile(u, onOpen = { onOpen(u) }, modifier = Modifier.weight(1f)) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The bar over a picture or a video: close, the name, save the original, delete. */
@Composable
private fun MediaBar(name: String, onClose: () -> Unit, onSave: () -> Unit, onDelete: () -> Unit) {
    val on = Kelo.colors.onMedia
    Row(Modifier.fillMaxWidth().background(Color(0x66000000)).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(KeloIcons.Close, contentDescription = string(Res.string.close), tint = on) }
        Text(name, color = on, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = onSave) { Icon(KeloIcons.ArrowDown, contentDescription = string(Res.string.docs_download), tint = on) }
        IconButton(onClick = onDelete) { Icon(KeloIcons.Trash, contentDescription = string(Res.string.delete), tint = on) }
    }
}

/** A picture on the whole screen: pinch to zoom, drag to move, double-tap to zoom in or back out. */
@Composable
fun PhotoPage(u: Upload, onClose: () -> Unit, onSave: () -> Unit, onDelete: () -> Unit, note: String) {
    val big = rememberThumb(u, 2048)
    val small = rememberThumb(u, 512)
    var scale by remember(u.name) { mutableFloatStateOf(1f) }
    var offset by remember(u.name) { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().background(Kelo.colors.media)) {
        val image = big ?: small
        if (image != null) {
            Image(
                image, contentDescription = u.name, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .pointerInput(u.name) {
                        detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f })
                    }
                    .pointerInput(u.name) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            )
        }
        if (big == null) CircularProgressIndicator(color = Kelo.colors.onMedia, modifier = Modifier.align(Alignment.Center).size(28.dp))
        Column(Modifier.fillMaxWidth().safeDrawingPadding()) {
            MediaBar(u.name, onClose, onSave, onDelete)
            if (note.isNotEmpty()) Text(note, color = Kelo.colors.onMedia, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
        }
    }
}

/**
 * A video or sound file played in the app. Streamed from the server (GET /api/uploads/{name}/file?inline=1 with the token; the
 * server answers Range requests, so skipping ahead does not fetch the whole file). The app's [LocalMediaPlayer] does the playing; an
 * app without one shows the same note as a failed play, with the save button.
 */
@Composable
fun PlayerPage(u: Upload, onClose: () -> Unit, onSave: () -> Unit, onDelete: () -> Unit, note: String) {
    val api = LocalDocumentMedia.current.api()
    val player = LocalMediaPlayer.current
    var failed by remember(u.name) { mutableStateOf(player == null) }
    val audio = u.kind == "audio"
    Box(Modifier.fillMaxSize().background(Kelo.colors.media)) {
        if (audio) {
            Icon(KeloIcons.Music, contentDescription = null, tint = Kelo.colors.onMedia.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.Center).padding(bottom = 80.dp).size(96.dp))
        }
        player?.Show(
            api, "/api/uploads/${KeloApi.segment(u.name)}/file?inline=1", audio,
            Modifier.fillMaxSize().safeDrawingPadding().padding(top = 52.dp),
            onFailed = { failed = true },
        )
        if (failed) {
            Text(string(Res.string.docs_play_failed), color = Kelo.colors.onMedia, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        }
        Column(Modifier.fillMaxWidth().safeDrawingPadding()) {
            MediaBar(u.name, onClose, onSave, onDelete)
            if (note.isNotEmpty()) Text(note, color = Kelo.colors.onMedia, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
        }
    }
}
