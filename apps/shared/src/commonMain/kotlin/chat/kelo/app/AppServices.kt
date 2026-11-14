package chat.kelo.app

import androidx.compose.runtime.staticCompositionLocalOf
import chat.kelo.app.chat.AgentActivity
import chat.kelo.app.core.Sessions
import chat.kelo.app.notify.OpenTarget
import chat.kelo.app.agent.AgentAvatar
import chat.kelo.app.connectors.ConnectorIcons
import chat.kelo.app.platform.*
import chat.kelo.app.wallet.CardKeys
import chat.kelo.app.wallet.CardScanner
import chat.kelo.app.wallet.WalletConsent
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What the shared screens need from the app's long-lived objects. Each app's container implements it and its root provides it with
 * [LocalAppServices]. It grows phase by phase, only when a moved screen needs a member (the full container may become one interface later).
 */
interface AppServices {
    val sessions: Sessions

    /** The app's release number ("0.9.0"), sent along with a problem report. */
    val appVersion: String

    /** The commit the app's source came from (40 lowercase hex; "" = the build did not say) and whether files differed from it; see [chat.kelo.app.core.SourceLink]. */
    val sourceCommit: String
    val sourceDirty: Boolean

    /** What the agent is doing now (the header's line, the character's mood); the chat feeds it. */
    val agentActivity: AgentActivity

    /** A screen another part of the app asked to be opened (a notification, a card): the shell takes it. */
    val openRequests: MutableStateFlow<OpenTarget?>

    /** A message the chat should scroll to (a follow-up notification, a briefing row), by its id. */
    val chatFocus: MutableStateFlow<Long?>

    /** A secure entry's card the chat should scroll to and mark, by the request id. */
    val chatEntryFocus: MutableStateFlow<String?>

    /** A reply started outside the chat (an offer card on the Work page): the chat follows it, then sets this back to null. */
    val chatFollowTurn: MutableStateFlow<String?>

    // ---- what the My and wallet pages need from the device (one implementation per app; see chat.kelo.app.platform)

    /** The agent's picture. */
    val agentAvatar: AgentAvatar

    /** A My sub page a tapped notification asked for (e.g. the wallet), waiting for the My tab. */
    val myPageRequests: MutableStateFlow<String?>

    val filePicker: FilePicker
    val systemSettings: SystemSettings

    /** Null = this device has no such switch / feature; the rows that need it are not shown. */
    val notifications: PhoneNotifications?
    val phoneLocation: PhoneLocation?
    val appUpdates: AppUpdateSource?
    val language: AppLanguage

    /** The device's card key ([CardKeys.None]: this device never holds a card) and its optional card scanner. */
    val cardKeys: CardKeys
    val cardScanner: CardScanner?

    /** This device's copy of the Keyguard key ([chat.kelo.app.keyguard.KeyguardKeys.None]: the device never holds one). */
    val keyguardKeys: chat.kelo.app.keyguard.KeyguardKeys

    // ---- what the connectors and the chat's connect card need

    /** The owner's browser, for a service's own sign-in page. */
    val browser: ExternalBrowser

    /** What this phone shares itself (calendar, contacts, ...), null = this device has none. */
    val phoneData: PhoneDataSection?

    /** The services' app icons (decoded in memory, kept in this app's cache folder). */
    val connectorIcons: ConnectorIcons

    /** What the owner accepted about the card notices (kept on this device only). */
    val walletConsent: WalletConsent

    /** What was typed on the card pages (kept through their lock, memory only) and a card number a held-back message handed over. */
    val walletPlaces: chat.kelo.app.wallet.WalletPlaces
    val secretHandoff: chat.kelo.app.chat.SecretHandoff

    // ---- signing this device in (the connect screen)

    /** Pairs this device with a server (a link's code, or an access key); each server's pinned key is used. */
    val pairing: chat.kelo.app.core.PairingClient

    /** What this device is called in 마이 → 내 기기, e.g. "Pixel 8 · Kelo". */
    fun deviceName(): String

    /** Plain http to this computer or the local network is accepted (a debug build, the desktop); a release phone needs https. */
    val allowLocalHttp: Boolean
}

val LocalAppServices = staticCompositionLocalOf<AppServices> { error("no AppServices") }
