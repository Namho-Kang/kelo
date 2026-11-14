package chat.kelo.app.platform

/** The app's language pick (the whole app in the chosen language). */
interface AppLanguage {
    /** The saved pick: a language code or [chat.kelo.app.core.Languages.SYSTEM]; null = never picked. */
    val current: String?

    /** The language the app is shown in when following the phone, as one of [chat.kelo.app.core.Languages.ALL]. */
    val systemShownCode: String

    /** Keeps the pick, tells the server, and shows the app in that language. */
    fun pick(code: String)
}
