package chat.kelo.app.wallet

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What was typed on 카드 등록 so far. Lives only in this process's memory (never in saved state, never on disk, never logged:
 * [toString] leaves the fields out).
 */
data class AddCardDraft(
    val step: AddStep = AddStep.NUMBER,
    val number: String = "",
    val expiry: String = "",
    val cvc: String = "",
    val holder: String = "",
    val postal: String = "",
    val agreed: Boolean = false,
    /** The owner's name for the card ("생활비 카드"): its title everywhere; empty = brand and last 4 */
    val nickname: String = "",
) {
    fun isEmpty(): Boolean = step == AddStep.NUMBER && number.isEmpty() && expiry.isEmpty() && cvc.isEmpty() &&
        holder.isEmpty() && postal.isEmpty() && !agreed && nickname.isEmpty()

    override fun toString(): String = "AddCardDraft(step=$step)"
}

/** The number and CVC typed again for a saved card ([cardId]); memory only, like [AddCardDraft]. */
data class ReenterDraft(val cardId: String, val autoPay: Boolean, val number: String = "", val cvc: String = "") {
    override fun toString(): String = "ReenterDraft(card=$cardId, autoPay=$autoPay)"
}

/**
 * Where the owner was on the card pages (which page, and what was typed), so that the lock that comes with leaving the app does
 * not throw the work away: after the owner's check the same page opens with the same fields. It is dropped when the owner leaves
 * the flow (back to the card list, out of the card pages, or saved), and [limitMs] after the last edit or touch: a place older than
 * that is gone. Times are milliseconds from a monotonic clock.
 */
internal class WalletPlace(private val limitMs: Long = LIMIT_MS) {
    private var view: WalletView = WalletView.List
    private var add: AddCardDraft? = null
    private var reenter: ReenterDraft? = null
    private var shipping: ShippingEditDraft? = null
    private var touchedAt: Long? = null

    /** The page to show when the card pages open (the list when there is no place to come back to). */
    fun view(now: Long): WalletView { expireIfDue(now); return view }

    /** The owner moved to [v]. Going back to the list ends any flow and forgets what was typed. */
    fun go(v: WalletView, now: Long) {
        expireIfDue(now)
        if (v == WalletView.List) { clear(); return }
        view = v
        touchedAt = now
        if (v != WalletView.Add) add = null
        if (v !is WalletView.Reenter || reenter?.let { it.cardId != v.id || it.autoPay != v.autoPay } == true) reenter = null
        if (v !is WalletView.ShippingEdit || shipping?.let { it.profileId != v.id } == true) shipping = null
    }

    /** What was typed on the address page for [profileId] (null: a new address), while that page is the one shown. */
    fun shipping(profileId: String?, now: Long): ShippingEditDraft? {
        expireIfDue(now)
        val v = view as? WalletView.ShippingEdit ?: return null
        if (v.id != profileId) return null
        return shipping?.takeIf { it.profileId == profileId }
    }

    /** Keeps [d] while its address page is shown; null (saved or deleted) forgets it. Untouched pages keep nothing. */
    fun saveShipping(d: ShippingEditDraft?, now: Long) {
        expireIfDue(now)
        if (d == null) { shipping = null; return }
        val v = view as? WalletView.ShippingEdit ?: return
        if (v.id != d.profileId) return
        shipping = d.takeIf { it.touched }
        touchedAt = now
    }

    fun add(now: Long): AddCardDraft? { expireIfDue(now); return add.takeIf { view == WalletView.Add } }

    /** Keeps [d] while 카드 등록 is the page shown (ignored otherwise: the flow was left or finished). */
    fun saveAdd(d: AddCardDraft, now: Long) {
        expireIfDue(now)
        if (view != WalletView.Add) return
        add = d.takeUnless { it.isEmpty() }
        touchedAt = now
    }

    fun reenter(cardId: String, autoPay: Boolean, now: Long): ReenterDraft? {
        expireIfDue(now)
        val v = view as? WalletView.Reenter ?: return null
        if (v.id != cardId || v.autoPay != autoPay) return null
        return reenter?.takeIf { it.cardId == cardId && it.autoPay == autoPay }
    }

    fun saveReenter(d: ReenterDraft, now: Long) {
        expireIfDue(now)
        val v = view as? WalletView.Reenter ?: return
        if (v.id != d.cardId || v.autoPay != d.autoPay) return
        reenter = d.takeUnless { it.number.isEmpty() && it.cvc.isEmpty() }
        touchedAt = now
    }

    /** The owner did something on an open page: the time limit starts again. */
    fun touch(now: Long) { if (!expireIfDue(now) && touchedAt != null) touchedAt = now }

    /** Something typed is being kept (the locked page says so). */
    fun hasTyping(now: Long): Boolean { expireIfDue(now); return add != null || reenter != null || shipping != null }

    fun clear() {
        view = WalletView.List
        add = null
        reenter = null
        shipping = null
        touchedAt = null
    }

    /** Forgets the place once it is [limitMs] old (or the clock went backwards); true when it did. */
    fun expireIfDue(now: Long): Boolean {
        val at = touchedAt ?: return false
        if (now - at >= limitMs || now < at) { clear(); return true }
        return false
    }

