package com.understory.manager

import android.content.Context
import android.content.pm.PackageManager
import com.understory.security.SuiteAttestation
import com.understory.security.SuiteCapability
import com.understory.security.SuiteCapabilityRegistry

/**
 * The Manager's read-only view of the installed suite.
 *
 * For each of the seven [SuiteApp]s this reports:
 *   - whether it is installed, and its [versionName] (honest: only what
 *     PackageManager returns — never fabricated),
 *   - whether it is cert-verified against the suite pin (it shares the suite
 *     signing key, so a same-key peer verifies; a repackaged one does not),
 *   - the capability the live [SuiteCapabilityRegistry] confirms the installed
 *     peer actually maps at its attested version (or null),
 *   - whether the suite attestation flagged it as tampered (installed but
 *     cert-mismatched).
 *
 * Everything here is rootless and in-bounds: install/version come from
 * PackageManager over the manifest <queries> visibility; verified caps come from
 * the signature-gated SuiteCapsProvider the Manager may read because it holds the
 * suite CAPS permission (same key). When a peer's cap cannot be read cross-UID —
 * e.g. it exposes no provider, or the query fails — the Manager falls back to
 * install + version only. No placeholder capability is ever shown.
 */
object SuiteDetection {

    /** Per-app detection result. Absent-but-known apps are represented too. */
    data class SuiteAppStatus(
        val app: SuiteApp,
        val installed: Boolean,
        /** Raw versionName from PackageManager, or null if absent/unreadable. */
        val versionName: String?,
        /** True when the installed peer's signing cert matched the suite pin. */
        val certVerified: Boolean,
        /**
         * True when attestation found this sibling installed but cert-MISMATCHED.
         * A tampered sibling is a suite-wide integrity signal the dashboard
         * surfaces prominently.
         */
        val tampered: Boolean,
        /**
         * The capability the live registry confirmed for this installed,
         * cert-verified peer at its attested version — or null when none is
         * mapped (e.g. browser v1) or the peer cap could not be read cross-UID.
         * NEVER derived from [SuiteApp.expectedCapability] alone.
         */
        val verifiedCapability: SuiteCapability?,
    ) {
        /** Whether a "provides…" capability chip should render for this app. */
        val hasVerifiedCapability: Boolean get() = verifiedCapability != null
    }

    /** Whole-suite snapshot the dashboard renders from. */
    data class Snapshot(
        val statuses: List<SuiteAppStatus>,
        /**
         * The live capability-registry snapshot (this Manager + verified peers).
         * Exposes the cumulative [SuiteCapabilityRegistry.Snapshot.tier] and the
         * union of confirmed capabilities for callers that want them.
         */
        val registry: SuiteCapabilityRegistry.Snapshot,
    ) {
        val installedCount: Int get() = statuses.count { it.installed }
        val verifiedCount: Int get() = statuses.count { it.installed && it.certVerified }
        val tamperedApps: List<SuiteApp> get() = statuses.filter { it.tampered }.map { it.app }
        fun forCategory(cat: SuiteCategory): List<SuiteAppStatus> =
            statuses.filter { it.app.category == cat }
    }

    /**
     * Build the full snapshot. Cheap (a handful of PackageManager + one
     * ContentResolver query per installed peer) — safe to run on a background
     * dispatcher from the ViewModel and re-run on PACKAGE_ADDED/REMOVED.
     */
    fun snapshot(ctx: Context): Snapshot {
        val pm = ctx.packageManager
        // Live capability registry — the authoritative, cert-checked source of
        // which installed peer verifiably provides which capability. If the
        // query itself fails we fall back to an empty peer list (install +
        // version still render; just no verified-cap chips) rather than
        // fabricating data or crashing the dashboard.
        val registry = runCatching { SuiteCapabilityRegistry.snapshot(ctx) }.getOrNull()
            ?: SuiteCapabilityRegistry.Snapshot(ownPackage = ctx.packageName, peers = emptyList())
        // Attestation — which installed siblings are cert-mismatched (tampered).
        val attestation = runCatching { SuiteAttestation.verify(ctx) }.getOrNull()
        val tamperedSet = attestation?.tamperedSiblings?.toSet() ?: emptySet()

        val statuses = SuiteApp.all().map { app ->
            val info = runCatching {
                pm.getPackageInfo(app.packageId, 0)
            }.getOrNull()
            val installed = info != null
            val versionName = info?.versionName

            // A peer entry appears in the registry's peer list only when it was
            // discovered AND cert-checked. Match by package.
            val peer = registry.peers.firstOrNull { it.packageName == app.packageId }
            val certVerified = peer?.certVerified == true
            // The registry only populates capabilities for cert-verified peers
            // at a known version; take the first (each app maps at most one).
            val verifiedCap = if (certVerified) peer?.capabilities?.firstOrNull() else null

            SuiteAppStatus(
                app = app,
                installed = installed,
                versionName = versionName,
                certVerified = certVerified,
                tampered = app.packageId in tamperedSet,
                verifiedCapability = verifiedCap,
            )
        }

        return Snapshot(
            statuses = statuses,
            registry = registry,
        )
    }
}
