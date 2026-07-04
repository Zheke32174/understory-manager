package com.understory.manager.control

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.understory.elevation.Elevation
import com.understory.elevation.ui.rememberElevationState
import com.understory.manager.DevicePosture
import com.understory.manager.ManagerInfoCard
import com.understory.manager.R
import com.understory.security.SecureButton
import com.understory.security.ui.Bg
import com.understory.security.ui.components.SuiteCard
import com.understory.security.ui.components.SuiteSectionHeader
import com.understory.security.ui.theme.UnderstoryTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Control center hub — the "Control" bottom-nav destination.
 *
 * Hosts the four elevated actions (panic lockdown, grant review, strict Private DNS,
 * deep posture read), each reusing the shared design system. It is uniformly
 * FAIL-CLOSED: when [Elevation.canRunShell] is false, NONE of the action surfaces
 * render — instead an honest locked card routes the user to the existing Elevation
 * grant flow (the Tools tab's Elevation center). It reads the shared elevation state
 * via [rememberElevationState] so it lights up reactively the moment the grant lands.
 * No dead or no-op control is ever shown.
 *
 * @param onOpenElevation navigates to the Tools tab where the grant flow lives.
 * @param onOpenAccessibilitySettings system accessibility settings deep-link.
 * @param onOpenDeviceAdminSettings system device-admin settings deep-link.
 */
@Composable
fun ControlCenterScreen(
    pad: PaddingValues,
    onOpenElevation: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenDeviceAdminSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    // React to grant changes: rememberElevationState recomputes on refreshKey change,
    // and the whole screen re-reads canRunShell from it so controls appear/disappear.
    val elevation = rememberElevationState()
    val canRun = elevation.isElevated && Elevation.canRunShell(ctx)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(pad)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = UnderstoryTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(UnderstoryTheme.spacing.sm),
    ) {
        Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
        ManagerInfoCard(stringResource(R.string.control_intro))

        if (!canRun) {
            // Fail-closed: honest locked card routing to the grant flow. No dead action.
            LockedCard(onOpenElevation = onOpenElevation)
            Spacer(Modifier.height(UnderstoryTheme.spacing.xxl))
            return@Column
        }

        ManagerInfoCard(stringResource(R.string.control_shell_ready))

        // 1) PANIC LOCKDOWN
        SuiteSectionHeader(stringResource(R.string.control_panic_title))
        ManagerInfoCard(stringResource(R.string.control_panic_desc))
        PanicLockdownScreen()

        Spacer(Modifier.height(UnderstoryTheme.spacing.md))

        // 2) GRANT REVIEW
        SuiteSectionHeader(stringResource(R.string.control_grant_title))
        GrantReviewScreen(
            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
            onOpenDeviceAdminSettings = onOpenDeviceAdminSettings,
        )

        Spacer(Modifier.height(UnderstoryTheme.spacing.md))

        // 3) PRIVATE DNS STRICT
        SuiteSectionHeader(stringResource(R.string.control_dns_title))
        PrivateDnsStrictCard()

        Spacer(Modifier.height(UnderstoryTheme.spacing.md))

        // 4) DEEP POSTURE READ
        SuiteSectionHeader(stringResource(R.string.control_posture_title))
        DeepPostureReadCard()

        Spacer(Modifier.height(UnderstoryTheme.spacing.xxl))
    }
}

/** Honest fail-closed card shown when no privileged shell is granted. */
@Composable
private fun LockedCard(onOpenElevation: () -> Unit) {
    SuiteCard {
        Text(
            stringResource(R.string.control_locked_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(R.string.control_locked_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = UnderstoryTheme.spacing.xs),
        )
        Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
        SecureButton(onClick = onOpenElevation) {
            Text(stringResource(R.string.control_open_elevation))
        }
    }
}

/**
 * DEEP POSTURE READ card — runs [DeepPostureRead], which enriches the rootless sweep
 * with additive privileged reads. Only reached when a shell can run; every extra row
 * degrades to omission on a null/parse miss, so it is strictly additive.
 */
@Composable
private fun DeepPostureReadCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<DeepPostureRead.ExtraRow>?>(null) }
    var running by remember { mutableStateOf(false) }

    ManagerInfoCard(stringResource(R.string.control_posture_desc))

    val run: () -> Unit = {
        if (!running) {
            running = true
            scope.launch {
                rows = withContext(Bg.io) { DeepPostureRead.read(ctx) }
                running = false
            }
        }
    }

    val current = rows
    when {
        running && current == null ->
            Box(Modifier.fillMaxWidth().height(120.dp)) {
                Text(
                    stringResource(R.string.control_posture_running),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        current == null ->
            SecureButton(onClick = run, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.control_posture_run))
            }
        else -> {
            if (current.isEmpty()) {
                ManagerInfoCard(stringResource(R.string.control_posture_empty))
            } else {
                current.forEach { row -> ExtraPostureRow(row) }
            }
            SecureButton(onClick = run, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.control_posture_rerun))
            }
        }
    }
}

/** A single deep-posture row, tinted by its severity level. */
@Composable
private fun ExtraPostureRow(row: DeepPostureRead.ExtraRow) {
    SuiteCard {
        Text(
            row.title,
            style = MaterialTheme.typography.titleMedium,
            color = when (row.level) {
                DevicePosture.Level.WARN -> UnderstoryTheme.semantic.warning
                DevicePosture.Level.OK -> UnderstoryTheme.semantic.success
                DevicePosture.Level.INFO -> MaterialTheme.colorScheme.onSurface
            },
        )
        Text(
            row.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = UnderstoryTheme.spacing.xs),
        )
    }
}
