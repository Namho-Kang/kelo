package chat.kelo.app.goals

import chat.kelo.app.core.Goal
import chat.kelo.app.my.MyFakeServer
import chat.kelo.app.my.MyFakeServer.Companion.fail
import chat.kelo.app.my.MyFakeServer.Companion.ok
import chat.kelo.app.my.MyFakeServer.Companion.until
import chat.kelo.app.res.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** The goals tab's view models against a local fake server (MockWebServer). */
@OptIn(ExperimentalCoroutinesApi::class)
class GoalsViewModelsTest {
    private var server: MyFakeServer? = null

    @Before fun main() { Dispatchers.setMain(Dispatchers.Default) }   // real time: the polls wait with delay()

    // the view models are cleared with the test (as a page leaving would): none is left running when Main goes away
    private val made = mutableListOf<ViewModel>()
    private fun <T : ViewModel> T.tracked(): T = also { made += it }

    @After fun stop() {
        runBlocking { made.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }   // finished, not only asked to stop
        server?.shutdown()
        Dispatchers.resetMain()
    }

    private fun fake(answer: (method: String, path: String, body: String) -> okhttp3.mockwebserver.MockResponse?) = MyFakeServer(answer).also { server = it }

    private fun count(s: MyFakeServer, prefix: String) = s.requests.count { it.startsWith(prefix) }

    private val fast: (List<Goal>) -> Long? = { goals -> if (goals.any { it.working }) 20L else null }

    @Test fun goalsLoadWithTheirSuggestionsAndAFailedLoadIsFlagged() {
        val s = fake { m, p, _ ->
            when ("$m $p") {
                "GET /api/goals" -> ok("""[{"id":1,"title":"Run"},{"id":2,"title":"Read","status":"done"}]""")
                "GET /api/goals/suggestions" -> ok("""{"items":[{"id":9,"title":"Sleep earlier"}]}""")
                else -> null
            }
        }
        val vm = GoalsViewModel(s.sessions(), fast).tracked()
        vm.load()
        until("goals") { vm.state.value.loaded && vm.state.value.suggestions.isNotEmpty() }
        assertEquals(listOf(1L), vm.state.value.tracking.map { it.id })
        assertEquals(listOf(2L), vm.state.value.done.map { it.id })
        assertEquals("Sleep earlier", vm.state.value.suggestions.single().title)

        val down = fake { _, _, _ -> fail(500, "down") }
        val bad = GoalsViewModel(down.sessions(), fast).tracked()
        bad.load()
        until("failed") { bad.state.value.failed }
        assertFalse(bad.state.value.loaded)
    }

    @Test fun aGoalIsAddedAndAFailedAddIsFlagged() {
        var refuse = false
        val s = fake { m, p, _ ->
            when ("$m $p") {
                "GET /api/goals" -> ok("""[]""")
                "POST /api/goals" -> if (refuse) fail(400, "no") else ok("""{"id":3,"title":"Run"}""")
                else -> null
            }
        }
        val vm = GoalsViewModel(s.sessions(), fast).tracked()
        val added = AtomicInteger()
        vm.add("Run", "", "") { added.incrementAndGet() }
        until("added") { added.get() == 1 }
        refuse = true
        vm.add("Walk", "", "") { added.incrementAndGet() }
        until("failed") { vm.state.value.failed }
        assertEquals(1, added.get())
    }

    @Test fun aSwipedGoalIsDeletedOnlyWhenTheUndoIsGone() {
        val s = fake { m, p, _ ->
            when ("$m $p") {
                "GET /api/goals" -> ok("""[{"id":1,"title":"Run"}]""")
                "DELETE /api/goals/1" -> ok("{}")
                else -> null
            }
        }
        val vm = GoalsViewModel(s.sessions(), fast).tracked()
        vm.load()
        until("goals") { vm.state.value.loaded }
        val g = vm.state.value.goals.single()
        vm.startRemove(g)
        assertTrue("hidden at once", vm.state.value.tracking.isEmpty())
        assertFalse(s.saw("DELETE"))
        vm.undoRemove(g)
        assertEquals(1, vm.state.value.tracking.size)
        vm.startRemove(g)
        vm.commitRemove(g)
        until("deleted") { s.saw("DELETE /api/goals/1") }
    }

