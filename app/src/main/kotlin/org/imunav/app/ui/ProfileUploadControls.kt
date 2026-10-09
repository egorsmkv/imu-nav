package org.imunav.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.imunav.app.AppGraph
import org.imunav.app.R
import org.imunav.app.diagnostics.ProfileUploadClient
import org.imunav.core.net.HttpException
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Changing account, server or archive cancels pending work and discards the previous confirmation. */
@Composable
internal fun ProfileUploadControls(app: AppGraph, archive: File) {
    val status by app.cells.status.collectAsStateWithLifecycle()
    val identity = remember(status) { profileUploadIdentity(app) }
    key(identity, archive.absolutePath) { ProfileUpload(app, archive, identity) }
}

private fun profileUploadIdentity(app: AppGraph): String = "${app.cells.diagnosticServerUrl()}:${app.cells.accountId()}:${app.cells.syncIdentity()}"

/** Fetch the operator notice first, then stream this one ZIP only after explicit confirmation. */
@Composable
private fun ProfileUpload(app: AppGraph, archive: File, identity: String) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var metadata by remember { mutableStateOf<JSONObject?>(null) }
    var client by remember { mutableStateOf<ProfileUploadClient?>(null) }
    var server by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var uploaded by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Int?>(null) }
    val signedIn = app.cells.accountId() > 0 && app.cells.syncIdentity().isNotBlank()
    fun checkAccount() {
        if (profileUploadIdentity(app) != identity) throw CancellationException("Account changed")
    }
    fun run(action: suspend () -> Unit) {
        busy = true
        failure = null
        job = scope.launch {
            try {
                checkAccount()
                action()
                checkAccount()
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                failure = when (error) {
                    is HttpException if error.code == 413 -> R.string.profile_upload_too_large
                    is HttpException if error.code in setOf(403, 404) -> R.string.profile_upload_unavailable
                    is HttpException if error.code == 409 -> R.string.profile_upload_notice_changed
                    else -> R.string.profile_upload_failed
                }
            } catch (_: JSONException) {
                failure = R.string.profile_upload_failed
            } catch (_: IllegalArgumentException) {
                failure = R.string.profile_upload_too_large
            } finally {
                busy = false
            }
        }
    }
    Column {
        if (!signedIn) Text(stringResource(R.string.profile_upload_sign_in))
        failure?.let { Text(stringResource(it)) }
        if (busy) {
            LinearProgressIndicator()
            Text(stringResource(R.string.profile_upload_progress))
            TextButton(onClick = { job?.cancel() }) { Text(stringResource(R.string.action_cancel)) }
        }
        TextButton(enabled = signedIn && !busy, onClick = {
            run {
                val credentials = withContext(Dispatchers.IO) { app.cells.accountCredentials() } ?: throw IOException("Not signed in")
                checkAccount()
                val connection = ProfileUploadClient(credentials.first, credentials.second)
                val response = connection.metadata()
                checkAccount()
                if (!response.getBoolean("enabled")) {
                    failure = R.string.profile_upload_unavailable
                } else {
                    server = credentials.first
                    client = connection
                    val language = resources.configuration.locales[0].language.takeIf { it in setOf("en", "uk", "ru") } ?: "en"
                    notice = response.optJSONObject("notice")?.let { value ->
                        listOf("controller", "contact", "notice_$language", "region", "recipients", "transfers")
                            .map { value.optString(it) }.filter(String::isNotBlank).joinToString("\n\n")
                    }.orEmpty()
                    metadata = response
                }
            }
        }) { Text(stringResource(R.string.profile_upload_action)) }
        if (uploaded) {
            Text(stringResource(R.string.profile_upload_done))
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, "$server/debug".toUri())) }) {
                Text(stringResource(R.string.profile_upload_open))
            }
        }
    }
    metadata?.let { consent ->
        AlertDialog(
            onDismissRequest = { metadata = null },
            title = { Text(stringResource(R.string.profile_upload_action)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val days = consent.getInt("retention_days")
                    val retention = resources.getQuantityString(R.plurals.profile_upload_retention, days, days)
                    Text(stringResource(R.string.profile_upload_warning, server, retention))
                    if (notice.isNotBlank()) Text(notice)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    metadata = null
                    run {
                        val connection = client ?: throw IOException("No server selected")
                        connection.consent(consent.optString("notice_version").takeIf { !consent.isNull("notice_version") && it.isNotBlank() })
                        checkAccount()
                        connection.upload(archive, consent.getLong("upload_bytes"))
                        checkAccount()
                        uploaded = true
                    }
                }) { Text(stringResource(R.string.profile_upload_action)) }
            },
            dismissButton = { TextButton(onClick = { metadata = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
