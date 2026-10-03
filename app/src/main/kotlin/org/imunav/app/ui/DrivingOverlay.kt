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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Bound the side panel so the map retains most of the available driving window. */
fun drivingPaneWidth(width: Dp, height: Dp): Dp? = if (width >= 600.dp && width > height) minOf(320.dp, width * 0.4f) else null

/** Search and saved places share a keyboard-aware pane while the existing map stays composed. */
@Composable
fun DrivingOverlay(onWidth: (Int) -> Unit = {}, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val paneWidth = drivingPaneWidth(maxWidth, maxHeight)
        Surface(
            Modifier.align(Alignment.CenterStart).fillMaxHeight()
                .then(if (paneWidth != null) Modifier.width(paneWidth) else Modifier.fillMaxSize())
                .onSizeChanged { onWidth(if (paneWidth != null) it.width else 0) }.blockTouchesBelow(),
            shadowElevation = 8.dp,
        ) { content() }
    }
}
