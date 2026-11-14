package chat.kelo.app.chat

/**
 * What the chat's state layer needs from the app around it, which only the app can do (each app provides one; the defaults suit tests).
 */
interface ChatPlatform {
    /** A steady clock in milliseconds (the phone's time since boot) the header's activity line counts quiet time with. */
    fun uptimeMs(): Long = System.nanoTime() / 1_000_000

    /** An approval was answered "allow": a text, call or calendar change may now wait for this phone (the phone starts that work). */
    fun afterApproval() {}

    /** A line for the developer's log (Android: Log.w). */
    fun log(tag: String, message: String, error: Throwable? = null) {}

    companion object {
        val Default: ChatPlatform = object : ChatPlatform {}
    }
}
