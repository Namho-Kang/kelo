package chat.kelo.app.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf

/** What the live view needs to know about the connection. */
fun interface NetworkInfo {
    /** Whether the current network is metered (mobile data): the live view then asks for smaller pictures. */
    fun isMetered(): Boolean

    companion object {
        /** A desktop's connection is not treated as metered. */
        val Unmetered: NetworkInfo = NetworkInfo { false }
    }
}

/** The app root provides the phone's own (ConnectivityManager); the default is unmetered. */
val LocalNetworkInfo = staticCompositionLocalOf { NetworkInfo.Unmetered }
