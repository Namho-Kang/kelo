package chat.kelo.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelChooserRulesTest {
    @Test fun theServersLabelWinsElseTheIdIsTidiedNeverShownRaw() {
        assertEquals("Claude Sonnet 5.5", ModelNames.display("claude-sonnet-5-5", "Claude Sonnet 5.5"))
        assertEquals("Claude Sonnet 5.5", ModelNames.display("claude-sonnet-5-5", null))
        assertEquals("Claude Sonnet 5.5", ModelNames.display("claude-sonnet-5-5", "claude-sonnet-5-5"))   // a label that is the id again
        assertEquals("Claude 3.5 Haiku", ModelNames.humanize("anthropic/claude-3-5-haiku-20241022"))
        assertEquals("GPT-5.1 Mini", ModelNames.humanize("gpt-5.1-mini"))
        assertEquals("GPT-4o", ModelNames.humanize("gpt-4o-2024-08-06"))
        assertEquals("Gemini 3.8 Flash", ModelNames.humanize("models/gemini-3.8-flash"))
        assertEquals("Qwen3 8B", ModelNames.humanize("qwen3:8b"))
        assertEquals("GPT-OSS 120B", ModelNames.humanize("gpt-oss-120b"))
        assertEquals("o3", ModelNames.humanize("o3"))
        assertEquals("Llama 3.1 405B", ModelNames.humanize("meta-llama/llama-3.1-405b"))
    }

    @Test fun theChipDropsALeadingClaudeOnly() {
        assertEquals("Sonnet 5.5", ModelNames.short("Claude Sonnet 5.5"))
        assertEquals("Claude", ModelNames.short("Claude"))
        assertEquals("Gemini 3.8 Flash", ModelNames.short("Gemini 3.8 Flash"))
    }

    @Test fun traitsComeFromTierWordsAndServerFactsOnly() {
        assertEquals(listOf(ModelTrait.FAST, ModelTrait.IMAGES, ModelTrait.LONG),
            ModelTraits.of("claude-haiku-5-5", ModelFacts(images = true, context = 1_000_000)))
        assertEquals(listOf(ModelTrait.LARGE, ModelTrait.IMAGES), ModelTraits.of("gemini-3.8-pro", ModelFacts(images = true, context = 200_000)))
        assertEquals(listOf(ModelTrait.REASONING), ModelTraits.of("deepseek/deepseek-r1", null))
        assertEquals(listOf(ModelTrait.FAST), ModelTraits.of("gemini-3.8-flash-pro", null))   // never both quick and big
        assertTrue(ModelTraits.of("llama3.2", ModelFacts(images = false, context = 131_072)).isEmpty())
        assertTrue(ModelTraits.of("gemini-3", null).isEmpty())   // "mini" inside "gemini" is not a word of its own
    }

    @Test fun effortIsLevelsANoteOrNothing() {
        assertEquals(EffortChoice.Levels(listOf("", "low", "medium", "high")),
            ModelChooserRules.effort(ProviderInfo("openrouter", effortLevels = listOf("low", "medium", "high"))))
        assertEquals(EffortChoice.FromModel("set by the name"), ModelChooserRules.effort(ProviderInfo("agy", effortNote = "set by the name")))
        assertEquals(EffortChoice.None, ModelChooserRules.effort(ProviderInfo("anthropic")))
        assertEquals(EffortChoice.None, ModelChooserRules.effort(null))
    }

    @Test fun theLevelsTheModelListStatesWinOverTheProviders() {
        val p = ProviderInfo("openai-codex", effortLevels = listOf("low", "medium", "high"))
        assertEquals(EffortChoice.Levels(listOf("", "low", "medium", "high", "xhigh")),
            ModelChooserRules.effort(p, ModelFacts(effortLevels = listOf("low", "medium", "high", "xhigh"))))
        assertEquals(EffortChoice.None, ModelChooserRules.effort(p, ModelFacts(effortLevels = emptyList())))   // the list says: none
        assertEquals(EffortChoice.Levels(listOf("", "low", "medium", "high")), ModelChooserRules.effort(p, ModelFacts()))   // says nothing
        // a provider with no levels of its own (Anthropic) still offers what its list states for a model
        assertEquals(EffortChoice.Levels(listOf("", "low", "max")), ModelChooserRules.effort(ProviderInfo("anthropic"), ModelFacts(effortLevels = listOf("low", "max"))))
    }

    @Test fun aCustomServerHasItsLevelsUntilItRefusesThem() {
        val custom = ProviderInfo("custom", effortLevels = listOf("low", "medium", "high", "max"))
        val levels = ModelChooserRules.effort(custom, ModelFacts())
        assertEquals(EffortChoice.Levels(listOf("", "low", "medium", "high", "max")), levels)
        // the server answered 400 to the level: no levels, the server's sentence instead (the sheet greys the row, the page says it)
        val refused = ModelChooserRules.effort(custom, ModelFacts(effortLevels = emptyList(), effortNote = "이 서버는 생각 수준 설정을 받지 않아요"))
        assertEquals(EffortChoice.Refused("이 서버는 생각 수준 설정을 받지 않아요"), refused)
        assertEquals("이 서버는 생각 수준 설정을 받지 않아요", ModelChooserRules.effortNote(refused))
        assertEquals("set by the name", ModelChooserRules.effortNote(EffortChoice.FromModel("set by the name")))
        assertEquals(null, ModelChooserRules.effortNote(levels))
        assertEquals(true, ModelChooserRules.closeAfterPick(refused))
        val facts = chat.kelo.app.core.KeloApi.json.decodeFromString<ModelList>(
            """{"provider":"custom","models":["k"],"info":{"k":{"images":false,"effort_levels":[],"effort_note":"no"}}}""").info.getValue("k")
        assertEquals(EffortChoice.Refused("no"), ModelChooserRules.effort(custom, facts))
    }

    @Test fun theShownLevelIsWhatTheServerSends() {
        val custom = EffortChoice.Levels(listOf("", "low", "medium", "high", "max"))
        assertEquals("max", ModelChooserRules.shownLevel(custom, "max"))
        assertEquals("", ModelChooserRules.shownLevel(custom, ""))
        assertEquals("high", ModelChooserRules.shownLevel(custom, "xhigh"))   // no xhigh here: the next lower one, like effort.clamp
        assertEquals("high", ModelChooserRules.shownLevel(EffortChoice.Levels(listOf("", "low", "medium", "high")), "max"))
        assertEquals("", ModelChooserRules.shownLevel(custom, "turbo"))
    }

    @Test fun aStaleListAndListedLevelsParse() {
        val l = chat.kelo.app.core.KeloApi.json.decodeFromString<ModelList>(
            """{"provider":"openrouter","models":["a"],"labels":{},"info":{"a":{"images":false,"context":null,"effort_levels":[]}},"error":null,"stale":true,"fetched_at":1700000000,"retry_in":30}""")
        assertEquals(true, l.stale)
        assertEquals(emptyList<String>(), l.info["a"]?.effortLevels)
        assertEquals(null, chat.kelo.app.core.KeloApi.json.decodeFromString<ModelList>("""{"models":["a"],"info":{"a":{}}}""").info["a"]?.effortLevels)
    }

    @Test fun sectionsAreTheCurrentProviderThenOtherReadyOnesOfficialFirst() {
        val providers = listOf(
            ProviderInfo("claude-subscription", displayName = "Claude", configured = true, unofficial = true),
            ProviderInfo("openrouter", displayName = "OpenRouter", configured = true),
            ProviderInfo("anthropic", displayName = "Anthropic", configured = true),
            ProviderInfo("gemini", displayName = "Gemini", configured = false),
            ProviderInfo("custom", displayName = "Custom", configured = null),
        )
        assertEquals(listOf("openrouter", "anthropic", "claude-subscription"), ModelChooserRules.sections("openrouter", providers))
        assertEquals(listOf("gemini", "anthropic", "openrouter", "claude-subscription"), ModelChooserRules.sections("gemini", providers))
        assertEquals(listOf("anthropic", "openrouter", "claude-subscription"), ModelChooserRules.sections("", providers))
    }

    @Test fun aModelTypedByHandStaysListedOnTop() {
        val list = ModelList("p", listOf("a", "b"))
        assertEquals(listOf("mine", "a", "b"), ModelChooserRules.rows("p", list, "p" to "mine"))
        assertEquals(listOf("a", "b"), ModelChooserRules.rows("p", list, "p" to "a"))
        assertEquals(listOf("a", "b"), ModelChooserRules.rows("p", list, "q" to "mine"))
        assertEquals(listOf("mine"), ModelChooserRules.rows("p", null, "p" to "mine"))
    }
}

