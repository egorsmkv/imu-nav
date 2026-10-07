package org.imunav.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.imunav.app.AppGraph
import org.imunav.app.AppLanguage
import org.imunav.app.R

/** Shared UI/voice language choice; save pending settings before recreating the localized activity. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguageSelector(app: AppGraph, onBeforeChange: () -> Unit = {}) {
    val language by app.language.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.language_title)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.language_hint))
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = languageLabel(language),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.sec_language)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        AppLanguage.CHOICES.forEach { choice ->
                            DropdownMenuItem(
                                text = { Text(languageLabel(choice)) },
                                modifier = Modifier.semantics { selected = language == choice },
                                trailingIcon = { if (language == choice) Icon(Icons.Filled.Check, contentDescription = null) },
                                onClick = {
                                    expanded = false
                                    if (language != choice) {
                                        onBeforeChange()
                                        app.setLanguage(choice)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
        leadingContent = { Icon(Icons.Filled.Translate, contentDescription = null) },
    )
}

/** Native language names stay recognizable even when the current UI language is unfamiliar. */
@Composable
private fun languageLabel(choice: String): String = when (choice) {
    AppLanguage.UKRAINIAN -> "Українська"
    AppLanguage.ENGLISH -> "English"
    AppLanguage.RUSSIAN -> "Русский"
    else -> stringResource(R.string.language_system)
}
