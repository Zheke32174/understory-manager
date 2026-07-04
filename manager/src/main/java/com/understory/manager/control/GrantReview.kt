package com.understory.manager.control

import android.content.Context
import android.content.pm.PackageManager
import com.understory.elevation.Elevation

/**
 * Pure(ish) logic behind the Control center's grant-review action: it reads an
 * installed app's sensitive grants (dangerous runtime permissions + high-power
 * appops such as overlay / usage-access / camera / mic / location) and models each
 * as an actionable [Grant] the UI can revoke or set-to-ignore, with undo.
 *
 * Elevation contract: the appops read uses the READ-ONLY [Elevation.readShell]
 * (`appops get <pkg>`), which returns null when unelevated or on any error. Parsing
 * is DEFENSIVE — any unexpected line is skipped, and on a null read the whole flow
 * degrades to the rootless PackageManager view of granted dangerous permissions. It
 * never throws to the UI.
 */
object GrantReview {

    /** How a single grant can be neutralised. */
    enum class Kind {
        /** A runtime permission — reversible via `pm revoke` / re-grant in Settings. */
        PERMISSION,

        /** An appops op — reversible via `appops set <pkg> <op> ignore|default`. */
        APPOP,
    }

    /**
     * One reviewable grant. [id] is the permission name (for [Kind.PERMISSION]) or
     * the appops op name (for [Kind.APPOP]). [label] is a short human name; [detail]
     * a one-line explanation of the risk. [active] is whether it is currently
     * granted/allowed (only active grants get an action button).
     */
    data class Grant(
        val kind: Kind,
        val id: String,
        val label: String,
        val detail: String,
        val active: Boolean,
    )

    /** The full review result for one package. */
    data class Result(
        val pkg: String,
        val grants: List<Grant>,
        /** True when the deep appops read was unavailable and we fell back to PM only. */
        val degraded: Boolean,
        /** True when the app holds an accessibility service (system-gated; link out). */
        val holdsAccessibility: Boolean,
        /** True when the app is an active device administrator (system-gated; link out). */
        val holdsDeviceAdmin: Boolean,
    )

    /** The high-power appops the review surfaces, with a human label + risk line. */
    private data class OpSpec(val op: String, val label: String, val detail: String)

    private val REVIEWED_OPS = listOf(
        OpSpec("SYSTEM_ALERT_WINDOW", "Draw over other apps", "Can overlay the screen — used for tap-jacking and phishing."),
        OpSpec("GET_USAGE_STATS", "Usage access", "Can see which apps you open and for how long."),
        OpSpec("CAMERA", "Camera", "Can capture photo/video."),
        OpSpec("RECORD_AUDIO", "Microphone", "Can record audio."),
        OpSpec("FINE_LOCATION", "Precise location", "Can read your exact location."),
        OpSpec("COARSE_LOCATION", "Approximate location", "Can read your approximate location."),
    )

    /** The dangerous runtime permissions the rootless fallback checks via PackageManager. */
    private data class PermSpec(val perm: String, val label: String, val detail: String)

    private val REVIEWED_PERMS = listOf(
        PermSpec(android.Manifest.permission.CAMERA, "Camera", "Can capture photo/video."),
        PermSpec(android.Manifest.permission.RECORD_AUDIO, "Microphone", "Can record audio."),
        PermSpec(android.Manifest.permission.ACCESS_FINE_LOCATION, "Precise location", "Can read your exact location."),
        PermSpec(android.Manifest.permission.ACCESS_COARSE_LOCATION, "Approximate location", "Can read your approximate location."),
        PermSpec(android.Manifest.permission.READ_CONTACTS, "Contacts", "Can read your contacts."),
        PermSpec(android.Manifest.permission.READ_SMS, "SMS", "Can read your text messages."),
        PermSpec(android.Manifest.permission.READ_CALL_LOG, "Call log", "Can read who you called and when."),
        PermSpec("android.permission.READ_EXTERNAL_STORAGE", "Storage", "Can read files in shared storage."),
    )

