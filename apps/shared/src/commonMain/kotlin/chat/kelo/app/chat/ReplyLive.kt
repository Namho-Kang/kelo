package chat.kelo.app.chat

/** The live line of a reply that runs while the app is away (backlog 64): the step it is on, the site, and its newest browser picture
 *  (a data: URL). [imageAt]: when that picture was taken in (ms), for the rate limit. */
data class ReplyLive(val step: String = "", val site: String = "", val image: String? = null, val imageAt: Long = 0L)

object ReplyLiveRules {
    /** A new picture at most this often (the server sends about one per 2 s already; this keeps the notification calm). */
    const val PICTURE_EVERY_MS = 2_000L

    /** The live line after [ev], or null when nothing it shows changed (or a new picture came too soon). */
    fun next(prev: ReplyLive, ev: TurnEvent, nowMs: Long): ReplyLive? = when (ev) {
        is TurnEvent.ToolStarted -> ev.label.trim().takeIf { !ev.hidden && it.isNotEmpty() && it != prev.step }?.let { prev.copy(step = it) }
        is TurnEvent.Browser -> when {
            ev.image == null -> ev.host.takeIf { it.isNotEmpty() && it != prev.site }?.let { prev.copy(site = it) }
            prev.image != null && nowMs - prev.imageAt < PICTURE_EVERY_MS -> null
            else -> prev.copy(image = ev.image, imageAt = nowMs, site = ev.host.ifEmpty { prev.site })
        }
        else -> null
    }

    /** One short line for the desktop tray: the step, else the site, else null (nothing to show yet). */
    fun trayLine(s: ReplyLive): String? = s.step.ifEmpty { s.site }.ifEmpty { null }
}
