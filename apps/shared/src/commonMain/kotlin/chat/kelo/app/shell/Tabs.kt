package chat.kelo.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import chat.kelo.app.res.*
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.text.string
import org.jetbrains.compose.resources.StringResource

/** A tab's name: a text of the app's own, or the name a Nest package gave its tab. */
sealed interface TabTitle {
    data class Res(val res: StringResource) : TabTitle
    data class Text(val text: String) : TabTitle
}

@Composable
fun TabTitle.text(): String = when (this) {
    is TabTitle.Res -> string(res)
    is TabTitle.Text -> text
}

/** Which badge a tab carries: [WORK] is the red pill with how many things wait for the owner. */
enum class TabBadgeKind { NONE, WORK }

/**
 * One tab of the signed-in app. The phone's bottom bar and the desktop's side rail both draw this list; each platform keeps its own
 * navigation container (Android: a NavHost, desktop: a page state) and maps a tab's [id] to its [content].
 */
class TabSpec(
    val id: String,
    val title: TabTitle,
    val icon: ImageVector,
    val badge: TabBadgeKind = TabBadgeKind.NONE,
    val content: @Composable () -> Unit,
    /** True for a tab a Nest package added. */
    val fromNest: Boolean = false,
)

/**
 * Which built-in tabs the owner hides. 채팅 and 마이 are always there (they cannot be hidden); 다짐 and 커넥터 may be, and then My has a
 * row for each. The default is today's tabs. Editing it and keeping it is Nest work: there is no screen for it and nothing is stored yet.
 */
data class TabLayout(val hidden: Set<String> = emptySet()) {
    /** [id] is a tab the layout hides: only 다짐 and 커넥터 can be. */
    fun hides(id: String): Boolean = id in hidden && id in REMOVABLE

    companion object {
        val REMOVABLE = setOf(Routes.GOALS, Routes.CONNECTORS)
        val Default = TabLayout()
    }
}

/**
 * The built-in tabs in today's order: 채팅, 다짐, [작업 when [withWork]: the desktop's rail; the phone opens it from the header], 커넥터, 마이.
 * [content] gives a tab's page by its id.
 */
fun builtInTabs(withWork: Boolean, content: (id: String) -> @Composable () -> Unit): List<TabSpec> = buildList {
    add(TabSpec(Routes.CHAT, TabTitle.Res(Res.string.tab_chat), KeloIcons.Chat, content = content(Routes.CHAT)))
    add(TabSpec(Routes.GOALS, TabTitle.Res(Res.string.tab_goals), KeloIcons.Goal, content = content(Routes.GOALS)))
    if (withWork) add(TabSpec(Routes.WORK, TabTitle.Res(Res.string.tab_work), KeloIcons.Work, TabBadgeKind.WORK, content(Routes.WORK)))
    add(TabSpec(Routes.CONNECTORS, TabTitle.Res(Res.string.tab_connectors), KeloIcons.Plug, content = content(Routes.CONNECTORS)))
    add(TabSpec(Routes.MY, TabTitle.Res(Res.string.tab_my), KeloIcons.User, content = content(Routes.MY)))
}

/**
 * The tabs to show: the built-in ones the [layout] does not hide (채팅 and 마이 always stay), and the Nest `tab` [nest] entries marked
 * `shown`, at most [maxTabs] of them, in the server's order, after 커넥터 and before 마이. [nest] must be empty while the app has no
 * [NestSurfaceView] ([nestEntries] does that); the entry's page is drawn by it.
 */
fun tabsFor(builtIn: List<TabSpec>, nest: List<NestEntry>, layout: TabLayout = TabLayout.Default, maxTabs: Int = 0): List<TabSpec> {
    val kept = builtIn.filter { it.id == Routes.CHAT || it.id == Routes.MY || !layout.hides(it.id) }
    val added = nest.filter { it.shown }.take(maxTabs.coerceAtLeast(0)).map { e ->
        TabSpec("nest/${e.key}", TabTitle.Text(e.title.ifBlank { e.packageName }), KeloIcons.Spark, fromNest = true, content = { NestPage(e) })
    }
    val my = kept.indexOfFirst { it.id == Routes.MY }
    return if (my < 0) kept + added else kept.take(my) + added + kept.drop(my)
}

/** The built-in tabs the layout hides: My lists them so they stay reachable. */
fun hiddenTabs(builtIn: List<TabSpec>, layout: TabLayout = TabLayout.Default): List<TabSpec> = builtIn.filter { layout.hides(it.id) }

@Composable
private fun NestPage(entry: NestEntry) {
    LocalNestSurfaceView.current?.Show(entry, Modifier.fillMaxSize())
}

/** The tabs for the current state: the layout, and Nest's tab entries (none without a [NestSurfaceView]). */
@Composable
fun rememberTabs(builtIn: List<TabSpec>, layout: TabLayout = TabLayout.Default): List<TabSpec> =
    tabsFor(builtIn, nestEntries(NestPlace.TAB), layout, LocalNest.current.maxTabs)