    /** When the place runs out (null when there is none). */
    fun expiresAt(): Long? = touchedAt?.plus(limitMs)

    companion object {
        /** Ten minutes: long enough to fetch a card from a bag or answer a message, short enough not to linger. */
        const val LIMIT_MS = 10 * 60 * 1000L
    }
}

/**
 * The one [WalletPlace] of this app process (the app's container keeps it, [clock] is a monotonic millisecond clock). A timer on
 * [scope] forgets it when it runs out, so nothing typed lingers in memory past the limit even when the card pages are never opened
 * again. Process death forgets it too (nothing is written down).
 */
class WalletPlaces(private val scope: CoroutineScope, private val clock: () -> Long) {
    private val place = WalletPlace()
    private var expiry: Job? = null

    private fun now() = clock()

    /** The monotonic clock the card pages' lock and place limits read. */
    internal fun nowMs(): Long = now()

    private fun schedule() {
        expiry?.cancel()
        val at = place.expiresAt() ?: return
        expiry = scope.launch {
            delay((at - now()).coerceAtLeast(0) + 500)
            place.expireIfDue(now())
            schedule()
        }
    }

    fun view(): WalletView = place.view(now())
    fun go(v: WalletView) { place.go(v, now()); schedule() }
    internal fun add(): AddCardDraft? = place.add(now())
    internal fun saveAdd(d: AddCardDraft) { place.saveAdd(d, now()); schedule() }
    internal fun reenter(cardId: String, autoPay: Boolean): ReenterDraft? = place.reenter(cardId, autoPay, now())
    internal fun saveReenter(d: ReenterDraft) { place.saveReenter(d, now()); schedule() }
    internal fun shipping(profileId: String?): ShippingEditDraft? = place.shipping(profileId, now())
    internal fun saveShipping(d: ShippingEditDraft?) { place.saveShipping(d, now()); schedule() }
    fun touch() { place.touch(now()); schedule() }
    fun hasTyping(): Boolean = place.hasTyping(now())
    fun clear() { place.clear(); schedule() }
}

/** Where opening the card pages is. */
enum class LockPhase {
    /** Locked; the phone's check comes up by itself when the page is in front, if [UnlockFlow] says so */
    LOCKED,
    /** The phone's check is on screen */
    PROMPTING,
    /** The check went away without opening (backed out of, or an error): the page waits for the unlock button */
    NEEDS_TAP,
    /** The phone has no screen lock, so the card pages cannot open */
    NO_LOCK,
    OPEN,
}

/**
 * The card pages' lock, as a state machine. The check comes up by itself once each time the page comes back in front locked
 * (first opened, back from another app or the home screen, after the idle timeout), never again after the owner backed out of it
 * (no loop): then the unlock button asks again. A check that the system took away because the app left the screen counts as
 * coming back, not as backing out.
 */
class UnlockFlow(private val credentialLeavesApp: Boolean = false) {
    var phase: LockPhase = LockPhase.LOCKED
        private set
    private var autoPending = true
    private var stoppedWhilePrompting = false

    val isOpen: Boolean get() = phase == LockPhase.OPEN

    /** The page is in front (resumed): true when the check should come up now. */
    fun resumed(): Boolean {
        if (phase == LockPhase.PROMPTING && stoppedWhilePrompting && !credentialLeavesApp) {
            // the system took the check away with the app and never said so: ask again, once
            stoppedWhilePrompting = false
            return true
        }
        if (phase == LockPhase.LOCKED && autoPending) {
            autoPending = false
            phase = LockPhase.PROMPTING
            stoppedWhilePrompting = false
            return true
        }
        return false
    }

    /** The unlock button: true when the check should come up now. */
    fun tap(): Boolean {
        if (phase == LockPhase.OPEN) return false
        autoPending = false
        phase = LockPhase.PROMPTING
        stoppedWhilePrompting = false
        return true
    }

    /**
     * The app left the screen. An open page locks; a locked one asks by itself again on return. While the check is up, nothing
     * changes yet: the check's own answer (or the system cancelling it) decides.
     */
    fun backgrounded() {
        when (phase) {
            LockPhase.PROMPTING -> stoppedWhilePrompting = true
            else -> { phase = LockPhase.LOCKED; autoPending = true }
        }
    }

    /** No touch for the idle limit while open. */
    fun timedOut() {
        if (phase == LockPhase.OPEN) { phase = LockPhase.LOCKED; autoPending = true }
    }

    fun opened() { phase = LockPhase.OPEN; autoPending = false; stoppedWhilePrompting = false }

    fun noLock() { phase = LockPhase.NO_LOCK; autoPending = false }

    /**
     * The check went away without opening. With [credentialLeavesApp] (the screen-lock check is a separate screen, Android 9 and
     * older) the app leaving the screen during the check says nothing about why it ended: it counts as backing out.
     */
    fun notOpened() {
        if (phase != LockPhase.PROMPTING) return
        if (stoppedWhilePrompting && !credentialLeavesApp) {
            phase = LockPhase.LOCKED
            autoPending = true
        } else {
            phase = LockPhase.NEEDS_TAP
        }
        stoppedWhilePrompting = false
    }
}
