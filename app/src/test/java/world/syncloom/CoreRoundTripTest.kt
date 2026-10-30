package world.syncloom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.syncloom_core.Authenticator
import uniffi.syncloom_core.FfiLogin
import uniffi.syncloom_core.Session
import java.io.File
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Drives the real Rust core through the generated Kotlin bindings against a freshly started server
 * (the first registered user becomes the admin). Uses the host build of the core library.
 */
class CoreRoundTripTest {
    private val server = System.getenv("SYNCLOOM_TEST_URL") ?: "http://localhost:8088"

    private fun base32(s: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var bits = 0; var value = 0
        val out = java.io.ByteArrayOutputStream()
        for (ch in s.uppercase().filter { it != ' ' && it != '=' }) {
            value = (value shl 5) or alphabet.indexOf(ch); bits += 5
            if (bits >= 8) { out.write((value shr (bits - 8)) and 0xff); bits -= 8 }
        }
        return out.toByteArray()
    }

    private fun totp(secret: String, stepOffset: Long = 0): String {
        val counter = System.currentTimeMillis() / 30000 + stepOffset
        val msg = ByteArray(8) { i -> ((counter shr (8 * (7 - i))) and 0xff).toByte() }
        val h = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(base32(secret), "HmacSHA1")) }.doFinal(msg)
        val o = h[19].toInt() and 0xf
        val n = ((h[o].toInt() and 0x7f) shl 24) or ((h[o + 1].toInt() and 0xff) shl 16) or ((h[o + 2].toInt() and 0xff) shl 8) or (h[o + 3].toInt() and 0xff)
        return "%06d".format(n % 1_000_000)
    }

    @Test
    fun fullRoundTripThroughTheKotlinBindings() {
        val auth = Authenticator(server)
        val user = "kotlin${(1000..9999).random()}"
        val password = "correct horse battery staple"

        val reg = try { auth.register(user, password, null, "kotlin-test") } catch (e: Throwable) { fail("registration failed (is the stack fresh?): $e"); return }
        assertEquals(71, reg.recoveryKey.length)
        val enroll = (reg.login as? FfiLogin.EnrollRequired)?.token ?: return fail("expected enrollment, got ${reg.login}")

        val setup = auth.totpSetup(enroll, "test authenticator")
        val confirm = auth.totpConfirm(enroll, setup.id, totp(setup.secret), "kotlin-test")
        assertEquals(10, confirm.recoveryCodes.size)

        val s = Session.connect(server, confirm.token, password)
        assertEquals(user, s.me().username)

        // encrypted multi-chunk file round trip
        val folder = s.createFolder(null, "Docs")
        val dir = File.createTempFile("syncloom", "").apply { delete(); mkdirs() }
        val payload = ByteArray(3_000_000) { (it * 31 + (it shr 7)).toByte() }
        val src = File(dir, "big.bin").apply { writeBytes(payload) }
        val file = s.uploadNewFile(folder.id, "big.bin", src.path, 1_700_000_000_000, false)
        assertEquals(payload.size.toLong(), file.size)
        assertEquals(listOf("big.bin"), s.list(folder.id).map { it.name })
        val out = File(dir, "out/copy.bin")
        s.downloadFile(file.id, null, out.path)
        assertTrue(payload.contentEquals(out.readBytes()))

        // versions and optimistic concurrency
        val edited = File(dir, "v2.bin").apply { writeText("version two") }
        val v2 = s.uploadVersion(file.id, file.headVersion, edited.path, 1_700_000_100_000)
        assertNotEquals(file.headVersion, v2.headVersion)
        assertEquals(2, s.versions(file.id).size)
        try { s.uploadVersion(file.id, file.headVersion, edited.path, 0); fail("a stale base must be refused") } catch (_: uniffi.syncloom_core.Exception) {}

        // rename / trash / restore
        s.rename(file.id, "renamed.bin")
        s.trash(file.id)
        assertEquals(listOf("renamed.bin"), s.trashList().map { it.name })
        s.restore(file.id)

        // key/value with compare-and-set
        val ns = s.kvCreateNamespace("settings")
        assertEquals(1L, s.kvPut(ns.id, "theme", "dark".toByteArray(), null))
        try { s.kvPut(ns.id, "theme", "x".toByteArray(), 0L); fail("must conflict") } catch (_: uniffi.syncloom_core.Exception) {}
        assertEquals("dark", String(s.kvGet(ns.id, "theme")!!.value))

        // link keeps the key in the fragment
        val link = s.createLink(folder.id, null, null, null)
        assertTrue(link.url.contains("/s/") && link.url.contains("#k="))

        // the saved secrets reopen the session without the password (what the Keystore-backed store keeps)
        val restored = Session.fromSecrets(server, confirm.token, s.exportSecrets())
        assertEquals("Docs", restored.get(folder.id).name)

        // another factor, then sign in again with any one of them
        val second = s.addTotp("second")
        s.confirmTotp(second.id, totp(second.secret))
        assertEquals(2, s.mfaMethods().size)
        val mfa = auth.login(user, password, "again") as FfiLogin.MfaRequired
        assertTrue(mfa.methods.contains("totp"))
        val token = auth.mfaVerify(mfa.token, "totp", totp(setup.secret, 1), "again")
        assertEquals("Docs", Session.connect(server, token, password).get(folder.id).name)
        try { Session.connect(server, token, "nope"); fail("wrong password must not unlock") } catch (_: uniffi.syncloom_core.Exception) {}
    }
}
