package org.imunav.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.imunav.app.AppGraph
import org.imunav.app.R
import org.imunav.app.trips.TripArchiveClient
import org.imunav.app.trips.TripSummary
import org.imunav.app.trips.playbackDocument
import org.imunav.core.net.HttpException
import org.json.JSONObject

/** Recreates the upload scope on account/server changes, cancelling requests and discarding receipts. */
@Composable
internal fun TripArchiveControls(app: AppGraph, trip: TripSummary) {
    val status by app.cells.status.collectAsState()
    val profile = remember(status) { "${app.cells.diagnosticServerUrl()}:${app.cells.accountId()}:${app.cells.syncIdentity()}" }
    key(profile, trip.id) { ArchiveUpload(app, trip) }
}

/** Manual consent and transfer flow. Leaving the screen cancels extraction and network requests. */
@Composable
private fun ArchiveUpload(app: AppGraph, trip: TripSummary) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var metadata by remember { mutableStateOf<JSONObject?>(null) }
    var client by remember { mutableStateOf<TripArchiveClient?>(null) }
    var notice by remember { mutableStateOf("") }
    var browser by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    val signedIn = app.cells.accountId() > 0 && app.cells.syncIdentity().isNotBlank()
    val profile = remember { "${app.cells.diagnosticServerUrl()}:${app.cells.accountId()}:${app.cells.syncIdentity()}" }
    fun checkProfile() {
        if (profile != "${app.cells.diagnosticServerUrl()}:${app.cells.accountId()}:${app.cells.syncIdentity()}") throw CancellationException("Account changed")
    }
    fun run(action: suspend () -> Unit) {
        busy = true
        error = null
        job = scope.launch {
            try {
                runCatching {
                    checkProfile()
                    action()
                    checkProfile()
                }.onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    error = when {
                        failure is HttpException && failure.code == 413 -> R.string.trip_archive_quota
                        failure is HttpException && failure.code == 404 -> R.string.trip_archive_unavailable
                        failure is HttpException && failure.code == 409 -> R.string.trip_archive_conflict
                        failure is IllegalArgumentException -> R.string.trip_archive_invalid
                        else -> R.string.trip_archive_failed
                    }
                }
            } finally {
                busy = false
            }
        }
    }
    Column(Modifier.padding(16.dp)) {
        Text(stringResource(R.string.trip_archive_title))
        Text(stringResource(if (signedIn) R.string.trip_archive_description else R.string.trip_archive_sign_in))
        error?.let { Text(stringResource(it)) }
        if (busy) {
            LinearProgressIndicator()
            Text(stringResource(R.string.trip_archive_progress))
            TextButton(onClick = { job?.cancel() }) { Text(stringResource(R.string.action_cancel)) }
        }
        Button(enabled = signedIn && !busy, onClick = {
            run {
                val credentials = withContext(Dispatchers.IO) { app.cells.accountCredentials() } ?: error("Not signed in")
                checkProfile()
                val connection = TripArchiveClient(credentials.first, credentials.second)
                val response = connection.metadata()
                client = connection
                notice = response.optJSONObject("notice")?.let {
                    val language = resources.configuration.locales[0].language.takeIf { lang -> lang in setOf("en", "uk", "ru") } ?: "en"
                    listOf(
                        it.optString("controller"),
                        it.optString("contact"),
                        it.optString("notice_$language"),
                        it.optString("region"),
                        it.optString("recipients"),
                        it.optString("transfers"),
                    ).filter(String::isNotBlank).joinToString("\n\n")
                }.orEmpty()
                metadata = response
            }
        }) { Text(stringResource(R.string.trip_archive_upload)) }
        browser?.let { url ->
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }) { Text(stringResource(R.string.trip_archive_open)) }
        }
    }
    metadata?.let { consent ->
        AlertDialog(
            onDismissRequest = { metadata = null },
            title = { Text(stringResource(R.string.trip_archive_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.trip_archive_consent))
                    Text(notice)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    metadata = null
                    run {
                        val connection = client ?: error("No connection")
                        val document = playbackDocument(app.trips.recordingFile(trip), trip)
                        checkProfile()
                        connection.consent(consent.getString("notice_version"))
                        checkProfile()
                        connection.upload(trip.id, document, consent.getJSONObject("limits").getInt("upload_bytes"))
                        browser = app.cells.diagnosticServerUrl() + "/trips/" + trip.id
                    }
                }) { Text(stringResource(R.string.trip_archive_upload)) }
            },
            dismissButton = { TextButton(onClick = { metadata = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
