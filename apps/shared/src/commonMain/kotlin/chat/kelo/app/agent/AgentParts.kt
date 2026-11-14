package chat.kelo.app.agent

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*


import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import chat.kelo.app.LocalAppServices
import chat.kelo.app.chat.AgentActivityRules
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Profile
import chat.kelo.app.notify.OpenTarget
import chat.kelo.app.ui.character.KeloCharacter
import chat.kelo.app.ui.character.KeloMood
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.LocalHaptics
import chat.kelo.app.ui.theme.Kelo
import chat.kelo.app.ui.theme.MotionSpecs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The agent's picture, or Kelo's character (in [mood]) when it has none. */
@Composable
fun AgentPicture(
    name: String, image: androidx.compose.ui.graphics.ImageBitmap?, sizeDp: Int, modifier: Modifier = Modifier,
    mood: KeloMood = KeloMood.Idle,
) {
    if (image != null) {
        Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.size(sizeDp.dp).clip(CircleShape))
    } else {
        KeloCharacter(mood, modifier.size(sizeDp.dp))
    }
}

@Composable
fun SheetRow(icon: ImageVector, text: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = Kelo.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (danger) c.danger else c.ink)
        Text(text, color = if (danger) c.danger else c.ink, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Renames the agent (PUT /api/profile). */
@Composable
fun RenameDialog(profile: Profile, onProfile: (Profile) -> Unit, onClose: () -> Unit) {
    val c = Kelo.colors
    val container = LocalAppServices.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(profile.agentName) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val failed = string(Res.string.connect_failed)
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = c.card,
        title = { Text(string(Res.string.agent_rename_title), color = c.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KeloTextField(name, { name = it }, string(Res.string.agent_rename_placeholder), maxLength = 60)
                error?.let { Text(it, color = c.danger, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !saving, onClick = {
                saving = true
                scope.launch {
                    try {
                        val p = container.sessions.requireApi().put<Profile>("/api/profile", buildJsonObject { put("agent_name", name.trim()) })
                        onProfile(p)
                        onClose()
                    } catch (e: ApiError) {
                        error = if (e.isNetwork) failed else e.detail
                    } finally {
                        saving = false
                    }
                }
            }) { Text(string(Res.string.save), color = c.accent) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(string(Res.string.cancel), color = c.ink) } },
    )
}

/** A full-screen page: back, title, and the content (status and navigation bars kept clear). */
@Composable
fun AgentSubPage(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(Kelo.colors.bg).safeDrawingPadding()) {
        chat.kelo.app.ui.components.BackTitleBar(title, onBack, actions = { actions() })
        Box(Modifier.fillMaxSize()) { content() }
    }
}
