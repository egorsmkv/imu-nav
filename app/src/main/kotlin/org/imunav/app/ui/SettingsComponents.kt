package org.imunav.app.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.imunav.app.R
import org.imunav.app.cells.CellSource
import org.imunav.app.power.PowerMode
import org.imunav.core.nav.NavigationEstimator
import org.imunav.core.nav.NavigationMethod

/** Reassures users that the defaults are safe and explains when edits are stored. */
@Composable
internal fun SettingsIntro() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Info, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.settings_intro_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_intro_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Expandable group that keeps the settings screen short and explains what each group controls. */
@Composable
internal fun SettingsGroup(title: String, summary: String, icon: ImageVector, initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val toggleDescription = stringResource(if (expanded) R.string.settings_collapse_section else R.string.settings_expand_section)
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = toggleDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                HorizontalDivider()
                Column(content = content)
            }
        }
    }
}

/** Short plain-language guidance shown at the start of an expanded settings group. */
@Composable
internal fun SettingsHelp(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Shows whether Android may stop navigation in the background and opens the system fix. */
@Composable
internal fun BatteryOptimizationItem(context: Context) {
    var unrestricted by remember { mutableStateOf(isBatteryUnrestricted(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) unrestricted = isBatteryUnrestricted(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.battery_title)) },
        supportingContent = {
            Text(
                stringResource(if (unrestricted) R.string.battery_unrestricted else R.string.battery_restricted),
                color = if (unrestricted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        },
        modifier = Modifier.clickable(enabled = !unrestricted) {
            runCatching {
                @android.annotation.SuppressLint("BatteryLife")
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    "package:${context.packageName}".toUri(),
                )
                context.startActivity(intent)
            }
        },
    )
}

/** Shows a donation destination only when its URL was configured for this build. */
@Composable
internal fun DonationLink(title: String, url: String, openUrl: (String) -> Unit) {
    if (url.isNotBlank()) {
        ListItem(
            headlineContent = { Text(title) },
            modifier = Modifier.clickable { openUrl(url) },
        )
    }
}

/** Localised name of the position estimator selected for the next trip. */
@Composable
internal fun navigationEstimatorName(estimator: NavigationEstimator): String = stringResource(
    when (estimator) {
        NavigationEstimator.KOTLIN -> R.string.navigation_estimator_kotlin
        NavigationEstimator.NATIVE_KALMAN -> R.string.navigation_estimator_native
    },
)

/** Localised name of a navigation fallback method. */
@Composable
internal fun navigationMethodName(method: NavigationMethod): String = stringResource(
    when (method) {
        NavigationMethod.DEAD_RECKONING -> R.string.navigation_method_dr
        NavigationMethod.CELL_TOWERS -> R.string.navigation_method_cells
        NavigationMethod.HYBRID -> R.string.navigation_method_hybrid
    },
)

/** Localised name of a power mode. */
@Composable
internal fun powerModeName(m: PowerMode): String = stringResource(
    when (m) {
        PowerMode.AUTO -> R.string.power_auto
        PowerMode.PERFORMANCE -> R.string.power_performance
        PowerMode.BALANCED -> R.string.power_balanced
        PowerMode.SAVER -> R.string.power_saver
    },
)

/** Localised name of a tower database source. */
@Composable
internal fun sourceName(s: CellSource): String = stringResource(
    when (s) {
        CellSource.SHARED -> R.string.src_label_shared
        CellSource.OPENCELLID -> R.string.src_label_ocid
        CellSource.LEARNED -> R.string.src_label_learned
        CellSource.MOZILLA -> R.string.src_label_mozilla
        CellSource.BUNDLED -> R.string.src_label_bundled
    },
)

/** Section title with a divider, in the Material 3 settings style. */
@Composable
internal fun SectionHeader(text: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 12.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}

/** A settings row with a title, optional summary and a switch. */
@Composable
internal fun SwitchItem(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

/** A single-line text field; [secret] hides the text (for keys and tokens). */
@Composable
internal fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String?,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    helper: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = helper?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}
