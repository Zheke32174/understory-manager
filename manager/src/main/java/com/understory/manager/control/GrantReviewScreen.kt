package com.understory.manager.control

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
 * ACTIONABLE GRANT REVIEW — pick an installed app, list its dangerous grants/appops
 * (via [GrantReview], which uses the read-only [Elevation.readShell] and degrades to
 * the rootless PackageManager view on a null read), and one-tap revoke a permission
 * ([Elevation.revokePermission]) or set an appop to ignore ([Elevation.setAppOpMode]),
 * with UNDO. Accessibility + device-admin are deep-linked to system Settings — never a
 * dead button.
 *
 * Reversible-first: revoke is undone by re-granting through Settings; an appop set to
 * ignore is undone with [Elevation.setAppOpMode] `default`. Every action reports its
 * [Outcome] honestly.
 *
 * @param onOpenAccessibilitySettings routes to system accessibility settings.
 * @param onOpenDeviceAdminSettings routes to system device-admin / security settings.
 */
@Composable
fun GrantReviewScreen(
    onOpenAccessibilitySettings: () -> Unit,
    onOpenDeviceAdminSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var pickable by remember { mutableStateOf<List<String>?>(null) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<GrantReview.Result?>(null) }
    // Per-grant last-action verdict (keyed by grant id) shown inline.
    var verdicts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(Unit) {
        if (pickable == null) {
            pickable = withContext(Bg.io) { ControlExclusions.eligiblePackages(ctx) }
        }
    }

    fun runReview(pkg: String) {
        chosen = pkg
        loading = true
        result = null
        verdicts = emptyMap()
        scope.launch {
            result = withContext(Bg.io) { GrantReview.review(ctx, pkg) }
            loading = false
        }
    }

    fun setVerdict(id: String, text: String) {
        verdicts = verdicts.toMutableMap().apply { put(id, text) }
    }

    fun verdictText(outcome: Outcome, successText: String): String = when (outcome) {
        is Outcome.Success -> successText
        is Outcome.Unsupported -> stringResourceRuntime(ctx, R.string.control_result_unsupported, outcome.reason)
        is Outcome.Failed -> stringResourceRuntime(ctx, R.string.control_result_failed, outcome.message)
    }

    Column(verticalArrangement = Arrangement.spacedBy(UnderstoryTheme.spacing.sm)) {
        SuiteSectionHeader(stringResource(R.string.control_grant_pick))
        ManagerInfoCard(stringResource(R.string.control_grant_desc))

        val apps = pickable
        if (chosen == null) {
            when {
                apps == null ->
                    Box(Modifier.fillMaxWidth().height(160.dp)) { LoadingState() }
                apps.isEmpty() ->
                    ManagerInfoCard(stringResource(R.string.control_panic_empty_list))
                else -> apps.forEach { pkg ->
                    SuiteCard(onClick = { runReview(pkg) }) {
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
        } else {
            // Reviewing a chosen app.
            SuiteCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            ControlExclusions.label(ctx, chosen!!),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            chosen!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SecureOutlinedButton(onClick = {
                        chosen = null
                        result = null
                        verdicts = emptyMap()
                    }) { Text(stringResource(R.string.action_cancel)) }
                }
            }

            when {
                loading ->
                    Box(Modifier.fillMaxWidth().height(160.dp)) {
                        LoadingState(label = stringResource(R.string.control_grant_reading))
                    }
                result != null -> {
                    val r = result!!
                    if (r.degraded) {
                        ManagerInfoCard(stringResource(R.string.control_grant_degraded))
                    }

                    val active = r.grants.filter { it.active }
                    if (active.isEmpty()) {
                        ManagerInfoCard(stringResource(R.string.control_grant_none))
                    } else {
                        active.forEach { grant ->
                            GrantRow(
                                grant = grant,
                                verdict = verdicts[grant.id],
                                onRevoke = {
                                    scope.launch {
                                        val o = withContext(Bg.io) { Elevation.revokePermission(ctx, r.pkg, grant.id) }
                                        setVerdict(grant.id, verdictText(o, "revoked"))
                                    }
                                },
                                onIgnore = {
                                    scope.launch {
                                        val o = withContext(Bg.io) { Elevation.setAppOpMode(ctx, r.pkg, grant.id, "ignore") }
                                        setVerdict(grant.id, verdictText(o, "set to ignore"))
                                    }
                                },
                                onUndo = {
                                    scope.launch {
                                        val o = when (grant.kind) {
                                            // Runtime perms are re-granted in Settings (system-gated);
                                            // appops undo restores the default mode.
                                            GrantReview.Kind.PERMISSION ->
                                                Outcome.Unsupported("re-grant this permission in system Settings")
                                            GrantReview.Kind.APPOP ->
                                                withContext(Bg.io) { Elevation.setAppOpMode(ctx, r.pkg, grant.id, "default") }
                                        }
                                        setVerdict(grant.id, verdictText(o, "restored to default"))
                                    }
                                },
                            )
                        }
                    }

                    // System-gated: a11y + device-admin are links, never dead buttons.
                    if (r.holdsAccessibility) {
                        SuiteCard {
                            Text(
                                stringResource(R.string.control_grant_a11y_note),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
                            SecureOutlinedButton(onClick = onOpenAccessibilitySettings) {
                                Text(stringResource(R.string.control_grant_a11y_settings))
                            }
                        }
                    }
                    if (r.holdsDeviceAdmin) {
                        SuiteCard {
                            Text(
                                stringResource(R.string.control_grant_admin_note),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
                            SecureOutlinedButton(onClick = onOpenDeviceAdminSettings) {
                                Text(stringResource(R.string.control_grant_admin_settings))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One reviewable grant row with its actions and an inline honest verdict. */
@Composable
private fun GrantRow(
    grant: GrantReview.Grant,
    verdict: String?,
    onRevoke: () -> Unit,
    onIgnore: () -> Unit,
    onUndo: () -> Unit,
) {
    SuiteCard {
        Text(
            grant.label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            grant.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = UnderstoryTheme.spacing.xs),
        )
        Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(UnderstoryTheme.spacing.sm)) {
            when (grant.kind) {
                GrantReview.Kind.PERMISSION ->
                    SecureButton(onClick = onRevoke) { Text(stringResource(R.string.control_grant_revoke)) }
                GrantReview.Kind.APPOP ->
                    SecureButton(onClick = onIgnore) { Text(stringResource(R.string.control_grant_ignore)) }
            }
            SecureOutlinedButton(onClick = onUndo) { Text(stringResource(R.string.control_grant_undo)) }
        }
        if (verdict != null) {
            Text(
                verdict,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = UnderstoryTheme.spacing.xs),
            )
        }
    }
}

/** Resolve a formatted string outside composition (for coroutine verdicts). */
private fun stringResourceRuntime(
    ctx: android.content.Context,
    resId: Int,
    arg: String,
): String = ctx.getString(resId, arg)
