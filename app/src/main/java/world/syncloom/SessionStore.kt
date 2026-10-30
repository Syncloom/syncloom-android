package world.syncloom

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What is needed to reopen a session: server, device token and the unlocked vault keys. */
data class SavedSession(val server: String, val username: String, val token: String, val secrets: ByteArray)

/**
 * Keeps the saved session in SharedPreferences, encrypted with an AES-GCM key that never leaves the
 * Android Keystore (hardware-backed where available).
 */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun save(s: SavedSession) {
        val json = JSONObject()
            .put("server", s.server).put("username", s.username).put("token", s.token)
            .put("secrets", Base64.encodeToString(s.secrets, Base64.NO_WRAP))
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = c.doFinal(json.toString().toByteArray())
        prefs.edit().putString("blob", Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)).apply()
    }

    fun load(): SavedSession? = try {
        val raw = Base64.decode(prefs.getString("blob", null) ?: return null, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12)) }
        val j = JSONObject(String(c.doFinal(raw, 12, raw.size - 12)))
        SavedSession(j.getString("server"), j.getString("username"), j.getString("token"), Base64.decode(j.getString("secrets"), Base64.NO_WRAP))
    } catch (e: Throwable) {
        null
    }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val ALIAS = "syncloom-session"
    }
}
