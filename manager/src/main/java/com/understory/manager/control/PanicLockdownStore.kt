package com.understory.manager.control

import android.content.Context

/**
 * Durable record of which packages the panic-lockdown flow has suspended, so the
 * set survives process death / relaunch and is ALWAYS restorable.
 *
 * This is a plain private [android.content.SharedPreferences] store (MODE_PRIVATE),
 * scoped to the Manager's own UID — it holds only package-name strings, never a
 * secret, never anything cross-UID. The invariant it protects: if the Manager
 * suspends an app, the user can always come back later and un-suspend exactly that
 * set, even after a reboot. Membership is filtered through [ControlExclusions] on
 * read so a stale entry (e.g. a package that later became the launcher) can never be
 * acted on.
 */
class PanicLockdownStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The saved locked set, exactly as stored (unfiltered). */
    fun all(): Set<String> =
        prefs.getStringSet(KEY_LOCKED, emptySet())?.toSet() ?: emptySet()

    /**
     * The locked set restricted to packages that are still installed AND not
     * hard-excluded — the safe, actionable set to un-suspend. Never returns a
     * package the action must not touch.
     */
    fun actionable(ctx: Context): Set<String> =
        all().filter { ControlExclusions.isInstalled(ctx, it) && !ControlExclusions.isExcluded(ctx, it) }
            .toSet()

    /** True when [pkg] is recorded as locked. */
    fun isLocked(pkg: String): Boolean = all().contains(pkg)

    /** Record [pkgs] as locked (union with the existing set). */
    fun addAll(pkgs: Collection<String>) {
        if (pkgs.isEmpty()) return
        val next = all().toMutableSet().apply { addAll(pkgs) }
        write(next)
    }

    /** Remove [pkgs] from the locked set (e.g. after un-suspending them). */
    fun removeAll(pkgs: Collection<String>) {
        if (pkgs.isEmpty()) return
        val next = all().toMutableSet().apply { removeAll(pkgs.toSet()) }
        write(next)
    }

    /** Clear the entire locked record. */
    fun clear() = write(emptySet())

    // ---- internals ----------------------------------------------------------

    private fun write(set: Set<String>) {
        // Store a fresh copy — SharedPreferences must not be handed a mutable set it
        // may later read back after we mutate it.
        prefs.edit().putStringSet(KEY_LOCKED, HashSet(set)).apply()
    }

    private companion object {
        const val PREFS_NAME = "control_panic_lockdown"
        const val KEY_LOCKED = "locked_packages"
    }
}
