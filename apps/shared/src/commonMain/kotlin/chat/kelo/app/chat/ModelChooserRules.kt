package chat.kelo.app.chat

import androidx.compose.ui.unit.dp

/**
 * A model as people read it: the server's label when it has one, else the id tidied up. Never the raw id.
 *
 * [humanize] is the server's own rule (assistant.models.model_label), so a name made here before the first list arrives reads
 * exactly like the server's label that replaces it: "gemini-3.8-flash-medium" -> "Gemini 3.8 Flash (Medium)", "sonnet" ->
 * "Sonnet (latest)", "gpt-oss-120b" -> "GPT-OSS 120B". tests/fixtures/model_labels.json (repo root) holds the cases every copy
 * (server, Android, desktop, iOS) is tested against.
 */
object ModelNames {
    private val ACRONYMS = setOf("gpt", "glm", "oss", "ai", "vl", "moe", "api")
    private val DATE_TAIL = Regex("[-_](20[0-9]{6}|20[0-9]{2}-[0-9]{2}-[0-9]{2})$")
    private val SIZE = Regex("^[0-9]+(\\.[0-9]+)?[bkmtBKMT]$")
    private val OPENAI_O = Regex("^o[0-9]+$")
    /** The claude CLI's names for the newest model of each family */
    private val LATEST_ALIASES = setOf("sonnet", "opus", "haiku")
    /** A thinking level written at the end of the id: shown in brackets, as the CLIs' own labels do */
    private val TAIL_WORDS = setOf("minimal", "low", "medium", "high", "xhigh", "max", "thinking")

    fun display(id: String, label: String?): String {
        val l = label?.trim().orEmpty()
        return if (l.isNotEmpty() && l != id) l else humanize(id)
    }

    /**
     * Readable names for a whole list, still told apart: two ids that read the same once tidied (gpt-4o and its dated
     * snapshot) keep their date, "GPT-4o (2024-08-06)"; anything still equal falls back to the server's label or the id's tail.
     */
    fun displayAll(ids: List<String>, labels: Map<String, String>): Map<String, String> {
        val first = ids.associateWith { display(it, labels[it]) }
        val clash = first.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return first.mapValues { (id, name) ->
            if (name !in clash) name
            else DATE_TAIL.find(id.trim().substringAfterLast('/'))?.let { "$name (${dashed(it.groupValues[1])})" } ?: name
        }
    }

    private fun dashed(d: String): String = if (d.length == 8 && d.all(::digit)) "${d.take(4)}-${d.substring(4, 6)}-${d.takeLast(2)}" else d

    private fun digit(c: Char) = c in '0'..'9'

    /** "claude-sonnet-5-5" -> "Claude Sonnet 5.5", "anthropic/claude-3-5-haiku-20241022" -> "Claude 3.5 Haiku", "qwen3:8b" -> "Qwen3 8B". */
    fun humanize(id: String): String {
        var s = id.trim().removePrefix("models/").substringAfterLast('/')
        s = s.replace(DATE_TAIL, "")
        if (s.lowercase() in LATEST_ALIASES) return s.lowercase().replaceFirstChar { it.uppercaseChar() } + " (latest)"
        val parts = s.split('-', '_', ' ', ':').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return id.trim()
        val merged = mutableListOf<String>()
        for (p in parts) {
            val prev = merged.lastOrNull()
            // a version written with dashes ("5-5", "3-5"): one or two digits right after a short number
            if (prev != null && p.length <= 2 && p.all(::digit) && prev.isNotEmpty() && prev.all { digit(it) || it == '.' } && prev.length <= 3) {
                merged[merged.size - 1] = "$prev.$p"
            } else merged += p
        }
        var tail = ""
        if (merged.size >= 2 && merged.last().lowercase() in TAIL_WORDS) {
            val last = merged.removeAt(merged.size - 1)
            tail = " (" + last.lowercase().replaceFirstChar { it.uppercaseChar() } + ")"
        }
        val words = mutableListOf<String>()
        var i = 0
        while (i < merged.size) {
            if (merged[i].lowercase() == "gpt" && i + 1 < merged.size) {   // OpenAI writes GPT-5.1, GPT-4o, GPT-OSS
                words += "GPT-" + word(merged[i + 1])
                i += 2
            } else {
                words += word(merged[i])
                i += 1
            }
        }
        return words.joinToString(" ") + tail
    }

    private fun word(w: String): String = when {
        w.lowercase() in ACRONYMS -> w.uppercase()
        SIZE.matches(w) -> w.uppercase()
        OPENAI_O.matches(w) -> w
        w.first().isLetter() -> w.replaceFirstChar { it.uppercaseChar() }
        else -> w
    }

    /** The chip's shorter form: a leading "Claude " says nothing next to Sonnet / Opus / Haiku. */
    fun short(name: String): String {
        val rest = name.removePrefix("Claude ").trim()
        return if (rest.isNotEmpty() && name.startsWith("Claude ")) rest else name
    }
}

/** What a model row may say about the model: only what the id's own tier words or the server's facts tell, nothing invented. */
enum class ModelTrait { FAST, REASONING, LARGE, IMAGES, LONG }

object ModelTraits {
    /** A context window at least this big is called long (1M-token families; most models today have 128-200k). */
    const val LONG_CONTEXT = 400_000L
    private val FAST = setOf("haiku", "flash", "mini", "nano", "lite", "small", "fast", "instant", "turbo")
    private val REASONING = setOf("thinking", "reasoner", "reasoning", "r1")
    private val LARGE = setOf("opus", "pro", "ultra", "large")

