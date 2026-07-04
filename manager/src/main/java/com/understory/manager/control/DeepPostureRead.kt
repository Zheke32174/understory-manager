package com.understory.manager.control

import android.content.Context
import com.understory.elevation.Elevation
import com.understory.manager.DevicePosture

/**
 * STRICTLY ADDITIVE privileged enrichment of the rootless [DevicePosture] sweep.
 *
 * Every read here goes through the READ-ONLY [Elevation.readShell]; each returns null
 * when unelevated or on any error, and every parse is defensive. A null/parse-miss on
 * any single read simply omits that row — the deep read never fails the sweep and never
 * fabricates a value. When no privileged shell is granted, [read] returns an empty list
 * and the caller shows the rootless summary unchanged.
 *
 * The extra rows it can produce (each best-effort):
 *  - doze / device-idle state (`dumpsys deviceidle get deep`),
 *  - count of apps holding the overlay appop (`appops query-op SYSTEM_ALERT_WINDOW allow`
 *    with a `dumpsys`-based fallback description),
 *  - background-network restriction list size (`cmd netpolicy list restrict-background`),
 *  - lock-screen "show all notifications" setting (`settings get secure lock_screen_...`).
 */
object DeepPostureRead {

    /** An extra posture row, reusing the sweep's own [DevicePosture.PostureItem] shape. */
    data class ExtraRow(
        val title: String,
        val detail: String,
        val level: DevicePosture.Level,
    )

    /**
     * Run the additive privileged reads. Returns empty when no shell is granted or when
     * nothing could be read — the caller then shows only the rootless sweep. Safe on a
     * background dispatcher; never throws.
     */
    suspend fun read(ctx: Context): List<ExtraRow> {
        if (!Elevation.canRunShell(ctx)) return emptyList()
        return buildList {
            dozeRow(ctx)?.let(::add)
            overlayRow(ctx)?.let(::add)
            netPolicyRow(ctx)?.let(::add)
            lockScreenRow(ctx)?.let(::add)
        }
    }

    // ---- individual additive reads (each null on miss) ----------------------

    private suspend fun dozeRow(ctx: Context): ExtraRow? {
        val out = Elevation.readShell(ctx, listOf("dumpsys", "deviceidle", "get", "deep"))
            ?.trim()?.lowercase() ?: return null
        val (detail, level) = when {
            out.contains("idle") -> "Device is in deep doze — background activity is heavily restricted." to DevicePosture.Level.OK
            out.contains("active") -> "Device is active (not dozing) — background apps can run more freely." to DevicePosture.Level.INFO
            else -> "Doze state: $out" to DevicePosture.Level.INFO
        }
        return ExtraRow("Doze / device idle", detail, level)
    }

    private suspend fun overlayRow(ctx: Context): ExtraRow? {
        val out = Elevation.readShell(
            ctx,
            listOf("appops", "query-op", "SYSTEM_ALERT_WINDOW", "allow"),
        )?.trim() ?: return null
        val count = out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.count()
        val level = if (count == 0) DevicePosture.Level.OK else DevicePosture.Level.WARN
        val detail = if (count == 0) {
            "No app currently holds the draw-over-other-apps (overlay) appop."
        } else {
            "$count app(s) hold the overlay appop — review these in Grant review."
        }
        return ExtraRow("Overlay grants", detail, level)
    }

    private suspend fun netPolicyRow(ctx: Context): ExtraRow? {
        val out = Elevation.readShell(ctx, listOf("cmd", "netpolicy", "list", "restrict-background"))
            ?.trim() ?: return null
        val entries = out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.count()
        return ExtraRow(
            "Background-network restriction",
            if (entries == 0) "No app is on the background-network restrict list."
            else "$entries entry/entries in the background-network restrict list.",
            DevicePosture.Level.INFO,
        )
    }

    private suspend fun lockScreenRow(ctx: Context): ExtraRow? {
        val out = Elevation.readShell(
            ctx,
            listOf("settings", "get", "secure", "lock_screen_allow_private_notifications"),
        )?.trim() ?: return null
        val (detail, level) = when (out) {
            "0" -> "Lock screen hides private notification content." to DevicePosture.Level.OK
            "1" -> "Lock screen shows full notification content — sensitive text may be visible when locked." to DevicePosture.Level.WARN
            "null", "" -> return null
            else -> "Lock-screen private-notifications setting: $out" to DevicePosture.Level.INFO
        }
        return ExtraRow("Lock-screen notifications", detail, level)
    }
}
