package chat.kelo.app.agent

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*


import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.kelo.app.ui.components.ConfirmDialog
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.launch

/**
 * The grants as one bordered list: what each allows, where, the cap, and Revoke (asks first). [showSchedule]: name the schedule
 * (on the wallet and the login pages; the schedule's own page leaves it out).
 */
@Composable
fun ScheduleGrantRows(grants: List<ScheduleGrant>, showSchedule: Boolean, onRevoke: (ScheduleGrant) -> Unit) {
    val c = Kelo.colors
    var asking by remember { mutableStateOf<ScheduleGrant?>(null) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, c.line, RoundedCornerShape(12.dp)),
    ) {
        grants.forEachIndexed { i, g ->
            if (i > 0) HorizontalDivider(color = c.line)
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        when {
                            g.isPurchase -> string(Res.string.grant_purchase_title, g.what)
                            g.isSend -> string(Res.string.task_grant_row_send, g.placesText)
                            g.isChange -> string(Res.string.task_grant_row_change, g.what)
                            g.isApply -> string(Res.string.task_grant_row_apply, g.what)
                            else -> string(Res.string.grant_login_title, g.loginLabel.ifBlank { g.login })
                        },
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = c.ink,
                    )
                    if (g.trigger.isNotBlank()) {
                        Text(string(Res.string.task_grant_row_when, g.trigger), style = MaterialTheme.typography.bodySmall, color = c.ink)
                    }
                    val sub = when {
                        g.isPurchase -> string(Res.string.grant_purchase_cap, g.site, g.maxAmountText, g.monthlyCapText)
                        g.isChange -> g.placesText + if (g.allowDelete) " · " + string(Res.string.task_grant_delete_yes) else ""
                        g.isSend -> g.template
                        g.isApply -> listOf(g.site, string(Res.string.task_grant_apply_per_run, g.maxCount)).filter { it.isNotBlank() }.joinToString(" · ")
                        else -> string(Res.string.grant_login_site, g.site)
                    }
                    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = c.muted)
                    if (g.isPurchase && !g.cardLabel.isNullOrBlank()) {
                        Text(string(Res.string.task_grant_row_card, g.cardLabel + (g.cardLast4?.let { " ••$it" } ?: "")),
                            style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                    g.left?.takeIf { !g.isLogin }?.let { n ->
                        Text(string(Res.string.task_grant_row_left, n, if (g.isApply) g.totalCount else g.maxCount) +
                            if (g.once) " · " + string(Res.string.task_grant_row_once) else "",
                            style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                    if (g.isPurchase && g.spentThisMonth > 0) {
                        Text(string(Res.string.grant_spent, grantMoney(g.spentThisMonth, g.currency)),
                            style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                    if (showSchedule && g.ownerName.isNotBlank()) {
                        Text(string(Res.string.grant_for_schedule, g.ownerName), style = MaterialTheme.typography.bodySmall, color = c.ink)
                    }
                }
                LinkButton(string(Res.string.grant_revoke), onClick = { asking = g }, danger = true)
            }
        }
    }
    asking?.let { g ->
        ConfirmDialog(string(Res.string.grant_revoke_confirm), string(Res.string.grant_revoke), onDismiss = { asking = null }, onYes = {
            asking = null
            onRevoke(g)
        })
    }
}

/**
 * A task page's "allowed without asking" block (a watcher's or a background job's page; the schedule page has its own): the grants
 * allowed at setup, each with Revoke, and the note that anything else still asks. Nothing shows while there are none.
 */
@Composable
fun TaskGrantsBlock(key: Any, load: suspend () -> List<ScheduleGrant>, revoke: suspend (ScheduleGrant) -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var grants by remember(key) { mutableStateOf<List<ScheduleGrant>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(key) { grants = runCatching { load() }.getOrDefault(emptyList()) }
    if (grants.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(string(Res.string.grant_section_title), style = MaterialTheme.typography.labelLarge, color = Kelo.colors.accent)
        ScheduleGrantRows(grants, showSchedule = false) { g ->
            scope.launch { if (runCatching { revoke(g) }.isSuccess) grants = grants - g }
        }
        Text(string(Res.string.task_grant_section_note), style = MaterialTheme.typography.bodySmall, color = Kelo.colors.muted)
    }
}

/** An amount in a currency ("30,000원", "25.00 USD"), digits and separators in the app's language. */
@Composable
private fun grantMoney(amount: Double, currency: String): String {
    val n = chat.kelo.app.wallet.Money.number(amount, currency, chat.kelo.app.my.appLocale())
    return if (currency.equals("KRW", ignoreCase = true)) string(Res.string.wallet_money_krw, n) else string(Res.string.wallet_money, n, currency.uppercase())
}
