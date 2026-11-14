package chat.kelo.app.my

import org.junit.Assert.*
import org.junit.Test

class UsageLimitPartsTest {
    private fun sub(role: String, windows: List<UsageWindow> = listOf(UsageWindow(label = "Session", usedPercent = 10)), error: String? = null) =
        Subscription(provider = "test", role = role, windows = windows, error = error)
    private fun account(role: String) = ProviderAccount(provider = "test", role = role)

    @Test fun mainAndBackgroundSubscription() {
        val u = Usage(subscriptions = listOf(sub("main"), sub("background")))
        val p = UsageLogic.limitParts(u)
        assertEquals(sub("main"), p.current)
        assertEquals(1, p.others.size)
        assertEquals("background", p.others[0].role)
        assertNull(p.currentAccount)
        assertTrue(p.otherAccounts.isEmpty())
        assertEquals(1, p.otherCount)
    }

    @Test fun onlyApiKeyAccount() {
        val a = account("main")
        val u = Usage(accounts = listOf(a))
        val p = UsageLogic.limitParts(u)
        assertNull(p.current)
        assertEquals(a, p.currentAccount)
        assertTrue(p.others.isEmpty())
        assertTrue(p.otherAccounts.isEmpty())
    }

    @Test fun olderServerOnlySubscription() {
        val s = Subscription(provider = "claude", windows = listOf(UsageWindow(label = "Session", usedPercent = 50)))
        val u = Usage(subscription = s)
        val p = UsageLogic.limitParts(u)
        assertNotNull(p.current)
        assertEquals("main", p.current!!.role)
        assertEquals(s.windows, p.current!!.windows)
        assertTrue(p.others.isEmpty())
    }

    @Test fun subscriptionWithNoWindowsAndNoErrorIsDropped() {
        val u = Usage(subscriptions = listOf(
            sub("main"),
            Subscription(provider = "other", role = "background", windows = emptyList(), error = null)
        ))
        val p = UsageLogic.limitParts(u)
        assertEquals(sub("main"), p.current)
        assertTrue(p.others.isEmpty())
        assertEquals(0, p.otherCount)
    }
}
