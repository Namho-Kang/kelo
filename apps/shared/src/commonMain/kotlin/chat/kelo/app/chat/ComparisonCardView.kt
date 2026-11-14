package chat.kelo.app.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.kelo.app.chat.vault.CardBox
import chat.kelo.app.res.*
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.text.string
import chat.kelo.app.ui.theme.Kelo

private val HEADER_H = 52.dp
private val CELL_H = 52.dp

object ComparisonRules {
    fun choice(option: ComparisonOption, fallback: (String) -> String): String =
        option.says.ifBlank { fallback(option.name) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComparisonCardView(card: ComparisonCard, onChoose: (String) -> Unit) {
    val c = Kelo.colors
    CardBox {
        if (card.title.isNotBlank()) {
            Text(card.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.ink)
        }
        if (card.basis.isNotBlank()) {
            Text(card.basis, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }

        val scrollOptions = card.options.size > 2
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Row labels fixed on the left; every cell has a fixed height (2 lines at most) so the label rows and the option rows stay level
            Column(Modifier.widthIn(min = 56.dp, max = 110.dp).padding(vertical = 6.dp)) {
                Box(Modifier.fillMaxWidth().height(HEADER_H))
                card.rows.forEach { rowLabel ->
                    Box(Modifier.fillMaxWidth().height(CELL_H).padding(vertical = 4.dp), contentAlignment = Alignment.CenterStart) {
                        Text(rowLabel, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            // Option columns
            val optionsModifier = if (scrollOptions) {
                Modifier.weight(1f).horizontalScroll(rememberScrollState())
            } else {
                Modifier.weight(1f)
            }
            Row(optionsModifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                card.options.forEach { opt ->
                    val colWidthModifier = if (scrollOptions) Modifier.widthIn(min = 96.dp) else Modifier.weight(1f)
                    val colModifier = if (opt.recommended) {
                        colWidthModifier
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, c.accent, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    } else {
                        colWidthModifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    }
                    Column(colModifier) {
                        Box(Modifier.fillMaxWidth().height(HEADER_H), contentAlignment = Alignment.BottomStart) {
                            Column {
                                if (opt.recommended) {
                                    Text(
                                        string(Res.string.comparison_recommended),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = c.accent,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(c.accent.copy(alpha = 0.12f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                    Spacer(Modifier.height(2.dp))
                                }
                                Text(opt.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = c.ink,
                                    maxLines = if (opt.recommended) 1 else 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        card.rows.indices.forEach { r ->
                            Box(Modifier.fillMaxWidth().height(CELL_H).padding(vertical = 4.dp), contentAlignment = Alignment.CenterStart) {
                                Text(opt.values.getOrElse(r) { "" }, style = MaterialTheme.typography.bodyMedium, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        if (card.note.isNotBlank()) {
            Text(card.note, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }

        if (card.open && card.options.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                card.options.forEach { opt ->
                    val label = string(Res.string.comparison_choose, opt.name)
                    val fallback = string(Res.string.comparison_choose_say, opt.name)
                    val action = {
                        val text = ComparisonRules.choice(opt) { fallback }
                        onChoose(text)
                    }
                    if (opt.recommended) {
                        PrimaryButton(label, onClick = action)
                    } else {
                        SecondaryButton(label, onClick = action)
                    }
                }
            }
        }
    }
}
