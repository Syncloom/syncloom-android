package world.syncloom.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.syncloom_core.FfiMfaMethod
import uniffi.syncloom_core.FfiTotpSetup
import world.syncloom.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val session = vm.session ?: return
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var methods by remember { mutableStateOf<List<FfiMfaMethod>>(emptyList()) }
    var setup by remember { mutableStateOf<FfiTotpSetup?>(null) }
    var codes by remember { mutableStateOf<List<String>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }

    suspend fun load() { methods = runCatching { withContext(Dispatchers.IO) { session.mfaMethods() } }.getOrDefault(emptyList()) }
    LaunchedEffect(Unit) { load() }
    fun <T> run(block: () -> T, then: suspend (T) -> Unit = {}) = scope.launch {
        try { then(withContext(Dispatchers.IO) { block() }) } catch (e: Throwable) { message = e.message }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            Text("Account", Modifier.padding(16.dp, 12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            ListItem({ Text(vm.saved?.username ?: "") }, supportingContent = { Text(vm.saved?.server ?: "") })
            vm.user?.let { u ->
                val used = Formatter.formatShortFileSize(ctx, u.usedBytes)
                ListItem({ Text("Storage") }, supportingContent = { Text(u.quotaBytes?.let { "$used of ${Formatter.formatShortFileSize(ctx, it)}" } ?: "$used used") })
            }
            HorizontalDivider()
            Text("Two-step verification", Modifier.padding(16.dp, 12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            methods.forEach { m ->
                ListItem(
                    { Text(m.name) },
                    supportingContent = { Text(if (m.kind == "webauthn") "Security key" else "Authenticator app") },
                    leadingContent = { Icon(if (m.kind == "webauthn") Icons.Filled.Key else Icons.Filled.Schedule, null) },
                    trailingContent = { TextButton({ run({ session.removeMfa(m.id) }) { load() } }, enabled = methods.size > 1) { Text("Remove") } }
                )
            }
            Text("Add as many as you like. Any one of them signs you in. Security keys can be added in the web app.", Modifier.padding(16.dp, 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(16.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                OutlinedButton({ run({ session.addTotp("Authenticator app") }) { setup = it } }) { Text("Add authenticator") }
                OutlinedButton({ run({ session.newRecoveryCodes() }) { codes = it } }) { Text("New recovery codes") }
            }
            HorizontalDivider()
            Button({ confirmSignOut = true }, Modifier.fillMaxWidth().padding(16.dp), colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) { Text("Sign out") }
        }
    }

    setup?.let { s ->
        var code by remember(s.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { setup = null },
            title = { Text("Add authenticator") },
            text = {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    androidx.compose.foundation.Image(remember(s.otpauthUrl) { qrBitmap(s.otpauthUrl).asImageBitmap() }, "QR code", Modifier.size(200.dp))
                    Text(s.secret.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace)
                    OutlinedTextField(code, { code = it }, label = { Text("6-digit code") }, singleLine = true)
                }
            },
            confirmButton = { TextButton({ run({ session.confirmTotp(s.id, code.filter { it.isDigit() }) }) { setup = null; load() } }, enabled = code.count { it.isDigit() } >= 6) { Text("Add") } },
            dismissButton = { TextButton({ setup = null }) { Text("Cancel") } }
        )
    }
    codes?.let { list ->
        AlertDialog(
            onDismissRequest = { codes = null },
            title = { Text("Recovery codes") },
            text = { Column { Text("Each works once. Store them like a password."); list.forEach { Text(it, fontFamily = FontFamily.Monospace) } } },
            confirmButton = { TextButton({ codes = null }) { Text("Done") } }
        )
    }
    message?.let { AlertDialog(onDismissRequest = { message = null }, title = { Text("Something went wrong") }, text = { Text(it) }, confirmButton = { TextButton({ message = null }) { Text("OK") } }) }
    if (confirmSignOut) AlertDialog(
        onDismissRequest = { confirmSignOut = false },
        title = { Text("Sign out of Syncloom?") },
        text = { Text("You will need your password and a second factor to sign in again.") },
        confirmButton = { TextButton({ confirmSignOut = false; vm.signOut() }) { Text("Sign out") } },
        dismissButton = { TextButton({ confirmSignOut = false }) { Text("Cancel") } }
    )
}
