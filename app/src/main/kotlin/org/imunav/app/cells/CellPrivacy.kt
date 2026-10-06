package org.imunav.app.cells

import android.content.Context
import androidx.core.content.edit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.imunav.core.net.Http
import org.imunav.core.net.HttpException
import org.json.JSONObject
import java.io.IOException

/** The operator's current notice, loaded from the selected server before an upload choice. */
data class CellPrivacyNotice(
    val version: String,
    val controller: String,
    val contact: String,
    val rightsContact: String,
    val region: String,
    val recipients: String,
    val transfers: String,
    val accountBasis: String,
    val securityBasis: String,
    val textEn: String,
    val textUk: String,
    val textRu: String,
) {
    /** Show the notice in the chosen app language, with English as the fallback. */
    fun text(language: String): String = when (language) {
        "uk" -> textUk
        "ru" -> textRu
        else -> textEn
    }
}

/** Keeps per-purpose choices local as well as on the server so a withdrawal stops capture immediately. */
internal class CellPrivacy(context: Context, private val auth: CellAuth) {
    private val prefs = context.getSharedPreferences("cell_privacy", Context.MODE_PRIVATE)

    /** Fetch public notice before registration or an upload decision. Local test servers may omit it. */
    fun notice(url: String): CellPrivacyNotice? {
        val response = request(url, "/v1/privacy", "GET", null, null) ?: run {
            prefs.edit { putBoolean("local:$url", true) }
            return null
        }
        prefs.edit { putBoolean("local:$url", false) }
        refreshRemote(url)
        return parseNotice(response)
    }

    /** Reconcile browser withdrawals and notice changes before the app uses cached consent. */
    fun refreshRemote(url: String) {
        if (auth.email == null || auth.serverUrl != url.trim().trimEnd('/')) return
        val token = auth.accessToken(url) ?: run {
            clearLocalGrants(url)
            return
        }
        val state = request(url, "/v1/privacy/me", "GET", token, null) ?: run {
            if (request(url, "/v1/privacy", "GET", null, null) != null) clearLocalGrants(url)
            return
        }
        reconcile(url, state)
    }

    /** Check the current server version and receipt before every tower upload. */
    fun canUpload(url: String, token: String): Boolean {
        val response = request(url, "/v1/privacy/me", "GET", token, null) ?: return notice(url) == null
        reconcile(url, response)
        val notice = parseNotice(response.getJSONObject("notice"))
        return response.optBoolean("tower_upload") && locallyGranted(url, "tower_upload", notice.version)
    }

    /** A browser withdrawal must also stop future diagnostic sends from this installation. */
    fun canSendDiagnostics(url: String, token: String): Boolean {
        val response = request(url, "/v1/privacy/me", "GET", token, null) ?: return notice(url) == null
        reconcile(url, response)
        val notice = parseNotice(response.getJSONObject("notice"))
        return response.optBoolean("diagnostics") && locallyGranted(url, "diagnostics", notice.version)
    }

    /** Capture stays disabled until a successful grant for this selected server and notice version. */
    fun canCaptureDiagnostics(url: String): Boolean {
        if (prefs.getBoolean("local:$url", false)) return true
        val version = prefs.getString("version:$url", null) ?: return false
        return locallyGranted(url, "diagnostics", version)
    }

    /** Whether this device has a current local receipt for the purpose. */
    fun isGranted(url: String, purpose: String, version: String): Boolean = locallyGranted(url, purpose, version)

    /** Submit a fresh receipt; a changed notice must be shown again before retrying. */
    fun grant(url: String, purpose: String, version: String) {
        val token = auth.accessToken(url) ?: throw IOException("Sign in to grant consent")
        val body = JSONObject().put("notice_version", version).toString()
        request(url, "/v1/privacy/consents/$purpose", "PUT", token, body)
        if (purpose == "tower_upload" && auth.emailVerified) auth.markSharingEnabled()
        prefs.edit {
            putString("version:$url", version)
            putBoolean("grant:$url:$purpose", true)
        }
    }

    /** Stop locally first, then queue server erasure until the authenticated request succeeds. */
    fun withdrawLocally(url: String, purpose: String) {
        prefs.edit {
            putBoolean("grant:$url:$purpose", false)
            putBoolean("pending:$url:$purpose", !prefs.getBoolean("local:$url", false))
        }
    }

    /** Retry a pending erasure when a valid account session and network become available. */
    fun flushWithdrawals(url: String) {
        if (url.isBlank()) return
        val token = auth.accessToken(url) ?: return
        for (purpose in listOf("tower_upload", "diagnostics")) {
            if (!prefs.getBoolean("pending:$url:$purpose", false)) continue
            try {
                request(url, "/v1/privacy/consents/$purpose", "DELETE", token, null)
            } catch (error: HttpException) {
                if (error.code != 404 || notice(url) != null) throw error
            }
            prefs.edit { putBoolean("pending:$url:$purpose", false) }
        }
    }

    private fun locallyGranted(url: String, purpose: String, version: String): Boolean =
        prefs.getString("version:$url", null) == version && prefs.getBoolean("grant:$url:$purpose", false) && !prefs.getBoolean("pending:$url:$purpose", false)

    private fun reconcile(url: String, state: JSONObject) {
        val version = state.getJSONObject("notice").getString("version")
        prefs.edit {
            for (purpose in listOf("tower_upload", "diagnostics")) {
                if (!state.optBoolean(purpose) || prefs.getString("version:$url", null) != version) {
                    putBoolean("grant:$url:$purpose", false)
                }
            }
        }
    }

    private fun clearLocalGrants(url: String) = prefs.edit {
        putBoolean("grant:$url:tower_upload", false)
        putBoolean("grant:$url:diagnostics", false)
    }

    private fun parseNotice(value: JSONObject): CellPrivacyNotice = CellPrivacyNotice(
        version = value.getString("version"),
        controller = value.getString("controller"),
        contact = value.getString("contact"),
        rightsContact = value.getString("rights_contact"),
        region = value.getString("region"),
        recipients = value.getString("recipients"),
        transfers = value.getString("transfers"),
        accountBasis = value.getString("account_basis"),
        securityBasis = value.getString("security_basis"),
        textEn = value.getString("notice_en"),
        textUk = value.getString("notice_uk"),
        textRu = value.getString("notice_ru"),
    )

    private fun request(url: String, path: String, method: String, token: String?, body: String?): JSONObject? {
        val base = url.trim().trimEnd('/')
        val parsed = base.toHttpUrlOrNull() ?: throw IOException("Invalid sharing server URL")
        require(parsed.scheme == "https" || (parsed.scheme == "http" && parsed.host in setOf("localhost", "127.0.0.1", "10.0.2.2"))) {
            "Account sharing requires HTTPS"
        }
        val builder = Request.Builder().url(base + path)
        if (token != null) builder.header("Authorization", "Bearer $token")
        val requestBody = body?.toRequestBody("application/json".toMediaType())
        val response = Http.client.newCall(builder.method(method, requestBody).build()).execute()
        return response.use {
            val bytes = it.body.source().readByteArray(MAX_RESPONSE_BYTES + 1L)
            if (bytes.size > MAX_RESPONSE_BYTES) throw IOException("Sharing server notice is too large")
            val text = bytes.toString(Charsets.UTF_8)
            if (it.code == 404 && path in setOf("/v1/privacy", "/v1/privacy/me")) return@use null
            if (!it.isSuccessful) throw HttpException(it.code, text.take(Http.ERROR_BODY_CHARS))
            if (text.isBlank()) null else JSONObject(text)
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}
