package org.imunav.app.cells

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.imunav.core.cells.CellObservation
import org.imunav.core.cells.CellTower
import org.imunav.core.cells.Radio
import kotlin.math.abs

/** Owns viewport query caching and the visible cell-tower layer independently of transfer tasks. */
internal class CellTowerLayerCoordinator(
    private val scope: CoroutineScope,
    private val db: CellDatabase,
    private val visibleCells: () -> List<CellObservation>,
    private val enabledRadios: () -> Set<Radio>,
    private val showTowers: () -> Boolean,
) {
    private val _layer = MutableStateFlow(TowerLayer())
    val layer: StateFlow<TowerLayer> = _layer.asStateFlow()
    private var job: Job? = null
    private var lastViewport: DoubleArray? = null
    private var lastQuery: DoubleArray? = null

    /** A changed database or radio filter makes the last viewport query stale. */
    fun invalidate(refresh: Boolean = false) {
        lastQuery = null
        if (refresh) lastViewport?.let { onViewport(it[0], it[1], it[2], it[3], it[4]) }
    }

    /** Show the current viewport immediately when enabled; clear the layer when disabled. */
    fun setVisible(on: Boolean) {
        lastQuery = null
        val viewport = lastViewport
        if (on && viewport != null) {
            onViewport(viewport[0], viewport[1], viewport[2], viewport[3], viewport[4])
        } else if (!on) {
            _layer.value = TowerLayer()
        }
    }

    /** Query a margin around the viewport so small camera movements reuse the loaded towers. */
    fun onViewport(south: Double, west: Double, north: Double, east: Double, zoom: Double) {
        lastViewport = doubleArrayOf(south, west, north, east, zoom)
        if (!showTowers()) return
        val query = lastQuery
        if (query != null && query.contains(south, west, north, east) && abs(zoom - query[4]) < 0.5 && !_layer.value.truncated) return
        val padLat = (north - south) * 0.5
        val padLon = (east - west) * 0.5
        lastQuery = if (zoom >= CellManager.MIN_TOWER_ZOOM) doubleArrayOf(south - padLat, west - padLon, north + padLat, east + padLon, zoom) else null
        job?.cancel()
        if (zoom < CellManager.MIN_TOWER_ZOOM) {
            job = scope.launch { _layer.value = TowerLayer(visible = withContext(Dispatchers.IO) { visibleTowers() }, zoomTooLow = true) }
            return
        }
        job = scope.launch {
            val next = withContext(Dispatchers.IO) {
                val (rows, truncated) = db.towersIn(south - padLat, west - padLon, north + padLat, east + padLon, CellManager.MAX_TOWERS_ON_MAP, enabledRadios())
                TowerLayer(rows.map { it.first }, visibleTowers(), truncated)
            }
            _layer.value = next
        }
    }

    /** Database entries for the cells the phone currently sees. */
    private fun visibleTowers(): List<CellTower> = visibleCells().mapNotNull { runCatching { db.resolve(it.key)?.first }.getOrNull() }

    /** Cached query bounds contain the current view when the camera has only moved a little. */
    private fun DoubleArray.contains(south: Double, west: Double, north: Double, east: Double) = south >= this[0] && west >= this[1] && north <= this[2] && east <= this[3]
}
