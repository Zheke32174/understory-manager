package com.understory.manager.control

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.understory.elevation.Elevation
import com.understory.elevation.Outcome
import com.understory.manager.ManagerInfoCard
import com.understory.manager.R
import com.understory.security.SecureButton
import com.understory.security.SecureOutlinedButton
import com.understory.security.ui.Bg
import com.understory.security.ui.components.LoadingState
import com.understory.security.ui.components.SuiteCard
import com.understory.security.ui.components.SuiteSectionHeader
import com.understory.security.ui.theme.UnderstoryTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PANIC LOCKDOWN — multi-select a set of installed, non-suite, non-critical apps and
 * suspend them all at once (reversible; greyed-out, cannot launch), with an optional
 * force-stop, and a one-tap un-suspend of the persisted locked set.
 *
 * Doctrine wired in here:
 *  - Reversible-first: the primary action is [Elevation.setAppSuspended] `true`, undone
 *    by the same helper with `false`. There is NO uninstall in this flow.
 *  - Fail-closed: this composable is only reached when a shell can run (the hub gates
 *    on [Elevation.canRunShell]); the pick list and the action both filter through
 *    [ControlExclusions] so an excluded package can never be selected nor acted on.
 *  - Durable + restorable: the suspended set is written to [PanicLockdownStore] so it
 *    survives relaunch and can always be un-suspended.
 *  - Honest reporting: every per-package [Outcome] is shown; no fabricated success.
 */
@Composable
fun PanicLockdownScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PanicLockdownStore(ctx) }

    var eligible by remember { mutableStateOf<List<String>?>(null) }
    val selected = remember { mutableStateListOf<String>() }
    var forceStop by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var lockedCount by remember { mutableStateOf(store.actionable(ctx).size) }

    // Load the eligible list off the main thread.
    LaunchedEffect(Unit) {
        if (eligible == null) {
            eligible = withContext(Bg.io) { ControlExclusions.eligiblePackages(ctx) }
        }
    }

    fun refreshLocked() {
        lockedCount = store.actionable(ctx).size
    }

    fun labelOutcome(outcome: Outcome): String = when (outcome) {
        is Outcome.Success -> "suspended"
        is Outcome.Unsupported -> "not applied: ${outcome.reason}"
        is Outcome.Failed -> "failed: ${outcome.message}"
    }

    Column(verticalArrangement = Arrangement.spacedBy(UnderstoryTheme.spacing.sm)) {
        SuiteSectionHeader(stringResource(R.string.control_panic_pick_title))
        ManagerInfoCard(stringResource(R.string.control_panic_pick_body))

        // Locked-set status + one-tap un-suspend.
        SuiteCard {
            Text(
                stringResource(R.string.control_panic_locked_header),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (lockedCount == 0) stringResource(R.string.control_panic_locked_empty)
                else stringResource(R.string.control_panic_locked_count, lockedCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = UnderstoryTheme.spacing.xs),
            )
            if (lockedCount > 0) {
                Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
                SecureOutlinedButton(
                    onClick = {
                        if (busy) return@SecureOutlinedButton
                        busy = true
                        scope.launch {
                            val toRestore = store.actionable(ctx).toList()
                            val out = withContext(Bg.io) {
                                toRestore.map { pkg ->
                                    val o = Elevation.setAppSuspended(ctx, pkg, false)
                                    pkg to o
                                }
                            }
                            // Remove only the ones that actually un-suspended.
                            val restored = out.filter { it.second is Outcome.Success }.map { it.first }
                            store.removeAll(restored)
                            results = out.map { (pkg, o) ->
                                ControlExclusions.label(ctx, pkg) to when (o) {
                                    is Outcome.Success -> "un-suspended"
                                    is Outcome.Unsupported -> "not applied: ${o.reason}"
                                    is Outcome.Failed -> "failed: ${o.message}"
                                }
                            }
                            refreshLocked()
                            busy = false
                        }
                    },
                ) { Text(stringResource(R.string.control_panic_unsuspend)) }
            }
        }

        val list = eligible
        when {
            list == null ->
                Box(Modifier.fillMaxWidth().height(160.dp)) { LoadingState() }
            list.isEmpty() ->
                ManagerInfoCard(stringResource(R.string.control_panic_empty_list))
            else -> {
                // Force-stop toggle.
                SuiteCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = forceStop,
                            onCheckedChange = { forceStop = it },
                        )
                        Text(
                            stringResource(R.string.control_panic_forcestop),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                // The pick list.
                list.forEach { pkg ->
                    val checked = selected.contains(pkg)
                    SuiteCard(
                        onClick = {
                            if (checked) selected.remove(pkg) else selected.add(pkg)
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { on ->
                                    if (on) { if (!selected.contains(pkg)) selected.add(pkg) }
                                    else selected.remove(pkg)
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    ControlExclusions.label(ctx, pkg),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    pkg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (selected.isEmpty()) {
                    Text(
                        stringResource(R.string.control_panic_none_selected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = UnderstoryTheme.spacing.lg),
                    )
                }

                SecureButton(
                    onClick = {
                        if (busy || selected.isEmpty()) return@SecureButton
                        busy = true
                        // Snapshot the selection; re-filter through exclusions as a
                        // second fail-closed guard before acting.
                        val targets = selected.toList().filterNot { ControlExclusions.isExcluded(ctx, it) }
                        scope.launch {
                            val out = withContext(Bg.io) {
                                targets.map { pkg ->
                                    val suspend = Elevation.setAppSuspended(ctx, pkg, true)
                                    if (suspend is Outcome.Success && forceStop) {
                                        Elevation.forceStop(ctx, pkg)
                                    }
                                    pkg to suspend
                                }
                            }
                            // Persist only the ones that actually suspended.
                            val locked = out.filter { it.second is Outcome.Success }.map { it.first }
                            store.addAll(locked)
                            results = out.map { (pkg, o) ->
                                ControlExclusions.label(ctx, pkg) to labelOutcome(o)
                            }
                            selected.clear()
                            refreshLocked()
                            busy = false
                        }
                    },
                    enabled = !busy && selected.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = UnderstoryTheme.spacing.lg,
                            vertical = UnderstoryTheme.spacing.sm,
                        ),
                ) { Text(stringResource(R.string.control_panic_suspend)) }
            }
        }

        // Per-package honest results.
        if (results.isNotEmpty()) {
            SuiteCard {
                results.forEach { (label, verdict) ->
                    Text(
                        "$label — $verdict",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
