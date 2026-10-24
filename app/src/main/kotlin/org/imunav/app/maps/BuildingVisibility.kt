package org.imunav.app.maps

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.PropertyValue

/**
 * Switches the style's buildings between 3D and flat.
 *
 * Many styles (OpenFreeMap Liberty, our offline packs) draw buildings at close zoom *only* as
 * extrusions, so hiding the extrusion layers would remove buildings altogether. Instead the
 * heights are set to zero: the footprints stay on the map, drawn flat. The style's own height
 * expressions are kept and restored when 3D is switched back on.
 */
class BuildingVisibility(style: Style) {
    private val layers = style.layers.filterIsInstance<FillExtrusionLayer>().map { OriginalHeights(it, it.fillExtrusionHeight, it.fillExtrusionBase) }

    fun apply(enabled: Boolean) {
        layers.forEach { original ->
            if (enabled) {
                original.layer.setProperties(original.height, original.base)
            } else {
                original.layer.setProperties(PropertyFactory.fillExtrusionHeight(0f), PropertyFactory.fillExtrusionBase(0f))
            }
        }
    }

    /** One extrusion layer with the height and base it had in the style. */
    private class OriginalHeights(val layer: FillExtrusionLayer, val height: PropertyValue<Float>, val base: PropertyValue<Float>)
}
