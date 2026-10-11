package org.imunav.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.imunav.app.AppGraph
import org.imunav.app.R
import org.imunav.app.cells.CellPrivacyNotice
import org.imunav.app.location.PositionSharingMode
import org.imunav.app.location.PositionSharingPhase
import org.imunav.app.location.PositionTransportStatus
import org.imunav.core.diagnostics.DiagnosticOwner

/** Describes local-network exposure and obtains separate account consent before Internet sharing. */
@Composable
internal fun PositionSharingSettings(app: AppGraph, navigating: Boolean) {
    val sharing = app.positionSharing
    val status by sharing.status.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(PositionSharingMode.LOCAL) }
    var address by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pair<DiagnosticOwner, CellPrivacyNotice>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    LaunchedEffect(sharing) { sharing.refreshAddresses() }
    LaunchedEffect(status.addresses) {
        if (address !in status.addresses) address = status.addresses.singleOrNull()
    }

    SectionHeader(stringResource(R.string.position_sharing_title))
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.position_sharing_explanation), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PositionSharingMode.entries.forEach { choice ->
                FilterChip(selected = mode == choice, enabled = !status.enabled && !loading, onClick = { mode = choice }, label = {
                    Text(stringResource(modeLabel(choice)))
                })
            }
        }
        if (mode != PositionSharingMode.INTERNET) {
            Text(stringResource(R.string.position_sharing_local_warning), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                status.addresses.forEach { candidate ->
                    FilterChip(selected = address == candidate, enabled = !status.enabled && !loading, onClick = { address = candidate }, label = { Text(candidate) })
                }
            }
            OutlinedButton(onClick = sharing::refreshAddresses) { Text(stringResource(R.string.position_sharing_refresh)) }
            if (status.addresses.isEmpty()) Text(stringResource(R.string.position_sharing_no_address))
        }
        if (mode != PositionSharingMode.LOCAL) Text(stringResource(R.string.position_sharing_vpn), style = MaterialTheme.typography.bodySmall)
        TransportStatus(R.string.position_sharing_local, status.local)
        TransportStatus(R.string.position_sharing_internet, status.internet)
        if (!navigating) Text(stringResource(R.string.position_sharing_start_route))
        if (failed) Text(stringResource(R.string.position_sharing_setup_failed), color = MaterialTheme.colorScheme.error)
        val localReady = mode == PositionSharingMode.INTERNET || address in status.addresses
        Button(enabled = status.enabled || (navigating && localReady && !loading), onClick = {
            if (status.enabled) {
                sharing.stop()
            } else if (mode == PositionSharingMode.LOCAL || app.cells.livePositionConsentGranted()) {
                sharing.start(mode, address, app.engine.state.active)
            } else {
                failed = false
                val owner = app.cells.diagnosticOwner()
                if (owner == null) {
                    failed = true
                    if (mode == PositionSharingMode.BOTH) sharing.start(mode, address, app.engine.state.active)
                } else {
                    loading = true
                    scope.launch {
                        runCatching {
                            val notice = app.cells.privacyNotice(owner.server)
                            if (notice == null || app.cells.diagnosticOwner() != owner) {
                                failed = true
                                if (mode == PositionSharingMode.BOTH) sharing.start(mode, address, app.engine.state.active)
                            } else if (app.cells.privacyGranted("live_position", notice.version)) {
                                sharing.start(mode, address, app.engine.state.active)
                            } else {
                                pending = owner to notice
                            }
                        }.onFailure { failure ->
                            if (failure is CancellationException) throw failure
                            failed = true
                            if (mode == PositionSharingMode.BOTH) sharing.start(mode, address, app.engine.state.active)
                        }
                        loading = false
                    }
                }
            }
        }) { Text(stringResource(if (status.enabled) R.string.position_sharing_stop else R.string.position_sharing_start)) }
        if (mode != PositionSharingMode.LOCAL) {
            TextButton(onClick = {
                sharing.stop()
                app.cells.withdrawPrivacyConsent("live_position")
            }) { Text(stringResource(R.string.position_sharing_withdraw)) }
        }
    }
    pending?.let { (owner, notice) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.privacy_notice_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.position_sharing_consent))
                    Text(notice.text(resources.configuration.locales[0].language))
                    Text(
                        stringResource(
                            R.string.privacy_notice_details, notice.controller, notice.contact, notice.rightsContact, notice.region,
                            notice.recipients, notice.transfers, notice.accountBasis, notice.securityBasis,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    loading = true
                    scope.launch {
                        runCatching {
                            app.cells.grantPrivacyConsent("live_position", notice.version, owner)
                            if (app.cells.diagnosticOwner() == owner) sharing.start(mode, address, app.engine.state.active)
                        }.onFailure { failure ->
                            if (failure is CancellationException) throw failure
                            failed = true
                        }
                        loading = false
                    }
                }) { Text(stringResource(R.string.privacy_agree)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun TransportStatus(label: Int, status: PositionTransportStatus) {
    Text(stringResource(R.string.position_sharing_status, stringResource(label), stringResource(phaseLabel(status.phase)), status.receivers))
    status.endpoint?.let { Text(stringResource(R.string.position_sharing_endpoint, it)) }
}

private fun modeLabel(mode: PositionSharingMode): Int = when (mode) {
    PositionSharingMode.LOCAL -> R.string.position_sharing_local
    PositionSharingMode.INTERNET -> R.string.position_sharing_internet
    PositionSharingMode.BOTH -> R.string.position_sharing_both
}

private fun phaseLabel(phase: PositionSharingPhase): Int = when (phase) {
    PositionSharingPhase.OFF -> R.string.position_sharing_off
    PositionSharingPhase.WAITING -> R.string.position_sharing_waiting
    PositionSharingPhase.ACTIVE -> R.string.position_sharing_active
    PositionSharingPhase.RETRYING -> R.string.position_sharing_retrying
    PositionSharingPhase.NETWORK_ERROR -> R.string.position_sharing_network_error
    PositionSharingPhase.ACCOUNT_REQUIRED -> R.string.position_sharing_account_required
    PositionSharingPhase.CONSENT_REQUIRED -> R.string.position_sharing_consent_required
    PositionSharingPhase.SERVER_UNAVAILABLE -> R.string.position_sharing_server_unavailable
    PositionSharingPhase.ACCOUNT_CHANGED -> R.string.position_sharing_account_changed
}
