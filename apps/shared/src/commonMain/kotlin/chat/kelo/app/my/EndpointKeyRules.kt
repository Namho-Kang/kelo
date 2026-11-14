package chat.kelo.app.my


/** Checks the address of an OpenAI-compatible server before it is sent (the server checks again). */
object EndpointRules {
    enum class Problem { EMPTY, SPACES, SCHEME, HOST }

    /** Null when [url] looks usable: http or https, a host, no spaces. */
    fun check(url: String): Problem? {
        val t = url.trim()
        if (t.isEmpty()) return Problem.EMPTY
        if (t.any { it.isWhitespace() }) return Problem.SPACES
        val scheme = t.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return Problem.SCHEME
        val host = runCatching { java.net.URI(t).host }.getOrNull()
            ?: t.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').substringBefore(':')
        return if (host.isBlank()) Problem.HOST else null
    }
}

/** Which API key box a provider gets, from where its key comes (ProviderDetail.keySource). */
object KeyRules {
    enum class State { IN_APP, ENV, NONE }

    fun state(keySource: String?): State = when (keySource) {
        "app" -> State.IN_APP
        "env" -> State.ENV
        else -> State.NONE
    }
}

