package com.understory.manager

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.understory.security.Diagnostics

/**
 * The Manager's launch + deep-link helper.
 *
 * The Manager ORCHESTRATES: it never reads a peer's data cross-UID and never
 * performs a specialist's deep action itself. Its job is to open the right app
 * or the right system screen. Every launch is try/catch with an honest boolean
 * so a caller disables-with-reason rather than leaving a dead control.
 */
object ManagerDeepLinks {

    /**
     * Launch a suite peer via its package launcher intent. Returns true if an
     * activity started. False when the app is not installed or has no launcher —
     * the caller should not show an "Open" action in that case.
     */
    fun launchApp(ctx: Context, packageId: String): Boolean {
        val intent = ctx.packageManager.getLaunchIntentForPackage(packageId)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return false
        return try {
            ctx.startActivity(intent)
            true
        } catch (t: Throwable) {
            Diagnostics.error("manager.DeepLink", "launch $packageId threw: ${t.javaClass.simpleName}")
            false
        }
    }

    /** Whether [packageId] is installed AND has a launchable activity. */
    fun canLaunch(ctx: Context, packageId: String): Boolean =
        ctx.packageManager.getLaunchIntentForPackage(packageId) != null

    /** Convenience: launch a [SuiteApp]. */
    fun launch(ctx: Context, app: SuiteApp): Boolean = launchApp(ctx, app.packageId)

    /**
     * Route a security-sweep item to its specialist app when installed, else to
     * the closest system settings screen. Never dead-ends: returns true whenever
     * *something* opened.
     */
    fun openPostureTarget(ctx: Context, target: DevicePosture.PostureDeepLink): Boolean =
        when (target) {
            DevicePosture.PostureDeepLink.APK_CHECK ->
                launchApp(ctx, SuiteApp.ANTIVIRUS.packageId) ||
                    launchFirst(ctx, listOf(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, Settings.ACTION_SETTINGS))
            DevicePosture.PostureDeepLink.NET_AUDIT ->
                launchApp(ctx, SuiteApp.FIREWALL.packageId) ||
                    launchFirst(ctx, listOf(privateDnsSettingsAction(), Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS))
            DevicePosture.PostureDeepLink.ACCESSIBILITY_SETTINGS ->
                launchFirst(ctx, listOf(Settings.ACTION_ACCESSIBILITY_SETTINGS, Settings.ACTION_SETTINGS))
        }

    /** Whether a posture target has any reachable destination on this device. */
    fun canOpenPostureTarget(ctx: Context, target: DevicePosture.PostureDeepLink): Boolean =
        when (target) {
            DevicePosture.PostureDeepLink.APK_CHECK ->
                canLaunch(ctx, SuiteApp.ANTIVIRUS.packageId) ||
                    resolves(ctx, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
            DevicePosture.PostureDeepLink.NET_AUDIT ->
                canLaunch(ctx, SuiteApp.FIREWALL.packageId) ||
                    resolves(ctx, privateDnsSettingsAction()) ||
                    resolves(ctx, Settings.ACTION_WIRELESS_SETTINGS)
            DevicePosture.PostureDeepLink.ACCESSIBILITY_SETTINGS ->
                resolves(ctx, Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }

    /**
     * The rootless fallback for the Elevation Center when no elevation tier is
     * grantable: open the all-apps settings list, then the top-level Settings.
     */
    fun openApplicationSettings(ctx: Context): Boolean =
        launchFirst(ctx, listOf(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, Settings.ACTION_SETTINGS))

    /** Open the Private DNS / wireless settings so the user can set it themselves. */
    fun openPrivateDnsSettings(ctx: Context): Boolean =
        launchFirst(ctx, listOf(privateDnsSettingsAction(), Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS))

    /**
     * Open the system accessibility settings so the user can review/turn off an app's
     * accessibility service. Accessibility is system-gated — the Manager routes to it
     * rather than offering a (nonexistent) direct toggle. Never a dead end.
     */
    fun openAccessibilitySettings(ctx: Context): Boolean =
        launchFirst(ctx, listOf(Settings.ACTION_ACCESSIBILITY_SETTINGS, Settings.ACTION_SETTINGS))

    /**
     * Open the system security / device-admin settings so the user can remove an active
     * device-admin (which blocks uninstall). ACTION_SECURITY_SETTINGS hosts the device
     * administrators list on AOSP; fall back to the top-level Settings. Never a dead end.
     */
    fun openDeviceAdminSettings(ctx: Context): Boolean =
        launchFirst(ctx, listOf(Settings.ACTION_SECURITY_SETTINGS, Settings.ACTION_SETTINGS))

    // ---- internals ----------------------------------------------------------

    /**
     * No public constant opens the Private-DNS panel directly on every OEM;
     * ACTION_WIRELESS_SETTINGS is the reachable surface. The named action here
     * is the documented AOSP host of the Private DNS toggle when present.
     */
    private fun privateDnsSettingsAction(): String = Settings.ACTION_WIRELESS_SETTINGS

    private fun launchFirst(ctx: Context, actions: List<String>): Boolean {
        for (action in actions) {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                // try the next fallback
            } catch (t: Throwable) {
                Diagnostics.error("manager.DeepLink", "launch $action threw: ${t.javaClass.simpleName}")
            }
        }
        Diagnostics.error("manager.DeepLink", "no reachable settings target for $actions")
        return false
    }

    private fun resolves(ctx: Context, action: String): Boolean = runCatching {
        Intent(action).resolveActivity(ctx.packageManager) != null
    }.getOrDefault(false)
}
