package chat.kelo.app.my

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import chat.kelo.app.core.SourceLink
import chat.kelo.app.res.*
import chat.kelo.app.ui.text.string
import chat.kelo.app.ui.theme.Kelo

/**
 * The version lines at the bottom of My: "App version: 0.9.3 · a1b2c3d" and the server's version. The short hash is the commit the app was
 * built from: a link to that source on GitHub for a clean build ([openUrl] opens it in the system browser), plain "a1b2c3d (modified)"
 * for a build from changed files, and nothing at all when the build did not say ([SourceLink], docs/versioning.md).
 */
@Composable
fun AppVersionLine(
    appVersion: String, serverVersion: String?, sourceCommit: String, sourceDirty: Boolean, openUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Kelo.colors
    val style = MaterialTheme.typography.bodySmall
    val app = appVersion.takeIf { it.isNotEmpty() }
    val server = serverVersion?.takeIf { it.isNotEmpty() }
    if (app == null && server == null) return
    val label = SourceLink.label(sourceCommit, sourceDirty, string(Res.string.my_source_modified))
    val url = SourceLink.url(sourceCommit, sourceDirty)
    Column(modifier) {
        if (app != null) {
            Row {
                Text(string(Res.string.my_version, app) + (if (label != null) " · " else ""), color = c.muted, style = style)
                if (label != null) {
                    if (url != null) {
                        val description = string(Res.string.my_source_link)
                        Text(
                            label, color = c.accent, style = style, textDecoration = TextDecoration.Underline,
                            modifier = Modifier
                                .clickable(onClickLabel = description, role = Role.Button) { openUrl(url) }
                                .semantics { contentDescription = description },
                        )
                    } else {
                        Text(label, color = c.muted, style = style)
                    }
                }
            }
        }
        if (server != null) Text(string(Res.string.my_server_version, server), color = c.muted, style = style)
    }
}
