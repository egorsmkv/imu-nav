package org.imunav.app.maps

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory

/** Retains the style's original visibility, including intentionally hidden extrusion layers. */
class BuildingVisibility(style: Style) {
    private val layers = style.layers.filterIsInstance<FillExtrusionLayer>().map { it to (it.visibility.value ?: Property.VISIBLE) }

    /** Only 3D geometry is hidden; existing flat building footprints and navigation layers remain. */
    fun apply(enabled: Boolean) {
        layers.forEach { (layer, original) ->
            layer.setProperties(PropertyFactory.visibility(if (enabled) original else Property.NONE))
        }
    }
}
