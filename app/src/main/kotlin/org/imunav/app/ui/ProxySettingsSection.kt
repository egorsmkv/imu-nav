package org.imunav.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.imunav.app.R
import org.imunav.app.net.ProxySettings
import org.imunav.core.net.ProxyConfig
import org.imunav.core.net.ProxyMode

/** Edit locally, validate on Apply, and never replace a working proxy with a partially typed endpoint. */
@Composable
internal fun ProxySettingsSection(settings: ProxySettings) {
    val current by settings.config.collectAsStateWithLifecycle()
    var mode by remember(current) { mutableStateOf(current.mode) }
    var host by remember(current) { mutableStateOf(current.host) }
    var port by remember(current) { mutableStateOf(current.port.toString()) }
    var username by remember(current) { mutableStateOf(current.username) }
    var password by remember(current) { mutableStateOf(current.password) }
    var invalid by remember { mutableStateOf(false) }
    var applied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.proxy_hint), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProxyMode.entries.forEach { choice ->
                FilterChip(
                    selected = mode == choice,
                    onClick = {
                        mode = choice
                        applied = false
                        invalid = false
                    },
                    label = { Text(proxyModeName(choice)) },
                )
            }
        }
        if (mode == ProxyMode.HTTP || mode == ProxyMode.SOCKS) {
            OutlinedTextField(
                value = host,
                onValueChange = {
                    host = it
                    applied = false
                },
                label = { Text(stringResource(R.string.proxy_host)) },
                singleLine = true,
                isError = invalid,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = port,
                onValueChange = {
                    port = it
                    applied = false
                },
                label = { Text(stringResource(R.string.proxy_port)) },
                singleLine = true,
                isError = invalid,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        if (mode == ProxyMode.HTTP) {
            OutlinedTextField(
                value = username,
                onValueChange = {
                    username = it
                    applied = false
                },
                label = { Text(stringResource(R.string.proxy_username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    applied = false
                },
                label = { Text(stringResource(R.string.proxy_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.proxy_auth_hint), style = MaterialTheme.typography.bodySmall)
        }
        if (mode == ProxyMode.SOCKS) Text(stringResource(R.string.proxy_socks_hint), style = MaterialTheme.typography.bodySmall)
        if (invalid) Text(stringResource(R.string.proxy_invalid), color = MaterialTheme.colorScheme.error)
        if (applied) Text(stringResource(R.string.proxy_applied), style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            val config = ProxyConfig.parse(mode, host, port, username, password)
            invalid = config == null
            if (config != null) {
                settings.save(config)
                applied = true
            }
        }) { Text(stringResource(R.string.proxy_apply)) }
    }
}

/** Keep every mode label localized, including the default and explicit system-proxy bypass. */
@Composable
private fun proxyModeName(mode: ProxyMode): String = stringResource(
    when (mode) {
        ProxyMode.SYSTEM -> R.string.proxy_system
        ProxyMode.DIRECT -> R.string.proxy_direct
        ProxyMode.HTTP -> R.string.proxy_http
        ProxyMode.SOCKS -> R.string.proxy_socks
    },
)
