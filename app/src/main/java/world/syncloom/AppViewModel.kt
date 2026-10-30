package world.syncloom

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.syncloom_core.Authenticator
import uniffi.syncloom_core.FfiLogin
import uniffi.syncloom_core.FfiTotpSetup
import uniffi.syncloom_core.FfiUser
import uniffi.syncloom_core.Session

sealed interface AuthStep {
    data object Credentials : AuthStep
    data object Register : AuthStep
    data class Mfa(val token: String, val methods: List<String>) : AuthStep
    data class RecoveryKey(val key: String) : AuthStep
    data class Enroll(val token: String) : AuthStep
    data class Totp(val token: String, val setup: FfiTotpSetup) : AuthStep
    data class Codes(val codes: List<String>) : AuthStep
}

/** Sign-in state machine and the signed-in session, shared by all screens. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store get() = getApplication<SyncloomApp>().store
    private val device = android.os.Build.MODEL ?: "Android"

    var session by mutableStateOf<Session?>(null)
    var saved by mutableStateOf<SavedSession?>(null)
    var user by mutableStateOf<FfiUser?>(null)
    var step by mutableStateOf<AuthStep>(AuthStep.Credentials)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var restoring by mutableStateOf(true)

    var server by mutableStateOf(store.load()?.server.orEmpty())
    var username by mutableStateOf(store.load()?.username.orEmpty())
    var password by mutableStateOf("")
    var invite by mutableStateOf("")
    private var pendingLogin: FfiLogin? = null
    private var pendingToken: String? = null

    init {
        viewModelScope.launch {
            store.load()?.let { s ->
                runCatching { io { Session.fromSecrets(s.server, s.token, s.secrets) } }.onSuccess { sess ->
                    session = sess
                    saved = s
                    user = runCatching { io { sess.me() } }.getOrNull()
                }.onFailure { store.clear() }
            }
            restoring = false
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    val normalizedServer: String
        get() {
            var s = server.trim()
            if (s.isNotEmpty() && !s.contains("://")) s = "https://$s"
            return s.trimEnd('/')
        }

    private fun launch(block: suspend () -> Unit) {
        busy = true
        error = null
        viewModelScope.launch {
            try { block() } catch (e: Throwable) { error = e.message ?: e.toString() }
            busy = false
        }
    }

    private val auth get() = Authenticator(normalizedServer)

    fun signIn() = launch {
        val (u, p) = username.trim() to password
        handle(io { auth.login(u, p, device) })
    }

    fun register() = launch {
        val (u, p, inv) = Triple(username.trim(), password, invite.trim())
        val reg = io { auth.register(u, p, inv.ifEmpty { null }, device) }
        pendingLogin = reg.login
        step = AuthStep.RecoveryKey(reg.recoveryKey)
    }

    fun continueAfterRecoveryKey() = launch { pendingLogin?.let { handle(it) } }

    private suspend fun handle(o: FfiLogin) {
        when (o) {
            is FfiLogin.Ok -> open(o.token)
            is FfiLogin.MfaRequired -> step = AuthStep.Mfa(o.token, o.methods)
            is FfiLogin.EnrollRequired -> step = AuthStep.Enroll(o.token)
        }
    }

    fun verify(token: String, method: String, code: String) = launch {
        open(io { auth.mfaVerify(token, method, code, device) })
    }

    fun beginTotp(token: String) = launch {
        step = AuthStep.Totp(token, io { auth.totpSetup(token, "$device authenticator") })
    }

    fun confirmTotp(token: String, setup: FfiTotpSetup, code: String) = launch {
        val r = io { auth.totpConfirm(token, setup.id, code, device) }
        pendingToken = r.token
        step = AuthStep.Codes(r.recoveryCodes)
    }

    fun finishAfterCodes() = launch { pendingToken?.let { open(it) } }

    private suspend fun open(token: String) {
        val server = normalizedServer
        val p = password
        val sess = io { Session.connect(server, token, p) }
        val s = SavedSession(server, username.lowercase(), token, sess.exportSecrets())
        store.save(s)
        password = ""
        saved = s
        session = sess
        user = runCatching { io { sess.me() } }.getOrNull()
        step = AuthStep.Credentials
    }

    fun refreshUser() {
        val s = session ?: return
        viewModelScope.launch { user = runCatching { io { s.me() } }.getOrNull() }
    }

    fun signOut() {
        session?.let { s -> viewModelScope.launch(Dispatchers.IO) { runCatching { s.logout() } } }
        store.clear()
        session = null
        saved = null
        user = null
        step = AuthStep.Credentials
    }
}
