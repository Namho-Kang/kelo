package chat.kelo.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import chat.kelo.app.core.KeloApi

/**
 * Plays a video or a sound streamed from the server ([path] on [api], with its token and its one HTTP client, so Range requests and
 * the end-to-end layer work); [onFailed] when it cannot. It pauses when the app goes to the background. Null in [LocalMediaPlayer] =
 * the app cannot play media inside its window.
 */
fun interface MediaPlayerView {
    @Composable fun Show(api: KeloApi, path: String, audio: Boolean, modifier: Modifier, onFailed: () -> Unit)
}

val LocalMediaPlayer = staticCompositionLocalOf<MediaPlayerView?> { null }
