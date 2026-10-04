package org.imunav.app.cells

import org.imunav.app.setup.Preparation
import org.imunav.core.cells.CellTower
import org.imunav.core.cells.Radio

/** Towers to draw on the map for the current viewport. */
data class TowerLayer(
    val towers: List<CellTower> = emptyList(),
    /** Towers of the cells the phone sees right now (exact or site match). */
    val visible: List<CellTower> = emptyList(),
    /** True when there were more towers than we draw (only a sample is shown). */
    val truncated: Boolean = false,
    /** Map is zoomed out too far to show towers. */
    val zoomTooLow: Boolean = false,
)

/** Offline cell positioning status for the UI. */
data class CellStatus(
    val preparation: Preparation = Preparation.CHECKING,
    /** Cells of enabled types the modem sees now. */
    val seen: Int = 0,
    /** How many of them are in the database (used for the position). */
    val located: Int = 0,
    /** Accuracy of the current cell fix, metres. */
    val accuracyM: Double? = null,
    /** Towers per source in the database. */
    val counts: Map<CellSource, Long> = emptyMap(),
    /** Learn tower positions from trusted GPS. */
    val learning: Boolean = true,
    val showTowers: Boolean = false,
    /** Cell types used for positioning and drawn on the map. */
    val radios: Set<Radio> = CellManager.DEFAULT_RADIOS,
    /** An OpenCellID token has been entered. */
    val hasToken: Boolean = false,
    /** Country codes to import, comma-separated (255 = Ukraine). */
    val mccs: String = "255",
    /** Cell-sharing server settings. */
    val syncUrl: String = "",
    val hasSyncKey: Boolean = false,
    val autoSync: Boolean = false,
    val lastSync: String? = null,
    /** Long-running task message (import / download / sync), null when idle. */
    val busy: String? = null,
    val busyCancellable: Boolean = false,
    /** Result of the last task. */
    val message: String? = null,
) {
    val total: Long get() = counts.values.sum()
}
