package com.understory.manager

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat

/**
 * The Manager's ROOTLESS device-posture read for the one-tap security sweep.
 *
 * Every read here is something the Manager itself can do with no cross-UID magic
 * and no privileged slot: it ENUMERATES which apps currently hold accessibility
 * / device-admin / notification-listener access (readable by any app rootless —
 * the Manager holds none of those slots itself), reads the Private DNS global
 * setting, and counts installed non-system apps. It is deliberately a SUMMARY:
 * where a deeper audit is warranted it routes the user to the specialist app
 * (APK Check for a full installed-app audit, Net Audit for network posture). It
 * does NOT duplicate those deep scanners.
 *
 * This mirrors the read patterns the antivirus/firewall apps use, implemented
 * locally so the Manager degrades to exactly what IT can see even when no
 * specialist app is installed.
 */
object DevicePosture {

    /** A single sweep line: a name, a human verdict, and how alarming it is. */
    data class PostureItem(
        val title: String,
        val detail: String,
        val level: Level,
        /** Which specialist deep-link (if any) offers deeper action on this item. */
        val deepLink: PostureDeepLink? = null,
    )

    /** Severity ladder for a posture line. Maps to a token color in the UI. */
    enum class Level { OK, INFO, WARN }

    /** Which specialist app can act more deeply on a posture item. */
    enum class PostureDeepLink { APK_CHECK, NET_AUDIT, ACCESSIBILITY_SETTINGS }

    /** The full sweep result. */
    data class Report(
        val items: List<PostureItem>,
        /** Packages currently holding an enabled accessibility service. */
        val accessibilityHolders: Set<String>,
        /** Packages that are an active device administrator. */
        val deviceAdminHolders: Set<String>,
        /** Packages with notification-listener access. */
        val notificationListenerHolders: Set<String>,
        /** Count of installed user (non-system) apps. */
        val userAppCount: Int,
        /** Private DNS mode as a human string. */
        val privateDnsSummary: String,
    ) {
        /** Highest severity present — drives the summary banner tone. */
        val worst: Level
            get() = when {
                items.any { it.level == Level.WARN } -> Level.WARN
                items.any { it.level == Level.INFO } -> Level.INFO
                else -> Level.OK
            }
    }

    /**
     * Run every rootless read and assemble the report. Safe to call on a
     * background dispatcher; each read is individually runCatching-guarded so a
     * single OEM quirk never fails the whole sweep.
     */
    fun sweep(ctx: Context): Report {
        val a11y = enabledAccessibilityPackages(ctx)
        val admins = activeDeviceAdminPackages(ctx)
        val listeners = enabledNotificationListenerPackages(ctx)
        val userApps = userAppCount(ctx)
        val dns = privateDnsState(ctx)

        // Human-readable, comma-joined labels for a set of packages.
        fun labels(pkgs: Set<String>): String =
            pkgs.map { labelFor(ctx, it) }.sorted().joinToString(", ")

        val items = buildList {
            // Accessibility — the highest-power surface a malicious app can hold.
            add(
                if (a11y.isEmpty()) {
                    PostureItem(
                        title = "Accessibility services",
                        detail = "No app currently holds accessibility access.",
                        level = Level.OK,
                    )
                } else {
                    PostureItem(
                        title = "Accessibility services",
                        detail = "${a11y.size} app(s) can read the screen and inject taps: " +
                            labels(a11y),
                        level = Level.WARN,
                        deepLink = PostureDeepLink.ACCESSIBILITY_SETTINGS,
                    )
                },
            )
            // Device administrators.
            add(
                if (admins.isEmpty()) {
                    PostureItem(
                        title = "Device administrators",
                        detail = "No active device-admin app.",
                        level = Level.OK,
                    )
                } else {
                    PostureItem(
                        title = "Device administrators",
                        detail = "${admins.size} active device-admin app(s): " +
                            labels(admins),
                        level = Level.WARN,
                        deepLink = PostureDeepLink.APK_CHECK,
                    )
                },
            )
            // Notification listeners.
            add(
                if (listeners.isEmpty()) {
                    PostureItem(
                        title = "Notification listeners",
                        detail = "No app can read your notifications.",
                        level = Level.OK,
                    )
                } else {
                    PostureItem(
                        title = "Notification listeners",
                        detail = "${listeners.size} app(s) can read notifications: " +
                            labels(listeners),
                        level = Level.INFO,
                        deepLink = PostureDeepLink.APK_CHECK,
                    )
                },
            )
            // Private DNS.
            add(
                PostureItem(
                    title = "Private DNS",
                    detail = dns.detail,
                    level = dns.level,
                    deepLink = PostureDeepLink.NET_AUDIT,
                ),
            )
            // Installed-app surface.
            add(
                PostureItem(
                    title = "Installed apps",
                    detail = "$userApps user-installed app(s). Run APK Check for a full audit.",
                    level = Level.INFO,
                    deepLink = PostureDeepLink.APK_CHECK,
                ),
            )
        }

        return Report(
            items = items,
            accessibilityHolders = a11y,
            deviceAdminHolders = admins,
            notificationListenerHolders = listeners,
            userAppCount = userApps,
            privateDnsSummary = dns.detail,
        )
    }

    // ---- individual rootless reads -----------------------------------------

    private fun enabledAccessibilityPackages(ctx: Context): Set<String> = runCatching {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return emptySet()
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .mapNotNull { it.resolveInfo?.serviceInfo?.packageName }
            .toSet()
    }.getOrDefault(emptySet())

    private fun activeDeviceAdminPackages(ctx: Context): Set<String> = runCatching {
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java) ?: return emptySet()
        dpm.activeAdmins?.map(ComponentName::getPackageName)?.toSet() ?: emptySet()
    }.getOrDefault(emptySet())

    private fun enabledNotificationListenerPackages(ctx: Context): Set<String> = runCatching {
        NotificationManagerCompat.getEnabledListenerPackages(ctx)
    }.getOrDefault(emptySet())

    private fun userAppCount(ctx: Context): Int = runCatching {
        val pm = ctx.packageManager
        pm.getInstalledApplications(0).count { info ->
            // Non-system, non-updated-system apps only — the user-installed surface.
            (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0 &&
                info.packageName != ctx.packageName
        }
    }.getOrDefault(0)

    private data class DnsState(val detail: String, val level: Level)

    private fun privateDnsState(ctx: Context): DnsState = runCatching {
        val resolver = ctx.contentResolver
        // private_dns_mode: "off" | "opportunistic" | "hostname".
        val mode = Settings.Global.getString(resolver, "private_dns_mode")
        val host = Settings.Global.getString(resolver, "private_dns_specifier")
        when (mode) {
            "hostname" -> DnsState(
                "Strict (DNS-over-TLS) to ${host ?: "a configured host"}.",
                Level.OK,
            )
            "opportunistic" -> DnsState(
                "Automatic (opportunistic DoT when the network supports it).",
                Level.INFO,
            )
            "off" -> DnsState(
                "Off — DNS queries are sent in the clear. Consider enabling Private DNS.",
                Level.WARN,
            )
            null -> DnsState("Private DNS state is unavailable on this device.", Level.INFO)
            else -> DnsState("Mode: $mode.", Level.INFO)
        }
    }.getOrDefault(DnsState("Private DNS state could not be read.", Level.INFO))

    /**
     * Resolve a package name to a human label, falling back to the raw package
     * id. Used to make posture detail lines readable.
     */
    fun labelFor(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
