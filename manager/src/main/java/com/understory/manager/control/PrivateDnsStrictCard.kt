package com.understory.manager.control

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import com.understory.elevation.Elevation
import com.understory.elevation.Outcome
import com.understory.elevation.PrivateDnsMode
import com.understory.manager.R
import com.understory.security.SecureButton
import com.understory.security.SecureOutlinedButton
import com.understory.security.ui.Bg
import com.understory.security.ui.components.SuiteCard
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PRIVATE DNS (STRICT) — a hostname field + Apply that calls
 * [Elevation.setPrivateDns] in Strict (`PrivateDnsMode.HOSTNAME`) mode, and reads the
 * live `private_dns_mode` / `private_dns_specifier` back via the read-only
 * [Elevation.readShell] as an honest confirmation of what was actually applied.
 *
 * This COMPLEMENTS the existing Automatic/Off buttons in the Tools tab — it does not
 * replace them. It is only rendered when a shell can run (the hub gates it), so it is
 * never a dead control; the result of Apply is reported honestly.
 */
@Composable
fun PrivateDnsStrictCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var hostname by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var applyResult by remember { mutableStateOf<String?>(null) }
    var current by remember { mutableStateOf<String?>(null) }
    var readTried by remember { mutableStateOf(false) }

    suspend fun readCurrent(): String? {
        val mode = Elevation.readShell(ctx, listOf("settings", "get", "global", "private_dns_mode"))
            ?.trim()?.takeIf { it.isNotBlank() && it != "null" }
        val host = Elevation.readShell(ctx, listOf("settings", "get", "global", "private_dns_specifier"))
            ?.trim()?.takeIf { it.isNotBlank() && it != "null" }
        return when (mode) {
            null -> null
            "hostname" -> "Strict (DNS-over-TLS) → ${host ?: "a configured host"}"
            "opportunistic" -> "Automatic (opportunistic)"
            "off" -> "Off"
            else -> mode
        }
    }

    // Read the live state on first show.
    LaunchedEffect(Unit) {
        if (!readTried) {
            readTried = true
            current = withContext(Bg.io) { readCurrent() }
        }
    }

    SuiteCard {
        Text(
            stringResource(R.string.control_dns_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(R.string.control_dns_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = com.understory.security.ui.theme.UnderstoryTheme.spacing.xs),
        )

        Spacer(Modifier.height(com.understory.security.ui.theme.UnderstoryTheme.spacing.sm))
        OutlinedTextField(
            value = hostname,
            onValueChange = { hostname = it.trim() },
            label = { Text(stringResource(R.string.control_dns_hostname_label)) },
            placeholder = { Text(stringResource(R.string.control_dns_hostname_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(com.understory.security.ui.theme.UnderstoryTheme.spacing.sm))
        Text(
            text = current?.let { stringResource(R.string.control_dns_current, it) }
                ?: stringResource(R.string.control_dns_current_unknown),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(com.understory.security.ui.theme.UnderstoryTheme.spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(com.understory.security.ui.theme.UnderstoryTheme.spacing.sm)) {
            SecureButton(
                onClick = {
                    if (busy) return@SecureButton
                    if (hostname.isBlank()) {
                        applyResult = ctx.getString(R.string.control_dns_need_host)
                        return@SecureButton
                    }
                    busy = true
                    scope.launch {
                        val outcome = withContext(Bg.io) {
                            Elevation.setPrivateDns(ctx, PrivateDnsMode.HOSTNAME, hostname)
                        }
                        applyResult = when (outcome) {
                            is Outcome.Success -> ctx.getString(R.string.control_result_success, "Private DNS set to Strict → $hostname")
                            is Outcome.Unsupported -> ctx.getString(R.string.control_result_unsupported, outcome.reason)
                            is Outcome.Failed -> ctx.getString(R.string.control_result_failed, outcome.message)
                        }
                        // Read back the live state regardless of the reported outcome.
                        current = withContext(Bg.io) { readCurrent() }
                        busy = false
                    }
                },
                enabled = !busy,
            ) { Text(stringResource(R.string.control_dns_apply)) }
            SecureOutlinedButton(
                onClick = {
                    if (busy) return@SecureOutlinedButton
                    scope.launch { current = withContext(Bg.io) { readCurrent() } }
                },
            ) { Text(stringResource(R.string.control_dns_refresh)) }
        }

        applyResult?.let { msg ->
            Spacer(Modifier.height(com.understory.security.ui.theme.UnderstoryTheme.spacing.xs))
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
