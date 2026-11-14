package chat.kelo.app.chat

/**
 * Answer buttons under an agent message, apart from the UI (the phone, desktop and iPhone apps follow the same rules).
 *
 * An answer may mean "I will write it" ("Other", "직접 입력할게요", "있어요 (직접 입력)"): the server lists those in the question's
 * `typed`, and for an older server that does not, [asksTyping] tells them by their words. Picking one opens a text box under that
 * question (placeholder: [placeholder]); what is typed is sent instead of the button's words, and nothing is sent while a picked one is
 * still empty.
 */
object QuickReplyRules {
    /** The app's own "Other" answer, added to every choice question that has no answer to write ([choices]); it opens a text box. */
    const val OTHER = "\u0000other"

    /** Buttons show only under the newest agent message, while the user has not written after it. */
    fun isOpen(items: List<ChatItem>, key: String): Boolean {
        val msgs = items.filterIsInstance<ChatItem.Msg>()
        val i = msgs.indexOfFirst { it.key == key }
        return i >= 0 && msgs.drop(i + 1).none { it.mine }
    }

    /** The buttons of a choice question: its options, then [OTHER] when none of them is an answer to write. Choices are suggestions:
     *  the user can always answer outside them. A lone button (a "Continue") gets no "Other". A field question has no buttons. */
    fun choices(q: ReplyQuestion): List<String> = when {
        ReplyFields.isField(q) -> emptyList()
        q.options.size >= 2 && q.options.none { isTyped(q, it) } -> q.options + OTHER
        else -> q.options
    }

    /** One choice question: a tap sends the answer at once. Several, or a field (a picker or a box): the answers go together with
     *  the send button, one line per question. */
    fun sendsAtOnce(set: ReplySet): Boolean = set.questions.size <= 1 && set.questions.none { ReplyFields.isField(it) }

    /** The answer [option] of [q] opens a text box instead of being the answer itself. */
    fun isTyped(q: ReplyQuestion, option: String): Boolean = option == OTHER || option in q.typed || asksTyping(option)

    /** A tap on [option] sends it straight away (a single question, and not an answer to write). */
    fun sendsOnTap(set: ReplySet, q: ReplyQuestion, option: String): Boolean = sendsAtOnce(set) && !isTyped(q, option)

    /** The send button shows: several questions, a field, or a picked answer to write. */
    fun showsSend(set: ReplySet, picks: Map<Int, String>): Boolean =
        !sendsAtOnce(set) || picks.any { (i, o) -> set.questions.getOrNull(i)?.let { isTyped(it, o) } == true }

    /** Questions whose picked answer is one to write: their text box shows. */
    fun writing(set: ReplySet, picks: Map<Int, String>): Set<Int> =
        picks.filter { (i, o) -> set.questions.getOrNull(i)?.let { isTyped(it, o) } == true }.keys

    /** Something is answered, every picked answer to write has words, and every field that has something in it fits its kind
     *  ([values]: what each field holds, in its plain form or as typed). */
    fun canSend(set: ReplySet, picks: Map<Int, String>, typed: Map<Int, String>, values: Map<Int, String> = emptyMap()): Boolean {
        val filled = values.filter { (i, v) -> v.isNotBlank() && set.questions.getOrNull(i)?.let { ReplyFields.isField(it) } == true }
        return (picks.isNotEmpty() || filled.isNotEmpty()) && writing(set, picks).all { !typed[it].isNullOrBlank() } &&
            filled.all { (i, v) -> ReplyFields.check(set.questions[i], v) != null }
    }

    /** The text box's hint: the server's, else the question itself, else null (the app's own "write your answer"). */
    fun placeholder(q: ReplyQuestion): String? = q.hint.trim().ifEmpty { q.question.trim() }.ifEmpty { null }

