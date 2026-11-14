package chat.kelo.app.my

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import chat.kelo.app.ui.components.MyCard
import chat.kelo.app.ui.components.KeyBox
import chat.kelo.app.ui.components.SubPage
import chat.kelo.app.ui.components.IconTitle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import chat.kelo.app.LocalAppServices
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Profile
import chat.kelo.app.start.LanguageList
import chat.kelo.app.ui.platform.keloViewModel
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Plain-language answers to "what happens to my data". Everything here is something the app really does. */
@Composable
fun PrivacyPage(onBack: () -> Unit) {
    val titles = stringArray(Res.array.privacy_titles)
    val bodies = stringArray(Res.array.privacy_bodies)
    SubPage(string(Res.string.privacy_title), onBack) {
        MyCard {
            titles.zip(bodies).forEachIndexed { i, (t, d) ->
                KeyBox(first = i == 0) {
                    IconTitle(KeloIcons.Shield, t)
                    MutedText(d, small = true)
                    if (i == 0) {
                        MutedText(string(Res.string.my_privacy_keyring), small = true)
                    }
                }
            }
        }
    }
}

/** My name and the assistant's name (PUT /api/profile). */
@Composable
fun ProfilePage(profile: Profile, onProfile: (Profile) -> Unit, onBack: () -> Unit) {
    val sessions = LocalAppServices.current.sessions
    val vm = keloViewModel("my-profile") { ProfileViewModel(sessions) }
    val ui by vm.state.collectAsStateWithLifecycle()
    var user by rememberSaveable { mutableStateOf(profile.userName) }
    var agent by rememberSaveable { mutableStateOf(profile.agentName) }
    val saved = ui.saved
    val err = ui.error
    fun save() = vm.save(user, agent, onProfile)
    SubPage(string(Res.string.profile_title), onBack) {
        MyCard {
            FieldLabel(string(Res.string.profile_user_name))
            KeloTextField(user, { vm.edited(); user = it }, string(Res.string.profile_user_name), maxLength = 60)
            FieldLabel(string(Res.string.profile_agent_name))
            KeloTextField(agent, { vm.edited(); agent = it }, string(Res.string.profile_agent_name), maxLength = 60)
            PrimaryButton(string(if (saved) Res.string.saved_check else Res.string.save), onClick = ::save, enabled = agent.isNotBlank())
            if (err.isNotEmpty()) ErrorText(err, small = true)
        }
    }
}

/** 앱 → 언어: the language registry. A change is kept on this phone and the screen is made again in that language. */
@Composable
fun LanguagePage(value: String, systemCode: String, onChange: (String) -> Unit, onBack: () -> Unit) {
    val title = string(Res.string.my_language)
    SubPage(title, onBack) {
        LanguageList(value = value, systemCode = systemCode, onChange = onChange, label = title)
    }
}

/** A form label (".form label"). */
@Composable
fun FieldLabel(text: String) {
    Text(text, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = Kelo.colors.ink)
}
