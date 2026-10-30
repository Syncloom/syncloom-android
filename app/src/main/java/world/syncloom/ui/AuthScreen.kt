package world.syncloom.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import uniffi.syncloom_core.FfiTotpSetup
import world.syncloom.AppViewModel
import world.syncloom.AuthStep

fun qrBitmap(text: String, px: Int = 512): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px)
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.RGB_565)
    for (x in 0 until px) for (y in 0 until px) bmp.setPixel(x, y, if (m[x, y]) AColor.rgb(20, 20, 43) else AColor.WHITE)
    return bmp
}

@Composable
fun AuthScreen(vm: AppViewModel) {
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.padding(horizontal = 28.dp, vertical = 40.dp).verticalScroll(rememberScrollState()).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                BrandMark(84.dp)
                val (title, sub) = when (vm.step) {
                    AuthStep.Credentials -> "Welcome back" to "Sign in to your encrypted storage."
                    AuthStep.Register -> "Create your account" to "Your files are encrypted on this device before they leave it."
                    is AuthStep.Mfa -> "Verify it’s you" to "Use any one of your second factors."
                    is AuthStep.RecoveryKey -> "Save your recovery key" to "If you forget your password, this key is the only way to get your files back."
                    is AuthStep.Enroll -> "Add a second factor" to "Every sign-in needs your password and a second factor. You can add more later."
                    is AuthStep.Totp -> "Set up your authenticator" to "Scan the code, then enter the 6 digits."
                    is AuthStep.Codes -> "You are protected" to "Keep these one-time recovery codes in case you lose your devices."
                }
                Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                when (val s = vm.step) {
                    AuthStep.Credentials, AuthStep.Register -> Credentials(vm, s == AuthStep.Register)
                    is AuthStep.Mfa -> MfaStep(vm, s)
                    is AuthStep.RecoveryKey -> RecoveryKeyStep(vm, s.key)
                    is AuthStep.Enroll -> Button({ vm.beginTotp(s.token) }, Modifier.fillMaxWidth(), enabled = !vm.busy) { Text("Authenticator app") }
                    is AuthStep.Totp -> TotpStep(vm, s.token, s.setup)
                    is AuthStep.Codes -> CodesStep(vm, s.codes)
                }
                vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
                if (vm.busy) CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun Credentials(vm: AppViewModel, registering: Boolean) {
    OutlinedTextField(vm.server, { vm.server = it }, Modifier.fillMaxWidth(), label = { Text("Server") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
    OutlinedTextField(vm.username, { vm.username = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true)
    OutlinedTextField(vm.password, { vm.password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
    if (registering) OutlinedTextField(vm.invite, { vm.invite = it }, Modifier.fillMaxWidth(), label = { Text("Invite code") }, singleLine = true)
    Button({ if (registering) vm.register() else vm.signIn() }, Modifier.fillMaxWidth(), enabled = !vm.busy && vm.server.isNotBlank() && vm.username.isNotBlank() && vm.password.isNotEmpty()) {
        Text(if (registering) "Create account" else "Sign in")
    }
    TextButton({ vm.step = if (registering) AuthStep.Credentials else AuthStep.Register }) {
        Text(if (registering) "I already have an account" else "Create an account with an invite")
    }
}

@Composable
private fun MfaStep(vm: AppViewModel, s: AuthStep.Mfa) {
    var recovery by remember { mutableStateOf(!s.methods.contains("totp")) }
    var code by remember { mutableStateOf("") }
    if (s.methods.contains("webauthn") && !s.methods.contains("totp")) {
        Text("Security keys are available in the web app. Use a recovery code here.", style = MaterialTheme.typography.bodySmall)
    }
    OutlinedTextField(
        code, { code = it; if (!recovery && it.count { c -> c.isDigit() } == 6) vm.verify(s.token, "totp", it.filter { c -> c.isDigit() }) },
        Modifier.fillMaxWidth(), label = { Text(if (recovery) "Recovery code" else "6-digit code") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (recovery) KeyboardType.Ascii else KeyboardType.NumberPassword)
    )
    Button({ vm.verify(s.token, if (recovery) "recovery" else "totp", code.trim()) }, Modifier.fillMaxWidth(), enabled = !vm.busy && code.isNotBlank()) { Text("Verify") }
    if (s.methods.contains("totp")) TextButton({ recovery = !recovery; code = "" }) { Text(if (recovery) "Use my authenticator app" else "Use a recovery code") }
    TextButton({ vm.step = AuthStep.Credentials }) { Text("Use a different account") }
}

@Composable
private fun RecoveryKeyStep(vm: AppViewModel, key: String) {
    var ack by remember { mutableStateOf(false) }
    val clip = LocalClipboardManager.current
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Text(key.chunked(17).joinToString("\n"), Modifier.padding(18.dp), fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
    }
    OutlinedButton({ clip.setText(AnnotatedString(key)) }) { Text("Copy") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(ack, { ack = it })
        Text("I have stored it somewhere safe")
    }
    Button({ vm.continueAfterRecoveryKey() }, Modifier.fillMaxWidth(), enabled = ack && !vm.busy) { Text("Continue") }
}

@Composable
private fun TotpStep(vm: AppViewModel, token: String, setup: FfiTotpSetup) {
    var code by remember { mutableStateOf("") }
    val bmp = remember(setup.otpauthUrl) { qrBitmap(setup.otpauthUrl).asImageBitmap() }
    Surface(shape = RoundedCornerShape(16.dp), color = androidx.compose.ui.graphics.Color.White) {
        Image(bmp, "QR code for your authenticator app", Modifier.padding(10.dp).size(200.dp))
    }
    Text(setup.secret.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace)
    OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), label = { Text("6-digit code") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
    Button({ vm.confirmTotp(token, setup, code.filter { it.isDigit() }) }, Modifier.fillMaxWidth(), enabled = !vm.busy && code.count { it.isDigit() } >= 6) { Text("Verify and continue") }
}

@Composable
private fun CodesStep(vm: AppViewModel, codes: List<String>) {
    val clip = LocalClipboardManager.current
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            codes.forEach { Text(it, fontFamily = FontFamily.Monospace) }
        }
    }
    OutlinedButton({ clip.setText(AnnotatedString(codes.joinToString("\n"))) }) { Text("Copy all") }
    Button({ vm.finishAfterCodes() }, Modifier.fillMaxWidth(), enabled = !vm.busy) { Text("Open my files") }
}