class ModelNamesListTest {
    @Test fun aListStaysReadableAndToldApart() {
        val ids = listOf("gpt-4o", "gpt-4o-2024-08-06", "gpt-4o-20240513", "claude-sonnet-5-5")
        val names = ModelNames.displayAll(ids, mapOf("claude-sonnet-5-5" to "Claude Sonnet 5.5"))
        assertEquals("GPT-4o", names["gpt-4o"])
        assertEquals("GPT-4o (2024-08-06)", names["gpt-4o-2024-08-06"])
        assertEquals("GPT-4o (2024-05-13)", names["gpt-4o-20240513"])
        assertEquals("Claude Sonnet 5.5", names["claude-sonnet-5-5"])
        ids.forEach { id -> assertTrue(names.getValue(id) != id || id == "o3") }
    }

    @Test fun theSheetStopsJustUnderTheHeader() {
        assertEquals(androidx.compose.ui.unit.Dp(132f), ModelChooserRules.sheetTop(androidx.compose.ui.unit.Dp(124f)))
        assertEquals(androidx.compose.ui.unit.Dp(48f), ModelChooserRules.sheetTop(androidx.compose.ui.unit.Dp(0f)))
    }
}

class ModelListFoldTest {
    private val sections = listOf("antigravity-oauth", "openrouter")