    fun of(id: String, facts: ModelFacts?): List<ModelTrait> {
        val words = id.lowercase().substringAfterLast('/').split('-', '_', ' ', ':', '.').toSet()
        return buildList {
            if (words.any { it in FAST }) add(ModelTrait.FAST)
            if (words.any { it in REASONING }) add(ModelTrait.REASONING)
            if (words.any { it in LARGE } && ModelTrait.FAST !in this) add(ModelTrait.LARGE)
            if (facts?.images == true) add(ModelTrait.IMAGES)
            if ((facts?.context ?: 0L) >= LONG_CONTEXT) add(ModelTrait.LONG)
        }
    }
}

/** How the thinking level can be chosen for the current provider. */
sealed interface EffortChoice {
    data class Levels(val levels: List<String>) : EffortChoice
    /** The provider takes it from the model name (agy): the server's sentence says how */
    data class FromModel(val note: String) : EffortChoice
    /** The server answered 400 to the level (a custom endpoint that does not take it): none is sent, the server's sentence says so */
    data class Refused(val note: String) : EffortChoice
    data object None : EffortChoice
}

object ModelChooserRules {
    /** How far below the floating header (the agent's name tag) the model sheet's top edge stays */
    val SHEET_GAP = 8.dp
    /** The most of the folded model row the model's name may take (the rest: "모델", the provider, the chevron) */
    const val NAME_SHARE = 0.6f
    /** Where the sheet's top edge may go at most: just under the header; without one (a page with no header), under the status bar */
    fun sheetTop(header: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp = if (header > 0.dp) header + SHEET_GAP else 48.dp

    /** [facts]: the chosen model's facts; the levels its provider's list states for it win over the provider's own levels */
    fun effort(p: ProviderInfo?, facts: ModelFacts? = null): EffortChoice = when {
        p == null -> EffortChoice.None
        facts?.effortLevels != null -> when {
            facts.effortLevels.isNotEmpty() -> EffortChoice.Levels(listOf("") + facts.effortLevels)
            !facts.effortNote.isNullOrBlank() -> EffortChoice.Refused(facts.effortNote)
            else -> EffortChoice.None
        }
        p.effortLevels.isNotEmpty() -> EffortChoice.Levels(listOf("") + p.effortLevels)   // "" = the model's own default
        !p.effortNote.isNullOrBlank() -> EffortChoice.FromModel(p.effortNote)
        else -> EffortChoice.None
    }

    /**
     * The provider sections of the sheet: the current provider first, then every other ready one (official before unofficial,
     * then by name). A provider that is not ready (no key, not signed in) stays in the detailed settings only.
     */
    fun sections(current: String, providers: List<ProviderInfo>): List<String> {
        val others = providers.filter { it.name != current && it.configured == true }
            .sortedWith(compareBy<ProviderInfo>({ ProviderConsentRules.group(it) }, { it.displayName.lowercase() }))
            .map { it.name }
        return (if (current.isNotEmpty()) listOf(current) else emptyList()) + others
    }

    /**
     * The model list is one folding row, like the thinking level, so the level and 자세한 설정 are always in reach however many
     * models there are. It starts folded; only with no model chosen yet (nothing to show in the row) does it start open.
     */
    fun listStartsOpen(currentModel: String, sections: List<String>): Boolean = currentModel.isBlank() && sections.isNotEmpty()

    /**
     * After a model is picked (or the one in use tapped again): the sheet closes when that provider has no thinking level to pick,
     * since nothing is left to choose here; otherwise the list folds and the sheet stays, the level one tap away.
     */
    fun closeAfterPick(effort: EffortChoice): Boolean = effort !is EffortChoice.Levels

    private val LEVEL_ORDER = listOf("low", "medium", "high", "xhigh", "max")

    /**
     * The level to show as chosen: [effort] itself when the provider has it, else the highest one below it (what the server sends,
     * effort.clamp: a custom server's levels stop at high before max, so a saved "xhigh" is sent and shown as high); "" = the default.
     */
    fun shownLevel(choice: EffortChoice.Levels, effort: String): String {
        if (effort in choice.levels) return effort
        val at = LEVEL_ORDER.indexOf(effort)
        if (at < 0) return ""
        val lower = choice.levels.filter { it.isNotEmpty() && LEVEL_ORDER.indexOf(it) in 0..at }
        return lower.lastOrNull() ?: choice.levels.firstOrNull { it.isNotEmpty() } ?: ""
    }

    /** The server's sentence for a provider or model with no levels to pick (agy's model names, a server that refused it); else null */
    fun effortNote(effort: EffortChoice): String? = when (effort) {
        is EffortChoice.FromModel -> effort.note
        is EffortChoice.Refused -> effort.note
        else -> null
    }

    /** The two folding rows open one at a time: opening one folds the other. Returns (model list open, levels open). */
    fun toggle(listOpen: Boolean, levelsOpen: Boolean, which: Fold): Pair<Boolean, Boolean> = when (which) {
        Fold.LIST -> (!listOpen).let { it to (levelsOpen && !it) }
        Fold.LEVELS -> (!levelsOpen).let { (listOpen && !it) to it }
    }

    enum class Fold { LIST, LEVELS }

    /** A folding row's chevron points right when folded and turns a quarter down when open (animated between the two). */
    const val CHEVRON_OPEN = 90f
    fun chevronTurn(open: Boolean): Float = if (open) CHEVRON_OPEN else 0f

    /** The models a section lists: the provider's list, with the current model on top when the list lacks it (typed by hand). */
    fun rows(provider: String, list: ModelList?, current: Pair<String, String>): List<String> {
        val ids = list?.models.orEmpty()
        val (cp, cm) = current
        return if (cp == provider && cm.isNotEmpty() && cm !in ids) listOf(cm) + ids else ids
    }
}
