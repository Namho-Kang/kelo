package chat.kelo.app.chat

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import chat.kelo.app.ui.platform.LocalReloadOnReturn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 로그인 for a provider reached by signing in (Claude, Antigravity, ChatGPT, Qwen, MiniMax), all on the phone: the consent notice
 * first (when not given yet), then the provider's page in the browser and either a code to type there (the app notices by itself)
 * or a field to paste the code the page shows. [onSignedIn] runs once it worked (the caller loads the providers again; the row then
 * shows the account and 로그아웃 instead of this box).
 */
@Composable
fun SignInBox(provider: ProviderInfo, gate: ConsentGate, onSignedIn: () -> Unit) {
    val sessions = LocalAppServices.current.sessions
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val done by rememberUpdatedState(onSignedIn)
    val path = "/api/providers/${KeloApi.segment(provider.name)}/login"

    var s by remember(provider.name) { mutableStateOf<SignInSession?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var paste by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    var deadline by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val browserFailed = string(Res.string.connector_browser_failed)
    val failedText = string(Res.string.signin_failed)
    val alreadyText = string(Res.string.signin_already)

    fun take(next: SignInSession) {
        s = next
        if (next.state == "pending") deadline = System.currentTimeMillis() + next.expiresIn * 1000
        if (SignInRules.finished(next)) done()
    }

    fun fail(e: ApiError) {
        err = when (SignInRules.problem(e)) {
            SignInRules.Problem.ALREADY -> alreadyText
            else -> SignInRules.readable(e).ifEmpty { failedText }
        }
    }

    fun start() {
        if (busy) return
        busy = true; err = ""; paste = ""; copied = false
        scope.launch {
            try {
                take(sessions.requireApi().post<SignInSession>("$path/start", buildJsonObject {}))
            } catch (e: ApiError) {
                if (SignInRules.problem(e) == SignInRules.Problem.CONSENT) {
                    busy = false
                    gate.request(provider.copy(consent = null)) { start() }   // recorded elsewhere meanwhile, or asked again
                    return@launch
                }
                if (SignInRules.problem(e) == SignInRules.Problem.ALREADY) done()
                fail(e)
            } finally {
                busy = false
            }
        }
    }

    fun finish() {
        val cur = s ?: return
        busy = true; err = ""
        scope.launch {
            try {
                take(sessions.requireApi().post<SignInSession>("$path/finish", buildJsonObject {
                    put("session", cur.session); put("code", paste.trim())
                }))
                paste = ""
            } catch (e: ApiError) {
                if (e.status == 404 || e.status == 410) s = cur.copy(state = "expired")
                fail(e)
            } finally {
                busy = false
            }
        }
    }

    fun cancel() {
        val cur = s ?: return
        s = null; err = ""; paste = ""
        scope.launch { runCatching { sessions.requireApi().post<kotlinx.serialization.json.JsonElement>("$path/cancel", buildJsonObject { put("session", cur.session) }) } }
    }

    suspend fun refresh() {
        val cur = s ?: return
        if (cur.state != "pending" || !SignInRules.isDeviceCode(cur)) return
        try {
            take(sessions.requireApi().get<SignInSession>("$path/status?session=${KeloApi.segment(cur.session)}"))
        } catch (e: ApiError) {
            if (e.status == 404) s = cur.copy(state = "expired")
        }
    }

    // a code typed on the provider's page: ask every third second while it waits, and at once when the user comes back to the app
    val waiting = s?.let { it.state == "pending" } == true
    LocalReloadOnReturn.current(s?.session) { refresh() }
    LaunchedEffect(s?.session, waiting) {
        if (!waiting) return@LaunchedEffect
        var ticks = 0
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
            if (++ticks % 3 == 0) refresh()
            if (s?.state != "pending") break
        }
    }

    val name = provider.displayName.ifBlank { string(Res.string.signin_this_service) }
    val cur = s
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            cur == null -> {
                MutedText(string(Res.string.signin_intro, name), small = true)
                PrimaryButton(string(if (busy) Res.string.signin_starting else Res.string.signin_button), onClick = { gate.request(provider) { start() } }, enabled = !busy)
            }
            SignInRules.finished(cur) -> MutedText(
                if (cur.account.isNullOrBlank()) string(Res.string.signin_done) else string(Res.string.signin_done_account, cur.account!!),
                small = true,
            )
            SignInRules.ended(cur) -> {
                ErrorText(cur.message?.takeIf { it.isNotBlank() } ?: string(Res.string.signin_ended), small = true)
                PrimaryButton(string(Res.string.signin_again), onClick = ::start, enabled = !busy)
            }
            else -> {
                val secs = SignInRules.secondsLeft(deadline, now)
                val device = SignInRules.isDeviceCode(cur)
                Text(string(Res.string.signin_step_open, name), style = MaterialTheme.typography.bodyMedium, color = Kelo.colors.ink)
                PrimaryButton(string(Res.string.signin_open_browser), onClick = { if (runCatching { uriHandler.openUri(cur.url) }.isFailure) err = browserFailed })
                if (device) {
                    val code = cur.userCode.orEmpty()
                    if (code.isNotEmpty()) {
                        Text(string(Res.string.signin_step_code), style = MaterialTheme.typography.bodyMedium, color = Kelo.colors.ink)
                        CodeBlock(code)
                        SecondaryButton(
                            string(if (copied) Res.string.device_copied else Res.string.device_copy),
                            onClick = { clipboard.setText(AnnotatedString(code)); copied = true }, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    MutedText(string(Res.string.signin_device_wait, SignInRules.mmss(secs)), small = true)
                    LinkButton(string(Res.string.signin_check_now), onClick = ::finish)
                } else {
                    Text(string(Res.string.signin_step_paste), style = MaterialTheme.typography.bodyMedium, color = Kelo.colors.ink)
                    KeloTextField(
                        paste, { paste = it; err = "" }, string(Res.string.signin_paste_placeholder), maxLength = 4096,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(
                            string(Res.string.signin_paste), modifier = Modifier.weight(1f),
                            onClick = { clipboard.getText()?.text?.trim()?.let { if (it.isNotEmpty()) { paste = it; err = "" } } },
                        )
                        PrimaryButton(
                            string(if (busy) Res.string.signin_checking else Res.string.signin_finish), onClick = ::finish,
                            enabled = !busy && SignInRules.pasteReady(paste), modifier = Modifier.weight(1f),
                        )
                    }
                    MutedText(string(Res.string.signin_paste_help, SignInRules.mmss(secs)), small = true)
                }
                LinkButton(string(Res.string.cancel), onClick = ::cancel)
            }
        }
        if (err.isNotEmpty()) ErrorText(err, small = true)
    }
}

@Composable
private fun CodeBlock(code: String) {
    val c = Kelo.colors
    val label = string(Res.string.device_code_label)
    SelectionContainer {
        Text(
            code,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.bubble)
                .padding(horizontal = 8.dp, vertical = 14.dp).semantics { contentDescription = label },
            color = c.ink, textAlign = TextAlign.Center, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = 3.sp,
        )
    }
}
