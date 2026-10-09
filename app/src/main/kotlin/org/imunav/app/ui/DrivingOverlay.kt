package org.imunav.app.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val DrivingPaneMinWidth = 320.dp
private const val DRIVING_PANE_MAX_FRACTION = 0.4f

/** Keep a readable pane at large font sizes; use the full-screen layout if it would crowd the map. */
fun drivingPaneWidth(width: Dp, height: Dp, fontScale: Float = 1f): Dp? {
    val readableWidth = DrivingPaneMinWidth * fontScale.coerceAtLeast(1f)
    return readableWidth.takeIf { width > height && it <= width * DRIVING_PANE_MAX_FRACTION }
}

/** Search and saved places share a keyboard-aware pane while the existing map stays composed. */
@Composable
fun DrivingOverlay(onWidth: (Int) -> Unit = {}, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val paneWidth = drivingPaneWidth(maxWidth, maxHeight, LocalDensity.current.fontScale)
        Surface(
            Modifier.align(Alignment.CenterStart).fillMaxHeight()
                .then(if (paneWidth != null) Modifier.width(paneWidth) else Modifier.fillMaxSize())
                .onSizeChanged { onWidth(if (paneWidth != null) it.width else 0) }.blockTouchesBelow(),
            shadowElevation = 8.dp,
        ) { content() }
    }
}