    @Test fun theGoalsAreFetchedAgainWhileKeloWorksOnOneButOnlyWhileTheScreenIsInView() = runBlocking {
        val s = fake { m, p, _ ->
            when ("$m $p") {
                "GET /api/goals" -> ok("""[{"id":1,"title":"Run","working":true}]""")
                "GET /api/goals/suggestions" -> ok("""{"items":[]}""")
                else -> null
            }
        }
        val vm = GoalsViewModel(s.sessions(), fast).tracked()
        vm.load()
        until("loaded") { vm.state.value.loaded }
        Thread.sleep(200)
        val idle = count(s, "GET /api/goals/suggestions")
        assertEquals("nothing polls before a screen shows the state", 1, idle)

        val look = launch(Dispatchers.Default) { vm.state.collect { } }
        until("polls") { count(s, "GET /api/goals/suggestions") >= 3 }
        look.cancelAndJoin()   // the screen is left or the app goes to the background
        Thread.sleep(150)
        val after = count(s, "GET /api/goals/suggestions")
        Thread.sleep(400)
        assertEquals("no request while nobody collects", after, count(s, "GET /api/goals/suggestions"))
        val again = launch(Dispatchers.Default) { vm.state.collect { } }
        until("polling again") { count(s, "GET /api/goals/suggestions") > after }
        again.cancelAndJoin()
    }

    // ---- one goal's page

    private val goal = Goal(7, "Run a 10k", working = true)
    private val workspace = """{"goal":{"id":7,"title":"Run a 10k","working":true},"steps":[{"id":1,"title":"Buy shoes","owner":"user"}]}"""

    @Test fun theWorkspaceLoadsAndATickedStepIsSent() {
        val s = fake { m, p, _ ->
            when ("$m $p") {
                "GET /api/goals/7" -> ok(workspace)
                "PATCH /api/goals/7/steps/1" -> ok("{}")
                else -> null
            }
        }
        val vm = GoalDetailViewModel(s.sessions(), goal, fast).tracked()
        vm.load()
        until("workspace") { vm.state.value.workspace != null }
        val step = vm.state.value.workspace!!.steps.single()
        vm.tick(step)
        assertEquals("shown ticked at once", "done", vm.state.value.ticking[1L])
        until("sent") { s.saw("PATCH /api/goals/7/steps/1") }
    }

    @Test fun continuingTheGoalSaysWhetherItStartedOrNeedsAModel() {
        var answer = ok("{}")
        val s = fake { m, p, _ -> if ("$m $p" == "POST /api/goals/7/work") answer else null }
        val vm = GoalDetailViewModel(s.sessions(), goal, fast).tracked()
        val started = AtomicInteger()
        vm.resume { started.incrementAndGet() }
        until("started") { started.get() == 1 && !vm.state.value.resuming }
        assertEquals(Res.string.goal_resume_started, vm.state.value.resumed)

        answer = fail(409, "no model")
        vm.resume { started.incrementAndGet() }
        until("no model") { vm.state.value.resumed == Res.string.goal_resume_no_model && !vm.state.value.resuming }
        assertEquals(1, started.get())
    }

    @Test fun aPageThatCannotLoadIsFlagged() {
        val s = fake { _, _, _ -> fail(500, "down") }
        val vm = GoalDetailViewModel(s.sessions(), goal, fast).tracked()
        vm.load()
        until("failed") { vm.state.value.failed }
        assertNull(vm.state.value.workspace)
    }

    @Test fun thePageIsFetchedAgainWhileKeloWorksButOnlyWhileItIsInView() = runBlocking {
        val s = fake { m, p, _ -> if ("$m $p" == "GET /api/goals/7") ok(workspace) else null }
        val vm = GoalDetailViewModel(s.sessions(), goal, fast).tracked()
        Thread.sleep(200)
        assertEquals("nothing is asked before the page is in view", 0, count(s, "GET /api/goals/7"))
        val look = launch(Dispatchers.Default) { vm.state.collect { } }
        until("polls") { count(s, "GET /api/goals/7") >= 3 }
        look.cancelAndJoin()
        Thread.sleep(150)
        val after = count(s, "GET /api/goals/7")
        Thread.sleep(400)
        assertEquals("no request while nobody collects", after, count(s, "GET /api/goals/7"))
    }
}