    /**
     * Review [pkg]. Attempts the privileged appops read first; on null degrades to the
     * rootless PackageManager permission view. Always returns a [Result]; never throws.
     *
     * @param ctx the Manager context.
     * @param pkg the installed package to review.
     */
    suspend fun review(ctx: Context, pkg: String): Result {
        val holdsA11y = holdsAccessibility(ctx, pkg)
        val holdsAdmin = holdsDeviceAdmin(ctx, pkg)

        // Privileged, read-only: `appops get <pkg>`. Null when unelevated or on error.
        val appopsRaw = runCatching { Elevation.readShell(ctx, listOf("appops", "get", pkg)) }.getOrNull()
        if (appopsRaw.isNullOrBlank()) {
            return Result(
                pkg = pkg,
                grants = rootlessPermissionGrants(ctx, pkg),
                degraded = true,
                holdsAccessibility = holdsA11y,
                holdsDeviceAdmin = holdsAdmin,
            )
        }

        val opGrants = parseAppops(appopsRaw)
        // Merge in dangerous runtime permissions the appops dump doesn't cover, so the
        // reviewer sees BOTH the overlay/usage appops and e.g. CAMERA/CONTACTS perms.
        val permGrants = rootlessPermissionGrants(ctx, pkg)
            .filterNot { p -> opGrants.any { it.label == p.label } }
        return Result(
            pkg = pkg,
            grants = (opGrants + permGrants).sortedByDescending { it.active },
            degraded = false,
            holdsAccessibility = holdsA11y,
            holdsDeviceAdmin = holdsAdmin,
        )
    }

    // ---- appops parsing (defensive) -----------------------------------------

    /**
     * Parse `appops get <pkg>` output. Each line looks roughly like:
     *   `SYSTEM_ALERT_WINDOW: allow`
     *   `CAMERA: deny; time=+1h2m3s ago`
     * We only surface ops in [REVIEWED_OPS], and treat a leading `allow` token as
     * "active". Anything we can't recognise is skipped — never guessed.
     */
    private fun parseAppops(raw: String): List<Grant> {
        val modeByOp = HashMap<String, String>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            val colon = trimmed.indexOf(':')
            if (colon <= 0) return@forEach
            val op = trimmed.substring(0, colon).trim()
            val rest = trimmed.substring(colon + 1).trim()
            // The mode is the first token after the colon (before any ';' detail).
            val mode = rest.substringBefore(';').trim().substringBefore(' ').trim()
            if (op.isNotEmpty() && mode.isNotEmpty()) modeByOp[op] = mode.lowercase()
        }
        return REVIEWED_OPS.mapNotNull { spec ->
            val mode = modeByOp[spec.op] ?: return@mapNotNull null
            Grant(
                kind = Kind.APPOP,
                id = spec.op,
                label = spec.label,
                detail = spec.detail,
                active = mode == "allow",
            )
        }
    }

    // ---- rootless fallback ---------------------------------------------------

    /** Dangerous runtime permissions currently GRANTED to [pkg], via PackageManager. */
    private fun rootlessPermissionGrants(ctx: Context, pkg: String): List<Grant> = runCatching {
        val pm = ctx.packageManager
        val info = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions ?: return@runCatching emptyList()
        val flags = info.requestedPermissionsFlags
        REVIEWED_PERMS.mapNotNull { spec ->
            val idx = requested.indexOf(spec.perm)
            if (idx < 0) return@mapNotNull null
            val granted = flags != null && idx < flags.size &&
                (flags[idx] and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            Grant(
                kind = Kind.PERMISSION,
                id = spec.perm,
                label = spec.label,
                detail = spec.detail,
                active = granted,
            )
        }
    }.getOrDefault(emptyList())

    // ---- system-gated holders (link out, never a dead button) ---------------

    private fun holdsAccessibility(ctx: Context, pkg: String): Boolean = runCatching {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? android.view.accessibility.AccessibilityManager ?: return@runCatching false
        am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
        ).any { it.resolveInfo?.serviceInfo?.packageName == pkg }
    }.getOrDefault(false)

    private fun holdsDeviceAdmin(ctx: Context, pkg: String): Boolean = runCatching {
        val dpm = ctx.getSystemService(android.app.admin.DevicePolicyManager::class.java)
            ?: return@runCatching false
        dpm.activeAdmins?.any { it.packageName == pkg } == true
    }.getOrDefault(false)
}
