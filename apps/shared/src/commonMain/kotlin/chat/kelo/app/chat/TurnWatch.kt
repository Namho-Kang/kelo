package chat.kelo.app.chat

/** What the chat tells about the reply it follows (a fake in tests). */
interface TurnWatch {
    fun started()
    /** Called right after [started] with the way to stop this reply (the chat's Stop: DELETE /api/turns/{id}); a notification's or
     *  the tray's Stop uses it. */
    fun stopWith(stop: () -> Unit) {}
    fun event(ev: TurnEvent)
    /** [ok]: the reply ended normally; false: it failed. A reply the user stopped calls nothing. */
    fun finished(ok: Boolean)
    /** The user stopped the reply, or the chat left it */
    fun cancelled() {}
}