    /** Each answered question's answer: its place and its plain form (typed words for an answer to write, a field's checked value). */
    fun answers(set: ReplySet, picks: Map<Int, String>, typed: Map<Int, String> = emptyMap(), values: Map<Int, String> = emptyMap()): List<ReplyAnswer> {
        val writing = writing(set, picks)
        return set.questions.indices.mapNotNull { i ->
            val q = set.questions[i]
            val plain = if (ReplyFields.isField(q)) ReplyFields.check(q, values[i]) else {
                val pick = picks[i] ?: return@mapNotNull null
                if (i in writing) oneLine(typed[i]) else pick
            }
            plain?.let { ReplyAnswer(i, it) }
        }
    }

    /** The whole sentence a picked option sends, or null (no sentence for it, a typed answer, or a field). */
    fun sentence(q: ReplyQuestion, pick: String): String? =
        q.options.indexOf(pick).takeIf { it >= 0 }?.let { q.says.getOrNull(it) }?.trim()?.ifEmpty { null }

    /** The message sent: one line per answered question ("question: answer"; a lone question just the answer), typed words in place
     *  of an answer to write, a field's value in the user's words ([show]: [ReplyFields.display]). */
    fun compose(
        set: ReplySet, picks: Map<Int, String>, typed: Map<Int, String> = emptyMap(), values: Map<Int, String> = emptyMap(),
        show: (ReplyQuestion, String) -> String = { _, plain -> plain },
    ): String = answers(set, picks, typed, values).joinToString("\n") { a ->
        val q = set.questions[a.question]
        if (!ReplyFields.isField(q)) sentence(q, a.value)?.let { return@joinToString it }
        val answer = if (ReplyFields.isField(q)) show(q, a.value) else a.value
        val title = q.question.trim()
        if (title.isEmpty() || set.questions.size == 1) answer else "$title: $answer"
    }

    private fun oneLine(text: String?): String? = text?.trim()?.replace(Regex("\\s*\\n\\s*"), " ")?.ifEmpty { null }

    /** Words of an answer that ask the user to write it: a typing phrase anywhere, or just "Other" (with "(...)" or "…" after it).
     *  The same lists as the server's (goals.py TYPING_PHRASES / OTHER_WORDS); kept conservative. */
    fun asksTyping(label: String): Boolean {
        val s = label.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        if (TYPING_PHRASES.any { it in s }) return true
        val bare = s.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").trim(' ', '.', '…', ':', '!', '?', '·', '-', '–', '—')
        return bare in OTHER_WORDS
    }

    private val TYPING_PHRASES = listOf(
        "type it", "i'll type", "let me type", "type my own", "write my own", "i'll write", "write it in", "(specify)", "please specify",
        "직접 입력", "직접입력", "직접 쓸", "직접 쓰", "직접 적", "입력할게",
        "直接入力", "自分で入力", "自由入力", "入力します", "自行输入", "手动输入", "自己输入", "自行輸入", "手動輸入", "自己輸入", "自定义", "自訂",
        "lo escribo", "escribir", "(especificar)", "je l'écris", "saisir", "(préciser)", "selbst eingeben", "eingeben", "selbst schreiben",
        "digitar", "vou escrever", "lo scrivo", "scrivere", "(specificare)", "zelf invullen", "zelf typen", "wpiszę", "wpisz", "napiszę",
        "свой вариант", "введу", "ввести", "напишу", "свій варіант", "kendim yazacağım", "kendim yazayım", "yazacağım",
        "tự nhập", "tự ghi", "tự viết", "ketik sendiri", "tulis sendiri", "พิมพ์เอง", "ระบุเอง", "سأكتب", "اكتب بنفسك",
        "खुद लिखूँगा", "खुद लिखूंगा", "खुद लिखें",
    )

    private val OTHER_WORDS = setOf(
        "other", "others", "something else", "기타", "다른 것", "その他", "其他", "其它", "otro", "otra", "otros", "autre", "autres", "andere",
        "anderes", "sonstiges", "outro", "outra", "altro", "anders", "overig", "inne", "inny", "другое", "інше", "diğer", "khác", "lainnya",
        "อื่นๆ", "อื่น ๆ", "أخرى", "غير ذلك", "अन्य",
    )
}
