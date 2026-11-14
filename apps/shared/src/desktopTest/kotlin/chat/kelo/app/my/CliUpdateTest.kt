package chat.kelo.app.my

import chat.kelo.app.core.KeloApi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CliUpdateTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val cli = CliStatus(installed = true, remote = true, version = "2.1.300 (Claude Code)", updateAvailable = true,
                                latestVersion = "2.1.319", updatable = true)

    @Test fun `the button shows only for the owner when a newer CLI is out and nothing else runs`() {
        assertTrue(CliUpdateRules.showButton(cli, busy = false))
        assertFalse(CliUpdateRules.showButton(cli, busy = true))
        assertFalse(CliUpdateRules.showButton(cli.copy(updatable = false), busy = false))      // not the owner, or an old sidecar
        assertFalse(CliUpdateRules.showButton(cli.copy(updateAvailable = false), busy = false))
        assertFalse(CliUpdateRules.showButton(cli.copy(installed = false), busy = false))
    }

    @Test fun `server status reads into an outcome`() {
        fun st(s: String) = json.decodeFromString<CliUpdateStatus>(s)
        val running = st("""{"state":"running","step":"installing","from":"2.1.300","to":"2.1.319","active":{"source":"image"}}""")
        assertFalse(CliUpdateRules.finished(running))
        assertEquals(CliUpdateRules.Outcome.Running, CliUpdateRules.outcome(running))
        val done = st("""{"state":"done","ok":true,"from":"2.1.300","to":"2.1.319","product":"Claude Code"}""")
        assertTrue(CliUpdateRules.finished(done))
        assertEquals(CliUpdateRules.Outcome.Updated("Claude Code", "2.1.319"), CliUpdateRules.outcome(done))
        assertEquals(CliUpdateRules.Outcome.Unchanged("Antigravity"),
            CliUpdateRules.outcome(st("""{"state":"done","ok":true,"unchanged":true}"""), "Antigravity"))
        assertEquals(CliUpdateRules.Outcome.Failed("The new version does not start"),
            CliUpdateRules.outcome(st("""{"state":"failed","ok":false,"error":"The new version does not start","error_key":"x"}""")))
        assertTrue(CliUpdateRules.finished(st("""{"state":"failed"}""")))
    }

    @Test fun `a newer CLI notice for the other service gets its own box`() {
        val withCli = listOf("claude-subscription-directsdk-experimental", "antigravity-oauth")
        assertEquals("antigravity-oauth", CliUpdateRules.focus("antigravity-oauth", "claude-subscription-directsdk-experimental", withCli))
        assertNull(CliUpdateRules.focus("antigravity-oauth", "antigravity-oauth", withCli))   // the form shows it already
        assertNull(CliUpdateRules.focus("openai", "antigravity-oauth", withCli))               // not a CLI
        assertNull(CliUpdateRules.focus("", "openai", withCli))
        assertNull(CliUpdateRules.focus(null, "openai", withCli))
    }

    @Test fun `the path names the provider safely`() {
        assertEquals("/api/providers/claude-subscription-directsdk-experimental/cli/update",
            CliUpdateRules.path("claude-subscription-directsdk-experimental"))
        assertEquals("/api/providers/a%20b/cli/update", CliUpdateRules.path("a b"))
        assertEquals(KeloApi.segment("x/y"), CliUpdateRules.path("x/y").removePrefix("/api/providers/").removeSuffix("/cli/update"))
    }
}
