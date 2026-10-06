package org.imunav.app.diagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.imunav.app.sync.SyncApi
import org.json.JSONObject
import java.io.File

/** Sends only the archive explicitly confirmed by the user, without loading it into the Java heap. */
internal class ProfileUploadClient(url: String, token: String) {
    private val api = SyncApi(url, token)

    suspend fun metadata(): JSONObject = api.request("/v1/debug/profiles")

    /** Grant this server's diagnostics purpose without enabling the app's automatic trip uploader. */
    suspend fun consent(version: String?) {
        if (version != null) api.request("/v1/privacy/consents/diagnostics", "PUT", JSONObject().put("notice_version", version))
    }

    suspend fun upload(file: File, maxBytes: Long): String = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() in 1..minOf(maxBytes, MAX_UPLOAD_BYTES)) { "Profile archive unavailable or too large" }
        api.requestBody("/v1/debug/profiles", "POST", file.asRequestBody("application/zip".toMediaType())).getString("id")
    }

    private companion object {
        const val MAX_UPLOAD_BYTES = 48L * 1024 * 1024
    }
}
