package chat.kelo.app.shell

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.LocalAppServices
import chat.kelo.app.agent.AgentPicture
import chat.kelo.app.chat.AgentStatus
import chat.kelo.app.core.Profile
import chat.kelo.app.res.*
import chat.kelo.app.ui.character.KeloCharacter
import chat.kelo.app.ui.character.KeloMood
import chat.kelo.app.ui.character.centerShift
import chat.kelo.app.ui.platform.formFactor
import chat.kelo.app.ui.text.plural
import chat.kelo.app.ui.text.string
import chat.kelo.app.ui.theme.Kelo
import chat.kelo.app.ui.theme.LocalReduceMotion
import chat.kelo.app.ui.theme.Motion
import chat.kelo.app.work.WorkBadge

/** The header's round badge, in dp. */
private const val HEADER_BADGE = 68

/** The launcher icon's cream: the character sits on it (light in both themes, like a sticker). */
private val BADGE_CREAM = Color(0xFFFFF3D6)

/**
 * The floating header of the main tabs: the agent at the top centre and its name in a small pill below, with nothing behind them (the
 * page scrolls underneath). Without a photo the agent is Kelo's character, whose drawing follows what the agent is doing ([ShellUiState.mood]);
 * a photo gets a ring that turns green while the agent works. What it is doing is read out by TalkBack; a small red dot means
 * something waits for the owner. Tapping it opens the agent's page. On the desktop the line also shows under the name.
 */
@Composable
fun FloatingHeader(profile: Profile, ui: ShellUiState, onAgent: () -> Unit, modifier: Modifier = Modifier) {
    val c = Kelo.colors
    val reduce = LocalReduceMotion.current
    val photo by LocalAppServices.current.agentAvatar.image.collectAsStateWithLifecycle()
    val line = ui.line
    val openLabel = string(Res.string.agent_open)
    val newsWords = ui.badge?.let { waitingWords(ui.status) }
    val offlineWords = if (ui.mood == KeloMood.Offline) string(Res.string.agent_offline) else null
    val name = profile.agentName.ifBlank { "Kelo" }
    // the desktop's pill is wider and carries the second line (the phone's agent page and TalkBack say it)
    val secondLine = formFactor(phone = false, desktop = true)
    val second = if (secondLine) offlineWords ?: line else null
    // nothing behind the picture and the name: the page shows through everywhere else
    Box(modifier.then(formFactor(Modifier.fillMaxWidth(), Modifier))) {
        Column(
            Modifier.then(formFactor(Modifier.fillMaxWidth(), Modifier)).statusBarsPadding().padding(top = 6.dp, bottom = formFactor(6.dp, 0.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.clip(RoundedCornerShape(20.dp))
                    .clickable(role = Role.Button, onClickLabel = openLabel, onClick = onAgent)
                    .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(name, offlineWords, line, newsWords).joinToString(", ") }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // the name pill sits on the lower edge of the picture, so the two read as one
                Box(contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier.align(Alignment.BottomCenter).widthIn(max = formFactor(260.dp, 320.dp))
                            .shadow(3.dp, RoundedCornerShape(14.dp), ambientColor = c.scrim, spotColor = c.scrim)
                            .clip(RoundedCornerShape(14.dp))
                            .background(c.card).border(1.dp, c.line, RoundedCornerShape(14.dp))
                            .animateContentSize(Motion.small(reduce))
                            .padding(horizontal = 12.dp, vertical = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(name, style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (second != null) Text(second, style = MaterialTheme.typography.labelSmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // one round badge for either a photo or the character (on a card-coloured disc), so both sit centred the same way
                    Box(Modifier.padding(bottom = if (second != null) 36.dp else 21.dp)) {   // the disc covers only the name tag's top edge
                        val ring = if (photo != null && line != null) c.success else c.card
                        Box(
                            Modifier.size(HEADER_BADGE.dp)
                                .shadow(6.dp, CircleShape, ambientColor = c.scrim, spotColor = c.scrim)
                                .clip(CircleShape).background(if (photo != null) c.card else BADGE_CREAM).border(2.dp, ring, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (photo != null) AgentPicture(name, photo, HEADER_BADGE)
                            else KeloCharacter(ui.mood, Modifier.size((HEADER_BADGE * 0.92f).dp).offset(x = (HEADER_BADGE * 0.92f * ui.mood.centerShift() / 120f).dp, y = 3.dp))
                        }
                        if (ui.news) NewsDot(Modifier.align(Alignment.TopEnd).offset(x = (-2).dp, y = 2.dp))
                    }
                }
            }
        }
    }
}

/** The red dot on the header's agent: something waits for the owner ([WorkBadge]). */
@Composable
fun NewsDot(modifier: Modifier = Modifier) {
    val c = Kelo.colors
    Box(modifier.size(12.dp).border(2.dp, c.bg, CircleShape).padding(2.dp).background(c.danger, CircleShape))
}

/** What waits, for a screen reader: "입력을 기다리는 키 1개, 답을 기다리는 질문 2개" (by kind; [WorkBadge.parts]). */
@Composable
fun waitingWords(status: AgentStatus?): String =
    WorkBadge.parts(status).map { p ->
        val res = when (p.kind) {
            WorkBadge.APPROVALS -> Res.plurals.work_badge_approvals
            WorkBadge.KEYS -> Res.plurals.work_badge_keys
            WorkBadge.LOGINS -> Res.plurals.work_badge_logins
            WorkBadge.CARDS -> Res.plurals.work_badge_cards
            WorkBadge.PINS -> Res.plurals.work_badge_pins
            else -> Res.plurals.work_badge_waiting
        }
        plural(res, p.count, p.count)
    }.joinToString(", ")

/** How many things wait for the owner, in a small red pill on a tab's icon. */
@Composable
fun TabBadge(b: WorkBadge.Shown, modifier: Modifier = Modifier) {
    val c = Kelo.colors
    Text(
        if (b.count > 99) string(Res.string.count_badge_max) else b.count.toString(),
        color = c.onDanger, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        modifier = modifier.clip(RoundedCornerShape(99.dp)).background(c.danger).padding(horizontal = 5.dp, vertical = 0.dp),
    )
}
