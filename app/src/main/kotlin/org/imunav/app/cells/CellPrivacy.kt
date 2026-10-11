package org.imunav.app.cells

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.imunav.core.diagnostics.DiagnosticOwner
import org.imunav.core.diagnostics.DiagnosticOwnerChanged
import org.imunav.core.diagnostics.DiagnosticRequestGate
import org.imunav.core.net.Http
import org.imunav.core.net.HttpException
import org.imunav.core.privacy.ConsentLedger
import org.imunav.core.privacy.ConsentStorage
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
internal class CellPrivacy(private val prefs: SharedPreferences, private val auth: PrivacyAccount) {
    constructor(context: Context, auth: CellAuth) : this(context.getSharedPreferences("cell_privacy", Context.MODE_PRIVATE), auth)

    private val ledger = ConsentLedger(object : ConsentStorage {
        override fun read(key: String): String? = prefs.getString(key, null)
        override fun write(values: Map<String, String?>) = prefs.edit {
            values.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) }
        }
    })
    private val requests = DiagnosticRequestGate(auth::diagnosticOwner) { true }

    init {
        auth.onPrivacyIdentityChanged = requests::cancelAll
    }

    /** Notice capability belongs to a server; personal receipts always belong to an authenticated owner. */
    fun notice(url: String): CellPrivacyNotice? {
        val base = url.trim().trimEnd('/')
        val response = request(base, "/v1/privacy", "GET", null, null) ?: run {
            prefs.edit { putBoolean("local:$base", true) }
            return null
        }
        prefs.edit { putBoolean("local:$base", false) }
        refreshRemote(base)
        return parseNotice(response)
    }

    /** Reconcile browser withdrawals only into the account whose token fetched the response. */
    fun refreshRemote(url: String) {
        val owner = owner(url) ?: return
        val token = auth.diagnosticAccessToken(owner) ?: return
        val receipts = receiptStamps(owner)
        val state = request(owner.server, "/v1/privacy/me", "GET", token, null, owner) ?: run {
            if (request(owner.server, "/v1/privacy", "GET", null, null) != null) clearLocalGrants(owner, receipts)
            return
        }
        reconcile(owner, state, receipts)
    }

    fun canUpload(url: String, token: String): Boolean = canSend(url, token, "tower_upload")

    fun canSendDiagnostics(url: String, token: String): Boolean = canSend(url, token, "diagnostics")

    private fun canSend(url: String, token: String, purpose: String): Boolean {
        val owner = auth.ownerForToken(url, token) ?: return false
        if (ledger.pending(owner, purpose) != null) return false
        val receipts = receiptStamps(owner)
        val response = request(owner.server, "/v1/privacy/me", "GET", token, null, owner)
        if (response == null) {
            return notice(owner.server) == null && auth.diagnosticOwner() == owner &&
                (purpose == "tower_upload" || ledger.granted(owner, purpose, LOCAL_VERSION))
        }
        reconcile(owner, response, receipts)
        val notice = parseNotice(response.getJSONObject("notice"))
        return response.optBoolean(purpose) && ledger.granted(owner, purpose, notice.version) && auth.diagnosticOwner() == owner
    }

    /** Legacy URL-only receipts cannot establish consent for any current account. */
    fun canCaptureDiagnostics(url: String): Boolean {
        val owner = owner(url) ?: return false
        val version = ledger.version(owner, "diagnostics") ?: return false
        return ledger.granted(owner, "diagnostics", version)
    }

    /** Live sharing requires its own local receipt; a diagnostics grant cannot authorize it. */
    fun canCaptureLivePosition(url: String): Boolean {
        val owner = owner(url) ?: return false
        val version = ledger.version(owner, "live_position") ?: return false
        return ledger.granted(owner, "live_position", version)
    }

    fun isGranted(url: String, purpose: String, version: String): Boolean = owner(url)?.let { ledger.granted(it, purpose, version) } == true

    /** Confirmation must still belong to the same server/account that displayed the notice. */
    fun grant(expected: DiagnosticOwner, purpose: String, version: String) {
        if (auth.diagnosticOwner() != expected) throw DiagnosticOwnerChanged()
        flushWithdrawals(expected.server)
        val token = auth.diagnosticAccessToken(expected) ?: throw DiagnosticOwnerChanged()
        if (!prefs.getBoolean("local:${expected.server}", false)) {
            val body = JSONObject().put("notice_version", version).toString()
            request(expected.server, "/v1/privacy/consents/$purpose", "PUT", token, body, expected)
        }
        if (auth.diagnosticOwner() != expected || !ledger.grant(expected, purpose, version)) throw DiagnosticOwnerChanged()
        if (purpose == "tower_upload") auth.markSharingEnabled(expected)
    }

    /** Keep the originating owner and intent until that same account's deletion succeeds. */
    fun withdrawLocally(url: String, purpose: String) {
        val owner = owner(url) ?: return
        ledger.withdraw(owner, purpose)
    }

    fun flushWithdrawals(url: String) {
        val owner = owner(url) ?: return
        for (purpose in listOf("tower_upload", "diagnostics", "live_position")) {
            val intent = ledger.pending(owner, purpose) ?: continue
            val token = auth.diagnosticAccessToken(owner) ?: return
            try {
                request(owner.server, "/v1/privacy/consents/$purpose", "DELETE", token, null, owner)
            } catch (error: HttpException) {
                if (error.code != 404 || notice(owner.server) != null) throw error
            }
            if (auth.diagnosticOwner() == owner) ledger.acknowledge(owner, purpose, intent)
        }
    }

    private fun owner(url: String): DiagnosticOwner? = auth.diagnosticOwner()?.takeIf { it.server == url.trim().trimEnd('/') }

    private fun reconcile(owner: DiagnosticOwner, state: JSONObject, receipts: Map<String, String?>) {
        val version = state.getJSONObject("notice").getString("version")
        for (purpose in listOf("tower_upload", "diagnostics", "live_position")) {
            if (!state.optBoolean(purpose) || !ledger.granted(owner, purpose, version)) ledger.revokeIfUnchanged(owner, purpose, receipts[purpose])
        }
    }

    private fun clearLocalGrants(owner: DiagnosticOwner, receipts: Map<String, String?>) {
        receipts.forEach { (purpose, stamp) -> ledger.revokeIfUnchanged(owner, purpose, stamp) }
    }

    private fun receiptStamps(owner: DiagnosticOwner): Map<String, String?> = listOf("tower_upload", "diagnostics", "live_position").associateWith { ledger.stamp(owner, it) }

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

    private fun request(url: String, path: String, method: String, token: String?, body: String?, owner: DiagnosticOwner? = null): JSONObject? {
        val base = url.trim().trimEnd('/')
        val parsed = base.toHttpUrlOrNull() ?: throw IOException("Invalid sharing server URL")
        require(parsed.scheme == "https" || (parsed.scheme == "http" && parsed.host in setOf("localhost", "127.0.0.1", "10.0.2.2"))) {
            "Account sharing requires HTTPS"
        }
        val builder = Request.Builder().url(base + path)
        if (token != null) builder.header("Authorization", "Bearer $token")
        val requestBody = body?.toRequestBody("application/json".toMediaType())
        val call = Http.client.newCall(builder.method(method, requestBody).build())
        val read: (Response) -> JSONObject? = {
            val source = it.body.source()
            if (source.request(MAX_RESPONSE_BYTES + 1L)) throw IOException("Sharing server notice is too large")
            val bytes = source.readByteArray()
            val text = bytes.toString(Charsets.UTF_8)
            if (it.code == 404 && path in setOf("/v1/privacy", "/v1/privacy/me")) {
                null
            } else {
                if (!it.isSuccessful) throw HttpException(it.code, text.take(Http.ERROR_BODY_CHARS))
                if (text.isBlank()) null else JSONObject(text)
            }
        }
        return if (owner == null) call.execute().use(read) else requests.execute(owner, call, read)
    }

    companion object {
        const val LOCAL_VERSION = "local"
        private const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}
