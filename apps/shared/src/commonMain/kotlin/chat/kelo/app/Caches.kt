package chat.kelo.app

import chat.kelo.app.chat.ChatHistoryCache
import chat.kelo.app.chat.ProviderInfo
import chat.kelo.app.chat.StoredMessage
import chat.kelo.app.connectors.Connector
import chat.kelo.app.core.CacheSlot
import chat.kelo.app.core.Goal
import chat.kelo.app.core.Profile
import chat.kelo.app.my.MyOverview
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** What each screen keeps between launches (see [chat.kelo.app.core.ScreenCache]). */
object Caches {
    /** The profile the app gate needs before the main screens can show */
    val PROFILE = CacheSlot("profile", Profile.serializer())
    /** The newest chat messages (at most [ChatHistoryCache.MAX_MESSAGES]) */
    val MESSAGES = CacheSlot("messages", ListSerializer(StoredMessage.serializer()), ChatHistoryCache::trim)
    /** The My page's overview numbers */
    val MY = CacheSlot("my", MyOverview.serializer())
    val CONNECTORS = CacheSlot("connectors", ListSerializer(Connector.serializer()))
    val GOALS = CacheSlot("goals", ListSerializer(Goal.serializer()))
    /** The providers the chat's model and effort chips need */
    val PROVIDERS = CacheSlot("providers", ListSerializer(ProviderInfo.serializer()))
    /** The providers as 마이 → 모델 shows them (its provider list shows at once on the next open; see [chat.kelo.app.chat.ModelCatalog]) */
    val PROVIDER_DETAILS = CacheSlot("provider_details", ListSerializer(chat.kelo.app.my.ProviderDetail.serializer()))
    /** The last good model list (labels and facts too) of each provider, newest first */
    val MODEL_LISTS = CacheSlot("model_lists", MapSerializer(String.serializer(), chat.kelo.app.chat.ModelList.serializer()))

    /** The agent's picture (read when the header first shows, not at start-up) */
    val AVATAR = CacheSlot("avatar", chat.kelo.app.agent.AvatarBlob.serializer())

    /** Read into memory at start-up, while the splash screen shows */
    val ALL: List<CacheSlot<*>> = listOf(PROFILE, MESSAGES, PROVIDERS, MODEL_LISTS, MY, CONNECTORS, GOALS, PROVIDER_DETAILS)
}
