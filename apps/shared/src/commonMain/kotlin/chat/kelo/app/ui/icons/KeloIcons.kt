package chat.kelo.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Kelo's line icons: 24x24, stroke 1.8, round caps and joins. Rects and circles are written as paths. A few outlines follow
 * open-source icon sets (see NOTICE: Feather, MIT; Lucide, ISC). Use with Icon(KeloIcons.Chat, contentDescription) so they take
 * the current content colour.
 */
object KeloIcons {
    val Chat by lazy { stroke("chat", "M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12Z") }
    val Goal by lazy { stroke("goal", rect(4f, 4f, 16f, 16f, 3f), "m8.5 12.5 2.5 2.5 4.5-5") }
    val Plug by lazy { stroke("plug", "M9 3v5M15 3v5M6 8h12v3a6 6 0 0 1-12 0V8ZM12 17v4") }
    val User by lazy { stroke("user", "M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2", circle(12f, 7f, 4f)) }
    val Trash by lazy { stroke("trash", "M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3") }
    val Globe by lazy { stroke("globe", circle(12f, 12f, 9f), "M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18") }
    /** A place on a map (a shipping address); Feather's map-pin */
    val MapPin by lazy { stroke("map-pin", "M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0Z", circle(12f, 10f, 3f)) }
    /** A parcel (an order's items); Feather's package */
    val Package by lazy {
        stroke("package", "M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z",
            "M3.3 7 12 12l8.7-5M12 22V12")
    }
    /** A price tag (a coupon); Feather's tag */
    val Tag by lazy { stroke("tag", "M20.6 13.4 13.4 20.6a2 2 0 0 1-2.8 0L2 12V2h10l8.6 8.6a2 2 0 0 1 0 2.8Z", "M7 7h.01") }
    val ChevronRight by lazy { mirrored("chevron-right", "m9 18 6-6-6-6") }
    val ChevronLeft by lazy { mirrored("chevron-left", "m15 18-6-6 6-6") }
    val ChevronDown by lazy { stroke("chevron-down", "m6 9 6 6 6-6") }
    val Plus by lazy { stroke("plus", "M12 5v14M5 12h14") }
    val Minus by lazy { stroke("minus", "M5 12h14") }
    val Send by lazy { stroke("send", "M12 19V5M5 12l7-7 7 7") }
    val Shield by lazy { stroke("shield", "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z", "m9 12 2 2 4-4") }
    val Phone by lazy { stroke("phone", rect(6f, 2f, 12f, 20f, 3f), "M11 18h2") }
    val Key by lazy { stroke("key", circle(8f, 15f, 4f), "m11 12 9-9M16 7l3 3") }
    val Chip by lazy {
        stroke("chip", rect(5f, 5f, 14f, 14f, 2f), rect(9f, 9f, 6f, 6f, 0f),
            "M9 2v3M15 2v3M9 19v3M15 19v3M2 9h3M2 15h3M19 9h3M19 15h3")
    }
    val File by lazy { stroke("file", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5M9 13h6M9 17h6") }
    val Mic by lazy { stroke("mic", rect(9f, 2f, 6f, 12f, 3f), "M5 11a7 7 0 0 0 14 0M12 18v4") }
    val Spark by lazy { stroke("spark", "m12 3 1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9L12 3Z") }
    val Check by lazy { stroke("check", "m5 12.5 4.5 4.5L19 7.5") }
    val ArrowDown by lazy { stroke("arrow-down", "M12 5v14M5 12l7 7 7-7") }
    val Clock by lazy { stroke("clock", circle(12f, 12f, 9f), "M12 7v5l3 2") }
    val Card by lazy { stroke("card", rect(2f, 5f, 20f, 14f, 2.5f), "M2 10h20M6 15h4") }
    val Lock by lazy { stroke("lock", rect(5f, 11f, 14f, 10f, 2.5f), "M8 11V7a4 4 0 0 1 8 0v4") }
    val Receipt by lazy { stroke("receipt", "M6 3h12v18l-3-2-3 2-3-2-3 2V3Z", "M9 8h6M9 12h6") }
    val Close by lazy { stroke("close", "M6 6l12 12M18 6 6 18") }

    val Copy by lazy { stroke("copy", rect(9f, 9f, 11f, 11f, 2f), "M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1") }
    val Share by lazy { stroke("share", "M12 3v12M7 8l5-5 5 5", "M5 13v6a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-6") }
    val Retry by lazy { stroke("retry", "M3 12a9 9 0 0 1 15.5-6.2L21 8", "M21 3v5h-5", "M21 12a9 9 0 0 1-15.5 6.2L3 16", "M3 21v-5h5") }
    val Edit by lazy { stroke("edit", "M12 20h9", "M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z") }
    val Flag by lazy { stroke("flag", "M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1z", "M4 22v-7") }
    val TextSelect by lazy { stroke("text-select", "M5 4h14M12 4v16M9 20h6") }
    val Image by lazy { stroke("image", rect(3f, 3f, 18f, 18f, 2f), circle(9f, 9f, 2f), "m21 15-5-5L5 21") }
    val Play by lazy { stroke("play", "M8 5v14l11-7Z") }
    val Video by lazy { stroke("video", rect(2f, 6f, 14f, 12f, 2f), "m16 10 6-4v12l-6-4") }
    val Music by lazy { stroke("music", "M9 18V5l12-2v13", circle(6f, 18f, 3f), circle(18f, 16f, 3f)) }
    val Camera by lazy {
        stroke("camera", "M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3Z", circle(12f, 13f, 3.5f))
    }

    // work, notifications
    val Bell by lazy { stroke("bell", "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9", "M10.3 21a1.9 1.9 0 0 0 3.4 0") }
    val Pulse by lazy { stroke("pulse", "M3 12h4l3-8 4 16 3-8h4") }
    val Book by lazy { stroke("book", "M4 19.5A2.5 2.5 0 0 1 6.5 17H20V3H6.5A2.5 2.5 0 0 0 4 5.5v14Z", "M4 19.5A2.5 2.5 0 0 0 6.5 22H20v-5") }
    val Calendar by lazy { stroke("calendar", rect(3f, 5f, 18f, 16f, 2f), "M3 10h18M8 3v4M16 3v4") }
    val Hourglass by lazy { stroke("hourglass", "M6 3h12M6 21h12M7 3c0 5 10 5 10 9s-10 4-10 9M17 3c0 5-10 5-10 9s10 4 10 9") }
    val Alert by lazy { stroke("alert", circle(12f, 12f, 9f), "M12 7.5v5.5M12 16.5h.01") }

    // the live view of a browser tab
    val ArrowLeft by lazy { mirrored("arrow-left", "M19 12H5M12 19l-7-7 7-7") }
    val ArrowRight by lazy { mirrored("arrow-right", "M5 12h14M12 5l7 7-7 7") }
    val Eye by lazy { stroke("eye", "M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12Z", circle(12f, 12f, 3f)) }
    val Hand by lazy {
        stroke("hand", "M8 13V5.5a1.5 1.5 0 0 1 3 0V12", "M11 11.5v-7a1.5 1.5 0 0 1 3 0V12", "M14 11.5V6.5a1.5 1.5 0 0 1 3 0V13",
            "M17 9.5a1.5 1.5 0 0 1 3 0V15a7 7 0 0 1-7 7h-1a7 7 0 0 1-5.6-2.8L3.6 15.6a1.6 1.6 0 0 1 2.5-2L8 15.5")
    }

    // the work tab, overflow menus, explanations
    val Work by lazy { stroke("work", rect(3f, 7f, 18f, 13f, 2f), "M9 7V5.5A1.5 1.5 0 0 1 10.5 4h3A1.5 1.5 0 0 1 15 5.5V7", "M3 13h18") }
    val Info by lazy { stroke("info", circle(12f, 12f, 9f), "M12 11v5.5M12 7.5h.01") }
    val Stop by lazy { stroke("stop", rect(6f, 6f, 12f, 12f, 2f)) }
    val Cloud by lazy { stroke("cloud", "M7 18a4.5 4.5 0 0 1-.6-9 6 6 0 0 1 11.5 1.5A3.8 3.8 0 0 1 17.5 18H7Z") }

    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float): String =
        if (r == 0f) "M$x ${y}h${w}v${h}h${-w}Z"
        else "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}h${-(w - 2 * r)}" +
            "a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}Z"

    private fun circle(cx: Float, cy: Float, r: Float): String = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0Z"

    /** A direction icon (back, forward, next): drawn the other way round in right-to-left languages such as Arabic. */
    private fun mirrored(name: String, vararg paths: String): ImageVector = stroke(name, *paths, autoMirror = true)

    private fun stroke(name: String, vararg paths: String, autoMirror: Boolean = false): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
            autoMirror = autoMirror)
            .apply {
                paths.forEach { d ->
                    addPath(
                        pathData = addPathNodes(d),
                        fill = null,
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = 1.8f,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }.build()
}
