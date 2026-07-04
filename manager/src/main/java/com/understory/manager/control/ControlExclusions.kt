package com.understory.manager.control

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * The single source of truth for which packages the Control center's app-affecting
 * actions (panic lockdown in particular) MUST NEVER touch.
 *
 * The exclusion is deliberately conservative and fail-closed: it is applied to BOTH
 * the pick list AND the action, so a hard-excluded package can never be selected
 * NOR suspended even if a stale saved set somehow references it. It hard-excludes:
 *
 *  - every Understory suite package (`com.understory.*`) — the Manager never turns
 *    the suite against itself,
 *  - the current default launcher (suspending it would strand the user),
 *  - `com.android.settings` (the escape hatch to fix anything),
 *  - `com.tailscale.ipn` (the box's only remote-access path — never sever it),
 *  - the Shizuku / Sui manager packages (the grant surface these actions depend on),
 *  - the Manager's own package,
 *  - system apps that are not user-updatable (locking these can brick basic device
 *    function; the panic flow is for user-installed apps).
 *
 * Everything is resolved defensively — an OEM quirk or a missing package never throws;
 * the predicate simply treats an unresolvable value as "excluded/absent" rather than
 * accidentally allowing an action.
 */
object ControlExclusions {

    /** Understory suite package prefix — every suite app is always excluded. */
    private const val SUITE_PREFIX = "com.understory."

    /** Connectivity / settings / grant-surface packages that must never be suspended. */
    private val ALWAYS_EXCLUDED: Set<String> = setOf(
        "com.android.settings",
        // Tailscale — the box's remote-access path; severing it is never acceptable.
        "com.tailscale.ipn",
        // Shizuku and the Sui (Riru/LSPosed) manager: the elevation grant surface.
        "moe.shizuku.privileged.api",
        "com.rikka.shizuku",
        "io.github.vvb2060.magisk", // common Sui host manager id
    )

    /**
     * The full exclusion predicate. Returns true when [pkg] must be hidden from the
     * pick list and skipped by the action. Fail-closed: on any read error the package
     * is treated as excluded rather than risking an action on it.
     */
    fun isExcluded(ctx: Context, pkg: String): Boolean = runCatching {
        if (pkg.isBlank()) return@runCatching true
        if (pkg == ctx.packageName) return@runCatching true
        if (pkg.startsWith(SUITE_PREFIX)) return@runCatching true
        if (pkg in ALWAYS_EXCLUDED) return@runCatching true
        if (pkg == currentLauncherPackage(ctx)) return@runCatching true
        if (isNonUpdatedSystemApp(ctx, pkg)) return@runCatching true
        false
    }.getOrDefault(true)

    /**
     * Installed, user-facing, NON-excluded, non-critical packages eligible for the
     * panic lockdown pick list. Sorted by human label. Only apps that HAVE a launcher
     * entry are offered (a headless service package is not a meaningful "app" to lock,
     * and suspending some of those is risky) — this is the honest, safe subset.
     */
    fun eligiblePackages(ctx: Context): List<String> = runCatching {
        launchablePackages(ctx)
            .filterNot { isExcluded(ctx, it) }
            .distinct()
            .sortedBy { label(ctx, it).lowercase() }
    }.getOrDefault(emptyList())

    /** Human label for a package, falling back to the raw id. */
    fun label(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** Whether the package is currently installed (defensive; false on any error). */
    fun isInstalled(ctx: Context, pkg: String): Boolean = runCatching {
        ctx.packageManager.getApplicationInfo(pkg, 0)
        true
    }.getOrDefault(false)

    // ---- internals ----------------------------------------------------------

    /** Packages that have a launcher activity (the meaningful user-app surface). */
    private fun launchablePackages(ctx: Context): List<String> = runCatching {
        val pm = ctx.packageManager
        pm.getInstalledApplications(0)
            .map { it.packageName }
            .filter { pm.getLaunchIntentForPackage(it) != null }
    }.getOrDefault(emptyList())

    /** The default HOME (launcher) package right now, or null. */
    private fun currentLauncherPackage(ctx: Context): String? = runCatching {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        val resolve = ctx.packageManager.resolveActivity(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        resolve?.activityInfo?.packageName
    }.getOrNull()

    /** True for a system app that has NOT been updated by the user (locking these is unsafe). */
    private fun isNonUpdatedSystemApp(ctx: Context, pkg: String): Boolean = runCatching {
        val info = ctx.packageManager.getApplicationInfo(pkg, 0)
        val system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val updatedSystem = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        system && !updatedSystem
    }.getOrDefault(false)
}
