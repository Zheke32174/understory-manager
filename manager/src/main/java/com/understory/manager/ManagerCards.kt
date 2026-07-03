package com.understory.manager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.understory.security.SecureButton
import com.understory.security.SecureOutlinedButton
import com.understory.security.ui.components.SuiteCard
import com.understory.security.ui.theme.UnderstoryTheme

/**
 * Manager-local composable building blocks, all token-native (color/spacing/type
 * come from [UnderstoryTheme]; no hex, no bare dp except the small chip/dot
 * geometry which reads shape tokens). They compose the shared [SuiteCard] and
 * [SecureButton] so the Manager never re-implements chrome.
 */

/** A small pill chip: a label on a tinted container. Used for verified/capability marks. */
@Composable
fun ManagerChip(
    text: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(
                horizontal = UnderstoryTheme.spacing.sm,
                vertical = UnderstoryTheme.spacing.xs,
            ),
        )
    }
}

/**
 * One suite app row inside a [SuiteCard]. Installed apps show name, purpose,
 * version, an optional verified + capability chip, and an "Open" action.
 * Not-installed apps render subtly (dim) with a "not installed" line and no
 * action — the honest degrade.
 */
@Composable
fun SuiteAppCard(
    status: SuiteDetection.SuiteAppStatus,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val installed = status.installed
    SuiteCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = status.app.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (installed) MaterialTheme.colorScheme.onSurface
                    else UnderstoryTheme.semantic.dim,
                )
                Text(
                    text = status.app.purpose,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = UnderstoryTheme.spacing.xs),
                )
            }
            if (installed) {
                SecureButton(onClick = onOpen) { Text("Open") }
            }
        }

        // Status line: version + chips, or the honest "not installed".
        Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
        if (!installed) {
            Text(
                text = "Not installed — install it to add this to your suite.",
                style = MaterialTheme.typography.bodySmall,
                color = UnderstoryTheme.semantic.dim,
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(UnderstoryTheme.spacing.sm),
            ) {
                Text(
                    text = status.versionName?.let { "v$it" } ?: "version unavailable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (status.tampered) {
                    ManagerChip(
                        text = "cert mismatch",
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else if (status.certVerified) {
                    ManagerChip(
                        text = "verified",
                        container = UnderstoryTheme.semantic.successContainer,
                        content = UnderstoryTheme.semantic.success,
                    )
                }
                status.verifiedCapability?.let { cap ->
                    ManagerChip(
                        text = "provides ${capabilityLabel(cap)}",
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            if (status.tampered) {
                Text(
                    text = "This sibling is installed but its signature does not match the " +
                        "suite key. Reinstall it from a trusted source.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = UnderstoryTheme.spacing.xs),
                )
            }
        }
    }
}

/** A single security-sweep row: a leading severity dot, title, and detail + optional deep-link. */
@Composable
fun PostureItemCard(
    item: DevicePosture.PostureItem,
    onDeepLink: (() -> Unit)?,
    deepLinkLabel: String?,
    modifier: Modifier = Modifier,
) {
    SuiteCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.Top) {
            SeverityDot(item.level)
            Spacer(Modifier.width(UnderstoryTheme.spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = item.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = UnderstoryTheme.spacing.xs),
                )
                if (onDeepLink != null && deepLinkLabel != null) {
                    Spacer(Modifier.height(UnderstoryTheme.spacing.xs))
                    SecureOutlinedButton(onClick = onDeepLink) { Text(deepLinkLabel) }
                }
            }
        }
    }
}

/** A colored severity marker icon for a posture item. */
@Composable
private fun SeverityDot(level: DevicePosture.Level) {
    val (icon: ImageVector, tint: Color) = when (level) {
        DevicePosture.Level.OK -> Icons.Filled.CheckCircle to UnderstoryTheme.semantic.success
        DevicePosture.Level.INFO -> Icons.Filled.Info to MaterialTheme.colorScheme.onSurfaceVariant
        DevicePosture.Level.WARN -> Icons.Filled.Warning to UnderstoryTheme.semantic.warning
    }
    Icon(
        imageVector = icon,
        contentDescription = null, // the title carries meaning
        tint = tint,
        modifier = Modifier.size(24.dp),
    )
}

/**
 * The sweep summary banner: a token-tinted surface stating the worst level and
 * a one-line summary. Honest — it summarizes, it does not verdict.
 */
@Composable
fun SweepSummaryBanner(report: DevicePosture.Report, modifier: Modifier = Modifier) {
    val (container, content, headline) = when (report.worst) {
        DevicePosture.Level.OK -> Triple(
            UnderstoryTheme.semantic.successContainer,
            UnderstoryTheme.semantic.success,
            "No high-power grants stood out",
        )
        DevicePosture.Level.INFO -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "A few things to be aware of",
        )
        DevicePosture.Level.WARN -> Triple(
            UnderstoryTheme.semantic.warningContainer,
            UnderstoryTheme.semantic.warning,
            "Worth a closer look",
        )
    }
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(UnderstoryTheme.spacing.lg)) {
            Text(headline, style = MaterialTheme.typography.titleMedium)
            Text(
                "This is a rootless summary. Tap through to a specialist app for a deeper audit.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = UnderstoryTheme.spacing.xs),
            )
        }
    }
}

/** A dim vault-app recovery reminder row inside a [SuiteCard]. */
@Composable
fun RecoveryAppCard(
    status: SuiteDetection.SuiteAppStatus,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SuiteCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = status.app.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (status.installed) MaterialTheme.colorScheme.onSurface
                    else UnderstoryTheme.semantic.dim,
                )
                Text(
                    text = if (status.installed) {
                        "Open it and export its recovery file. Keep that export somewhere safe " +
                            "and offline — the Manager cannot read or back up its vault for you."
                    } else {
                        "Not installed."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = UnderstoryTheme.spacing.xs),
                )
            }
            if (status.installed) {
                SecureButton(onClick = onOpen) { Text("Open") }
            }
        }
    }
}

/** Simple section-intro paragraph card. */
@Composable
fun ManagerInfoCard(text: String, modifier: Modifier = Modifier) {
    SuiteCard(modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Centered empty-suite prompt (only the Manager itself installed). */
@Composable
fun SingleAppEmptyState(modifier: Modifier = Modifier) {
    SuiteCard(modifier = modifier) {
        Text(
            "No other Understory apps are installed yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "The Manager is optional — every suite app works on its own. Install any of the " +
                "apps below and they will appear here for one-tap access, capability checks, and " +
                "recovery reminders.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = UnderstoryTheme.spacing.sm),
        )
    }
}

/** Human labels for capabilities the dashboard advertises. */
fun capabilityLabel(cap: com.understory.security.SuiteCapability): String = when (cap) {
    com.understory.security.SuiteCapability.IDENTITY_VAULT -> "identity vault"
    com.understory.security.SuiteCapability.OTP_STORE -> "OTP storage"
    com.understory.security.SuiteCapability.OTP_VAULT -> "OTP codes"
    com.understory.security.SuiteCapability.NET_POSTURE_AUDIT -> "network audit"
    com.understory.security.SuiteCapability.FILE_VAULT -> "file vault"
    com.understory.security.SuiteCapability.BACKUP_ENVELOPE -> "backup envelope"
    com.understory.security.SuiteCapability.APK_AUDITOR -> "APK audit"
    com.understory.security.SuiteCapability.HARDENED_BROWSER -> "trusted browser"
    com.understory.security.SuiteCapability.SECURE_MESSENGER -> "secure messaging"
    com.understory.security.SuiteCapability.LOCAL_POLICY -> "local policy"
}