    @Test fun theListStartsFoldedUnlessNoModelIsChosenYet() {
        assertEquals(false, ModelChooserRules.listStartsOpen("gemini-3.8-flash-high", sections))
        assertEquals(true, ModelChooserRules.listStartsOpen("", sections))
        assertEquals(true, ModelChooserRules.listStartsOpen("  ", sections))
        assertEquals(false, ModelChooserRules.listStartsOpen("", emptyList()))   // nothing to list: the "none ready" line shows instead
    }

    @Test fun pickingAModelFoldsTheListWhenAThinkingLevelIsLeftToChooseElseCloses() {
        assertEquals(false, ModelChooserRules.closeAfterPick(EffortChoice.Levels(listOf("", "low", "high"))))
        assertEquals(true, ModelChooserRules.closeAfterPick(EffortChoice.FromModel("set by the name")))   // no level row for it
        assertEquals(true, ModelChooserRules.closeAfterPick(EffortChoice.None))
    }

    @Test fun theTwoFoldingRowsOpenOneAtATime() {
        val list = ModelChooserRules.Fold.LIST
        val levels = ModelChooserRules.Fold.LEVELS
        assertEquals(true to false, ModelChooserRules.toggle(listOpen = false, levelsOpen = true, which = list))
        assertEquals(false to false, ModelChooserRules.toggle(listOpen = true, levelsOpen = false, which = list))
        assertEquals(false to true, ModelChooserRules.toggle(listOpen = true, levelsOpen = false, which = levels))
        assertEquals(false to false, ModelChooserRules.toggle(listOpen = false, levelsOpen = true, which = levels))
    }

    @Test fun foldingAnimatesUnlessMotionIsReduced() {
        assertEquals(0f, ModelChooserRules.chevronTurn(false), 0f)
        assertEquals(90f, ModelChooserRules.chevronTurn(true), 0f)
        // the grow / shrink / fade of the folding lists: tweens normally, an instant snap with reduced motion
        assertTrue(chat.kelo.app.ui.theme.Motion.enter<Float>(false) is androidx.compose.animation.core.TweenSpec)
        assertTrue(chat.kelo.app.ui.theme.Motion.exit<Float>(false) is androidx.compose.animation.core.TweenSpec)
        assertTrue(chat.kelo.app.ui.theme.Motion.enter<Float>(true) is androidx.compose.animation.core.SnapSpec)
        assertTrue(chat.kelo.app.ui.theme.Motion.exit<Float>(true) is androidx.compose.animation.core.SnapSpec)
        assertTrue(chat.kelo.app.ui.theme.Motion.standard<Float>(true) is androidx.compose.animation.core.SnapSpec)
    }
}
