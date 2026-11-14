package chat.kelo.app.chat

import chat.kelo.app.Caches
import chat.kelo.app.core.KeloApi
import chat.kelo.app.core.Sessions
import chat.kelo.app.my.ProviderDetail

/**
 * The last good provider list and model lists (their labels and facts too), kept per server on the phone (encrypted, see
 * [chat.kelo.app.core.ScreenCache]) so the chat's model chip, the model chooser and 마이 → 모델 show them at once on the next open,
 * then refresh them in the background and change in place. Only a list that worked is kept: a failed fetch never replaces it.
 * Before anything was ever fetched, names come from [ModelNames.humanize], which writes them as the server does.
 */
object ModelCatalog {
    /** A refresh this slow (ms) shows a small "loading" hint next to what is shown; a quicker one shows nothing */
    const val SLOW_MS = 1000L
    /** The most providers whose model lists are kept (the newest fetched first) */
    const val MAX_PROVIDERS = 16

    /** A list worth keeping: it has models and no error. */
    fun keepable(l: ModelList): Boolean = l.provider.isNotEmpty() && l.error == null && l.models.isNotEmpty()

    /** The kept lists once [l] arrived: a good list goes first and replaces that provider's; a failed one changes nothing. */
    fun merge(kept: Map<String, ModelList>, l: ModelList): Map<String, ModelList> {
        if (!keepable(l)) return kept
        val out = LinkedHashMap<String, ModelList>()
        out[l.provider] = l
        for ((k, v) in kept) if (k != l.provider && out.size < MAX_PROVIDERS) out[k] = v
        return out
    }

    /**
     * What a screen shows for one provider: a good fresh list; else, when the fetch failed or has not answered yet, the kept one
     * (marked stale after a failure); else the fresh answer as it is (its error, or nothing yet).
     */
    fun shown(fresh: ModelList?, kept: ModelList?): ModelList? = when {
        fresh != null && keepable(fresh) -> fresh
        kept != null && fresh == null -> kept
        kept != null && fresh != null && fresh.error != null -> kept.copy(stale = true)
        else -> fresh
    }

    /** One model's name: the fresh list's label, else the kept list's, else the shared rule. */
    fun name(id: String, fresh: ModelList?, kept: ModelList?): String =
        ModelNames.display(id, fresh?.labels?.get(id)?.takeIf { it.isNotBlank() } ?: kept?.labels?.get(id))

    /** While nothing is known yet for [provider]: the chosen model alone, so a field shows its name (never the raw id, never empty). */
    fun placeholder(provider: String, model: String): ModelList? =
        if (provider.isNotEmpty() && model.isNotEmpty()) ModelList(provider, listOf(model)) else null

    // ---- kept on the phone

    fun keptList(sessions: Sessions, provider: String): ModelList? =
        if (provider.isEmpty()) null else runCatching { sessions.cached(Caches.MODEL_LISTS)?.get(provider) }.getOrNull()

    fun keptLists(sessions: Sessions): Map<String, ModelList> = runCatching { sessions.cached(Caches.MODEL_LISTS) }.getOrNull().orEmpty()

    fun keepList(sessions: Sessions, api: KeloApi, l: ModelList) {
        // kept without the server's stale mark: shown on a later open, it is just the last list, refreshed right after
        if (keepable(l)) runCatching { sessions.keep(api, Caches.MODEL_LISTS, merge(keptLists(sessions), l.copy(stale = false))) }
    }

    fun keptProviders(sessions: Sessions): List<ProviderDetail> = runCatching { sessions.cached(Caches.PROVIDER_DETAILS) }.getOrNull().orEmpty()

    fun keptProviderInfos(sessions: Sessions): List<ProviderInfo> =
        runCatching { sessions.cached(Caches.PROVIDERS) }.getOrNull() ?: keptProviders(sessions).map { it.asInfo() }

    /** GET /api/models?provider= (and keeps a good answer). Throws what the call throws. */
    suspend fun fetch(sessions: Sessions, provider: String, refresh: Boolean = false): ModelList {
        val api = sessions.requireApi()
        val l = api.get<ModelList>("/api/models?provider=${KeloApi.segment(provider)}${if (refresh) "&refresh=true" else ""}")
        keepList(sessions, api, l)
        return l
    }

    /** GET /api/providers as the model page reads it (and keeps it, also in the chat's shorter form). Throws what the call throws. */
    suspend fun fetchProviders(sessions: Sessions): List<ProviderDetail> {
        val api = sessions.requireApi()
        val rows = api.get<List<ProviderDetail>>("/api/providers")
        sessions.keep(api, Caches.PROVIDER_DETAILS, rows)
        sessions.keep(api, Caches.PROVIDERS, rows.map { it.asInfo() })
        return rows
    }

    /** The chat's shorter form of GET /api/providers (kept as the chat reads it). Throws what the call throws. */
    suspend fun fetchProviderInfos(sessions: Sessions): List<ProviderInfo> {
        val api = sessions.requireApi()
        val rows = api.get<List<ProviderInfo>>("/api/providers")
        sessions.keep(api, Caches.PROVIDERS, rows)
        return rows
    }
}
