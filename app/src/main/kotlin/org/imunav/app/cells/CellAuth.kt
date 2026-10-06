package org.imunav.app.cells

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.imunav.core.net.Http
import org.imunav.core.net.HttpException
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps the renewable sharing session encrypted at rest while access tokens stay in memory. */
internal class CellAuth(context: Context) {
    private val prefs = context.getSharedPreferences("cell_auth", Context.MODE_PRIVATE)
    private var access: String? = null
    private var expiresAtMs = 0L

    val email: String? get() = prefs.getString("email", null)
    val serverUrl: String? get() = prefs.getString("url", null)
    val emailVerified: Boolean get() = prefs.getBoolean("email_verified", true)
    val sharingEnabled: Boolean get() = prefs.getBoolean("sharing_enabled", true)

    /** Cache a server-side upload restriction until the next session refresh checks it again. */
    fun markUploadBlocked(body: String) {
        prefs.edit {
            if (body.contains("EMAIL_UNVERIFIED")) putBoolean("email_verified", false)
            if (body.contains("SHARING_DISABLED")) putBoolean("sharing_enabled", false)
        }
    }

    /** A successful tower consent re-enables sharing on the server when email is verified. */
    fun markSharingEnabled() = prefs.edit { putBoolean("sharing_enabled", true) }

    /** A changed server cannot receive a token issued by the previous server. */
    fun clear() {
        access = null
        expiresAtMs = 0
        prefs.edit { clear() }
    }

    /** Sign in or register, storing only the refresh token persistently. Called on an I/O thread. */
    fun authenticate(url: String, email: String, password: String, register: Boolean) {
        val endpoint = if (register) "register" else "login"
        val body = JSONObject().put("email", email).put("password", password)
        val result = post(url, "/v1/auth/$endpoint", body)
        saveSession(url, result)
    }

    /** Obtain a live access token; a failed refresh clears the local session. Called on an I/O thread. */
    @Synchronized
    fun accessToken(url: String): String? {
        if (prefs.getString("url", null) != url.trim().trimEnd('/')) {
            clear()
            return null
        }
        if (System.currentTimeMillis() < expiresAtMs - 30_000) return access
        val refresh = decrypt(prefs.getString("refresh", null)) ?: run {
            clear()
            return null
        }
        return try {
            saveSession(url, post(url, "/v1/auth/refresh", JSONObject().put("refresh_token", refresh)))
            access
        } catch (error: HttpException) {
            if (error.code == 401) clear()
            throw error
        }
    }

    /** Revoke the server session before discarding local credentials. Called on an I/O thread. */
    fun signOut(url: String) {
        val refresh = decrypt(prefs.getString("refresh", null))
        try {
            if (refresh != null) post(url, "/v1/auth/logout", JSONObject().put("refresh_token", refresh))
        } finally {
            clear()
        }
    }

    /** Recovery emails contain a short-lived link to the server's reset page. */
    fun requestReset(url: String, email: String) {
        post(url, "/v1/auth/password-reset/request", JSONObject().put("email", email))
    }

    private fun saveSession(url: String, body: JSONObject) {
        val refresh = body.getString("refresh_token")
        val encrypted = encrypt(refresh)
        prefs.edit {
            putString("url", url.trim().trimEnd('/'))
            putString("email", body.getJSONObject("account").getString("email"))
            putBoolean("email_verified", body.getJSONObject("account").optBoolean("email_verified", true))
            putBoolean("sharing_enabled", body.getJSONObject("account").optBoolean("sharing_enabled", true))
            putString("refresh", encrypted)
        }
        access = body.getString("access_token")
        expiresAtMs = System.currentTimeMillis() + body.getLong("expires_in") * 1000
    }

    private fun post(url: String, path: String, body: JSONObject): JSONObject {
        val base = url.trim().trimEnd('/')
        val parsed = base.toHttpUrlOrNull() ?: error("Invalid sharing server URL")
        require(parsed.scheme == "https" || (parsed.scheme == "http" && parsed.host in setOf("localhost", "127.0.0.1", "10.0.2.2"))) {
            "Account sign-in requires HTTPS"
        }
        val request = Request.Builder().url(base + path).post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return Http.client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw HttpException(response.code, text.take(Http.ERROR_BODY_CHARS))
            JSONObject(text)
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    private fun decrypt(value: String?): String? = runCatching {
        val bytes = Base64.getDecoder().decode(value ?: return null)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val KEY_ALIAS = "imu-nav-cell-sharing-refresh-v1"
    }
}
