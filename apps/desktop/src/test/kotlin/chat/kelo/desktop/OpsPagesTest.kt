package chat.kelo.desktop

import chat.kelo.app.res.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import chat.kelo.desktop.core.AppContainer
import chat.kelo.app.my.OperatorPage
import chat.kelo.app.my.VaultPage
import chat.kelo.app.wallet.WalletPage
import chat.kelo.app.my.PermissionsPage
import chat.kelo.desktop.ui.KeloFrame
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * My → Operator, Keychain, Payment cards and Permissions against tools/mock_server.py (tools/mock_parts/ops.py), in Korean: each page
 * shows its rows, a beta request is approved, a remembered rule is taken back. Screenshots (light and dark) go to $KELO_SHOTS or
 * build/parity as parity-ops-<page>-<theme>.png. Buttons are pressed through their click action (the clock is held, so a scroll
 * animation would never end).
 */
class OpsPagesTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private var mock: MockServer? = null
    private val ko = Words.of("ko")

    @After fun stop() { mock?.stop() }

    /** The page on a fresh mock in Korean, drawn light then dark ([wait] shows once loaded); returns the app for the next steps. */
    private fun open(name: String, wait: String, page: @Composable (AppContainer) -> Unit): Pair<AppContainer, UiSteps> {
        val server = MockServer("--lang", "ko").also { mock = it }
        val app = server.app(tmp.newFolder(), lang = "ko")
        val ui = UiSteps(compose, parityShots())
        compose.mainClock.autoAdvance = false
        compose.setContent { KeloFrame(app) { Box(Modifier.fillMaxSize()) { page(app) } } }
        ui.waitText(wait)
        ui.settle(20)
        ui.shot("parity-ops-$name-light")
        app.settings.setTheme("dark")
        ui.settle(20)
        ui.shot("parity-ops-$name-dark")
        app.settings.setTheme("light")
        ui.settle(4)
        return app to ui
    }

    private fun click(text: String): SemanticsNodeInteraction =
        compose.onNode(hasText(text) and hasClickAction(), useUnmergedTree = false).also { it.performSemanticsAction(SemanticsActions.OnClick) }

    @Test fun operatorPageApprovesABetaRequest() {
        val (_, ui) = open("operator", "minji@example.com") { _ -> OperatorPage(onBack = {}) }
        ui.waitText(ko.get(Res.string.operator_title_github))
        ui.waitText(ko.get(Res.string.operator_title_google))
        ui.waitText("kelo-push")
        ui.waitText(ko.get(Res.string.operator_reports_open, 1))
        click(ko.get(Res.string.operator_beta_approve))
        ui.waitText(ko.get(Res.string.operator_beta_mailed, "minji@example.com"))
        ui.waitUntil("no request waiting") { compose.onAllNodes(hasText(ko.get(Res.string.operator_beta_approve)) and hasClickAction()).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun keychainListsLoginsKeysAndMailboxes() {
        val (_, ui) = open("vault", "OpenWeather") { app -> VaultPage(onBack = {}, onGoModel = {}) }
        ui.waitText(ko.get(Res.string.vault_logins))
        ui.waitText(ko.get(Res.string.vault_mailboxes))
        click("쿠팡")
        ui.waitText(ko.get(Res.string.vault_when_login))
        ui.shot("parity-ops-vault-item-light")
    }

    @Test fun cardsShowWhereTheyPayFrom() {
        val (_, ui) = open("wallet", "생활비 카드") { app -> WalletPage(onBack = {}, onSeen = {}) }
        ui.waitText(ko.get(Res.string.wallet_tag_expired))
        click(ko.get(Res.string.wallet_limits_title))
        ui.waitText("KRW")
        ui.settle(10)
        ui.shot("parity-ops-wallet-limits-light")
    }

    @Test fun permissionsTakeARuleBack() {
        val (_, ui) = open("permissions", "naver.com") { app -> PermissionsPage(onBack = {}, onOpenWork = {}) }
        ui.waitText(ko.get(Res.string.agent_perm_history))
        compose.onNode(hasContentDescription(ko.get(Res.string.agent_rule_revoke, "naver.com"))).performSemanticsAction(SemanticsActions.OnClick)
        ui.waitText(ko.get(Res.string.agent_rule_revoke_yes))
        compose.onNode(hasText(ko.get(Res.string.agent_rule_revoke_yes))).performSemanticsAction(SemanticsActions.OnClick)
        ui.waitText(ko.get(Res.string.agent_rule_revoked, "naver.com"))
        assertFalse(ui.has(ko.get(Res.string.agent_rule_revoke, "naver.com")))
    }
}
