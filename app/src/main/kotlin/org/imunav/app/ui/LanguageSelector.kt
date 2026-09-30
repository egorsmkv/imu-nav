package org.imunav.app.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.imunav.app.AppGraph
import org.imunav.app.AppLanguage
import org.imunav.app.R

/** Shared UI/voice language choice; save pending settings before recreating the localized activity. */
@Composable
internal fun LanguageSelector(app: AppGraph, onBeforeChange: () -> Unit = {}) {
    val context = LocalContext.current
    val language by app.language.collectAsStateWithLifecycle()
    ListItem(
        headlineContent = { Text(stringResource(R.string.language_title)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.language_hint))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppLanguage.CHOICES.forEach { choice ->
                        val label = when (choice) {
                            AppLanguage.UKRAINIAN -> "Українська"
                            AppLanguage.ENGLISH -> "English"
                            AppLanguage.RUSSIAN -> "Русский"
                            else -> stringResource(R.string.language_system)
                        }
                        FilterChip(
                            selected = language == choice,
                            onClick = {
                                if (language != choice) {
                                    onBeforeChange()
                                    app.setLanguage(choice)
                                    (context as? Activity)?.recreate()
                                }
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
        leadingContent = { Icon(Icons.Filled.Translate, contentDescription = null) },
    )
}
