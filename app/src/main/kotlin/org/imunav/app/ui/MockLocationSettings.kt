package org.imunav.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.imunav.app.R
import org.imunav.app.location.MockLocationSharing
import org.imunav.core.location.MockLocationStatus

/** Explains the system-wide effect before opting in, with Android setup and live status. */
@Composable
internal fun MockLocationSettings(sharing: MockLocationSharing) {
    val enabled by sharing.enabled.collectAsStateWithLifecycle()
    val status by sharing.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var settingsUnavailable by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner, sharing) {
        sharing.refresh()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) sharing.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    SectionHeader(stringResource(R.string.mock_location_section))
    SwitchItem(
        stringResource(R.string.mock_location_title), stringResource(R.string.mock_location_summary),
        enabled, sharing::setEnabled,
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.mock_location_limitations), style = MaterialTheme.typography.bodySmall)
        Text(
            stringResource(
                when (status) {
                    MockLocationStatus.OFF -> R.string.mock_location_off
                    MockLocationStatus.WAITING -> R.string.mock_location_waiting
                    MockLocationStatus.ACTIVE -> R.string.mock_location_active
                    MockLocationStatus.PERMISSION_REQUIRED -> R.string.mock_location_permission
                    MockLocationStatus.ERROR -> R.string.mock_location_error
                    MockLocationStatus.CLEANUP_FAILED -> R.string.mock_location_cleanup_failed
                },
            ),
            modifier = Modifier.padding(vertical = 8.dp),
        )
        OutlinedButton(onClick = {
            settingsUnavailable = runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }.isFailure
        }) {
            Text(stringResource(R.string.mock_location_open_settings))
        }
        if (settingsUnavailable) Text(stringResource(R.string.mock_location_settings_unavailable))
    }
}
