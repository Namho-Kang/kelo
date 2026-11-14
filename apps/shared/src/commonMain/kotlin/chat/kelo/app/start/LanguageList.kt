package chat.kelo.app.start

import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import chat.kelo.app.core.Languages
import chat.kelo.app.ui.theme.Kelo

/** The language list (.menu-card.lang-list) of My -> Language: following the phone first, then every language under its own name. */
@Composable
fun LanguageList(value: String, systemCode: String, onChange: (String) -> Unit, label: String) {
    val c = Kelo.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(c.card)
            .border(1.dp, c.line, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = label }
            .selectableGroup(),
    ) {
        // first: follow the phone, with the language that means right now (English when the app lacks the phone's language)
        LanguageRow(
            name = string(Res.string.language_system),
            detail = Languages.name(systemCode),
            selected = value == Languages.SYSTEM,
            onClick = { onChange(Languages.SYSTEM) },
        )
        Languages.ALL.forEach { lang ->
            HorizontalDivider(color = c.line)
            // each name in its own language and script, so it reads right (and in the right direction) whatever the app's language
            LanguageRow(name = lang.name, detail = null, selected = value == lang.code, onClick = { onChange(lang.code) },
                locale = LocaleList(Locale(lang.code)))
        }
    }
}

@Composable
private fun LanguageRow(name: String, detail: String?, selected: Boolean, onClick: () -> Unit, locale: LocaleList? = null) {
    val c = Kelo.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val style = MaterialTheme.typography.bodyLarge
            Text(name, style = if (locale != null) style.copy(localeList = locale, textDirection = TextDirection.Content) else style)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        if (selected) Text(string(Res.string.check_mark), color = c.ink, style = MaterialTheme.typography.bodyLarge)
    }
}
