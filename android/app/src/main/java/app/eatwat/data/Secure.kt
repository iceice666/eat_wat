package app.eatwat.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.eatwat.core.Session
import app.eatwat.core.SessionStore
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Credentials and the LibreLinkUp session, encrypted with an AES-GCM key that
 * lives in the Android Keystore (not exportable). Backups are disabled in the manifest.
 */
class SecureStore(ctx: Context) : SessionStore {
    private val prefs = ctx.applicationContext.getSharedPreferences("secure", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(enc: String): String? = runCatching {
        val all = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
        String(c.doFinal(all, 12, all.size - 12))
    }.getOrNull()

    private fun put(k: String, v: String?) = prefs.edit().apply { if (v == null) remove(k) else putString(k, encrypt(v)) }.apply()
    private fun read(k: String): String? = prefs.getString(k, null)?.let(::decrypt)

    var email: String
        get() = read("email").orEmpty()
        set(v) = put("email", v)

    var password: String
        get() = read("password").orEmpty()
        set(v) = put("password", v)

    val hasCredentials: Boolean get() = email.isNotBlank() && password.isNotEmpty()

    override fun load(): Session? = read("session")?.let {
        runCatching {
            val o = JSONObject(it)
            Session(o.getString("token"), o.getLong("expires"), o.getString("accountId"), o.optString("region"))
        }.getOrNull()
    }

    override fun save(s: Session?) = put(
        "session",
        s?.let { JSONObject().put("token", it.token).put("expires", it.expiresMs).put("accountId", it.accountId).put("region", it.region).toString() },
    )

    companion object {
        private const val ALIAS = "eatwat-secrets"
    }
}
