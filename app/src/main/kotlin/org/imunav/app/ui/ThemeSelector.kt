package org.imunav.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.imunav.app.AppGraph
import org.imunav.app.AppTheme
import org.imunav.app.R

/** Light / dark / phone default; save pending settings before the activity is recreated with the new theme. */
@Composable
internal fun ThemeSelector(app: AppGraph, onBeforeChange: () -> Unit = {}) {
    val theme by app.theme.collectAsStateWithLifecycle()
    ListItem(
        headlineContent = { Text(stringResource(R.string.theme_hint)) },
        supportingContent = {
            Column {
                FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTheme.CHOICES.forEach { choice ->
                        FilterChip(
                            modifier = Modifier.semantics { role = Role.RadioButton },
                            selected = theme == choice,
                            onClick = {
                                if (theme != choice) {
                                    onBeforeChange()
                                    app.setTheme(choice)
                                }
                            },
                            label = { Text(themeLabel(choice)) },
                        )
                    }
                }
            }
        },
        leadingContent = { Icon(Icons.Filled.DarkMode, contentDescription = null) },
    )
}

@Composable
private fun themeLabel(choice: String): String = stringResource(
    when (choice) {
        AppTheme.LIGHT -> R.string.theme_light
        AppTheme.DARK -> R.string.theme_dark
        else -> R.string.theme_system
    },
)
