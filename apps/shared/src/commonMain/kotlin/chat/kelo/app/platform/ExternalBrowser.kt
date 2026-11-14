package chat.kelo.app.platform

/**
 * Opens a service's own web page (a sign-in, a verification, its settings) in the owner's browser. When the owner comes back to the
 * app the page that sent them rereads its state ([chat.kelo.app.ui.platform.LocalReloadOnReturn]). Each app has one implementation.
 */
fun interface ExternalBrowser {
    /** False when nothing could open [url]. */
    fun open(url: String): Boolean
}
