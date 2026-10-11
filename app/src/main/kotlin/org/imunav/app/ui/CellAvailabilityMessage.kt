package org.imunav.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.imunav.app.R
import org.imunav.app.UiState
import org.imunav.app.cells.CellAvailability

/** Share the same localized explanation across the map, diagnostics and tower settings. */
internal fun cellAvailabilityMessage(availability: CellAvailability): Int? = when (availability) {
    CellAvailability.CHECKING, CellAvailability.AVAILABLE -> null
    CellAvailability.NO_CELLULAR -> R.string.cells_no_cellular
    CellAvailability.NO_SIM -> R.string.cells_no_sim
    CellAvailability.SIM_UNAVAILABLE -> R.string.cells_sim_unavailable
    CellAvailability.NO_CELLS -> R.string.cells_no_signal
    CellAvailability.SCAN_FAILED -> R.string.cells_scan_failed
    CellAvailability.NO_KNOWN_TOWERS -> R.string.cells_no_known_towers
    CellAvailability.DISABLED -> R.string.cells_none_warning
}

/** Keep the missing automatic position visible even in the compact map layout. */
@Composable
internal fun CellPositionWarning(ui: UiState) {
    val message = cellAvailabilityMessage(ui.cells.availability) ?: return
    if (ui.hasTrustedPosition) return
    val needsStart = !ui.guidance.active && ui.manualStart == null
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(if (needsStart) R.string.position_unavailable else R.string.cells_position_unavailable), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(message), style = MaterialTheme.typography.bodySmall)
            if (needsStart) Text(stringResource(R.string.error_no_position), style = MaterialTheme.typography.bodySmall)
        }
    }
}
