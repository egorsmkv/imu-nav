package org.imunav.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.imunav.app.AppGraph
import org.imunav.app.R
import org.imunav.app.UiState
import org.imunav.core.gnss.TrustLevel

// ------------------------------------------------------------------ diagnostics sheet

/** Contents of the diagnostics sheet: GPS verdict, satellites, jamming, cells, debug switches. */
@Composable
internal fun DiagnosticsContent(ui: UiState, app: AppGraph, onOpenLog: () -> Unit) {
    val res = LocalResources.current
    val nav = ui.guidance
    val none = stringResource(R.string.none)
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.diag_title), style = MaterialTheme.typography.titleLarge)
        if (nav.active) {
            DiagRow(stringResource(R.string.diag_source), sourceLabel(res, nav.source))
            DiagRow(stringResource(R.string.diag_uncertainty), formatAccuracy(res, nav.uncertaintyM))
            if (!nav.source.isGps) DiagRow(stringResource(R.string.diag_without_gps), stringResource(R.string.diag_seconds, nav.blindS))
        }
        val verdict = ui.lastVerdict
        DiagRow(
            stringResource(R.string.diag_last_fix),
            when (verdict?.level) {
                TrustLevel.GOOD -> stringResource(R.string.verdict_good)
                TrustLevel.SUSPECT -> stringResource(R.string.verdict_suspect)
                TrustLevel.BAD -> stringResource(R.string.verdict_bad)
                null -> none
            },
            valueColor = when (verdict?.level) {
                TrustLevel.GOOD -> GoodGreen
                TrustLevel.SUSPECT -> WarnAmber
                TrustLevel.BAD -> BadRed
                null -> null
            },
        )
        val reasons = verdict?.reasons.orEmpty()
        if (reasons.isNotEmpty()) DiagRow(stringResource(R.string.diag_reasons), reasons.joinToString("\n") { gpsReasonLabel(res, it) })
        val gn = ui.gnss
        DiagRow(stringResource(R.string.diag_satellites), "${gn.satellitesUsed} / ${gn.satellitesVisible}")
        DiagRow(stringResource(R.string.diag_signal), gn.meanCn0Used?.let { "%.0f ± %.1f dB-Hz".format(it, gn.cn0SpreadUsed ?: 0f) } ?: none)
        DiagRow(stringResource(R.string.diag_agc), gn.agcDb?.let { "%.1f dB".format(it) } ?: none)
        when {
            ui.simulateGpsLoss -> DiagRow(stringResource(R.string.diag_gps), stringResource(R.string.gps_loss_test_active), valueColor = InfoBlue)
            ui.jammed -> DiagRow(stringResource(R.string.diag_gps), stringResource(R.string.gps_jammed), valueColor = BadRed)
        }
        DiagRow(
            stringResource(R.string.diag_cells),
            "${ui.cells.located} / ${ui.cells.seen}" + (ui.cells.accuracyM?.let { " · " + formatAccuracy(res, it) } ?: ""),
        )
        DiagRow(stringResource(R.string.diag_sensors), ui.sensorWarning ?: stringResource(R.string.diag_sensors_full))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.simulate_gps_loss), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.simulate_gps_loss_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = ui.simulateGpsLoss, onCheckedChange = { app.setSimulateGpsLoss(it) })
        }
        TextButton(onClick = onOpenLog) { Text(stringResource(R.string.diag_open_log)) }
    }
}

/** One "label: value" row in the diagnostics sheet. */
@Composable
private fun DiagRow(label: String, value: String, valueColor: Color? = null, mono: Boolean = false) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            fontFamily = if (mono) FontFamily.Monospace else null,
            modifier = Modifier.weight(1.2f),
        )
    }
}
