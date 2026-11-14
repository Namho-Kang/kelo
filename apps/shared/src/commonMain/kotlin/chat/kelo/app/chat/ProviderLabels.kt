package chat.kelo.app.chat

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*
import androidx.compose.runtime.Composable

/** A provider as people know it, never its raw id: the app's own short name for the known ones, else the server's display name. */
object ProviderLabels {
    /** The server's display name for [id], when it has one that is not just the id again. */
    fun displayName(id: String, providers: List<ProviderInfo>): String? =
        providers.firstOrNull { it.name == id }?.displayName?.trim()?.takeIf { it.isNotEmpty() && it != id }
}

@Composable
fun providerLabel(id: String, providers: List<ProviderInfo>): String = when (ProviderConsentRules.service(id)) {
    ConsentService.CLAUDE_SUBSCRIPTION -> string(Res.string.consent_service_claude)
    ConsentService.CHATGPT_SUBSCRIPTION -> string(Res.string.consent_service_chatgpt)
    ConsentService.ANTIGRAVITY -> "Antigravity"
    ConsentService.QWEN -> "Qwen"
    ConsentService.MINIMAX -> "MiniMax"
    ConsentService.OTHER -> ProviderLabels.displayName(id, providers) ?: string(Res.string.usage_provider_generic)
}
