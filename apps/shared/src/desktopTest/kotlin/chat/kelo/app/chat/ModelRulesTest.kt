package chat.kelo.app.chat

import chat.kelo.app.core.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRulesTest {
    @Test fun fallbackOnTheSameProviderShowsEmpty() {
        assertEquals(ModelChoice.NONE, ModelRules.initialFallback(Profile(provider = "a", model = "m", fallbackProvider = "a", fallbackModel = "n")))
        assertEquals(ModelChoice("b", "n"), ModelRules.initialFallback(Profile(provider = "a", model = "m", fallbackProvider = "b", fallbackModel = "n")))
    }

    @Test fun choosingTheFallbacksProviderAsMainEmptiesIt() {
        assertEquals(ModelChoice.NONE to true, ModelRules.afterMainChange(ModelChoice("b", ""), ModelChoice("b", "n")))
        assertEquals(ModelChoice("b", "n") to false, ModelRules.afterMainChange(ModelChoice("a", ""), ModelChoice("b", "n")))
        assertEquals(ModelChoice.NONE to false, ModelRules.afterMainChange(ModelChoice("", ""), ModelChoice.NONE))
    }

    @Test fun aChosenProviderNeedsAModel() {
        assertTrue(ModelRules.complete(ModelChoice.NONE))
        assertFalse(ModelRules.complete(ModelChoice("a", " ")))
        assertTrue(ModelRules.complete(ModelChoice("a", "m")))
    }
}
