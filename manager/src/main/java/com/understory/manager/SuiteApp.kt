package com.understory.manager

import com.understory.security.SuiteCapability

/**
 * Static registry of the seven Understory suite apps the Manager knows how to
 * detect, describe, and launch.
 *
 * This is intentionally COMPILE-TIME knowledge, not something discovered from a
 * peer. A peer app is only ever asked "which package are you, and what version"
 * (via [com.understory.security.SuiteCapabilityRegistry]); the display name,
 * one-line purpose, category, and the capability it is *expected* to provide all
 * live here, under the Manager's own signed control. A repackaged impostor
 * cannot rename itself into the dashboard or claim a different purpose — the
 * Manager only shows what THIS table says for a package it found installed and
 * cert-verified.
 *
 * [expectedCapability] is the beacon the Manager displays as a "provides…" chip
 * ONLY after the live registry confirms the installed peer is cert-verified and
 * actually maps that capability at its attested version. It is never shown from
 * this table alone — see [SuiteDetection]. The browser app maps no peer-invocable
 * capability at v1 (its share-target is user-facing, not IPC), so its
 * [expectedCapability] is null; the dashboard shows it as an installed member
 * with no capability chip, which is honest.
 */
enum class SuiteApp(
    /** The peer's stable package id. Never suffixed — the Manager detects the base id. */
    val packageId: String,
    /** Store-facing display name. */
    val displayName: String,
    /** One-line, honest purpose (no "replaces X" claims). */
    val purpose: String,
    /** Which dashboard grouping this app belongs to. */
    val category: SuiteCategory,
    /**
     * The suite capability this app is expected to offer to peers, or null when
     * it offers no peer-invocable surface (e.g. browser at v1). Shown as a chip
     * only when the live registry independently confirms it.
     */
    val expectedCapability: SuiteCapability?,
) {
    PASSGEN(
        packageId = "com.understory.passgen",
        displayName = "Understory PassGen",
        purpose = "Password + identity vault; the suite keychain.",
        category = SuiteCategory.VAULT,
        expectedCapability = SuiteCapability.IDENTITY_VAULT,
    ),
    AEGIS(
        packageId = "com.understory.aegis",
        displayName = "Understory Aegis",
        purpose = "Stores TOTP/HOTP two-factor seeds at rest.",
        category = SuiteCategory.VAULT,
        expectedCapability = SuiteCapability.OTP_STORE,
    ),
    VAULTFOLDER(
        packageId = "com.understory.vaultfolder",
        displayName = "Understory Vault Folder",
        purpose = "Encrypted file vault for arbitrary user files.",
        category = SuiteCategory.VAULT,
        expectedCapability = SuiteCapability.FILE_VAULT,
    ),
    BACKUPS(
        packageId = "com.understory.backups",
        displayName = "Understory Backups",
        purpose = "Encrypts a file to/from the suite backup-envelope format.",
        category = SuiteCategory.VAULT,
        expectedCapability = SuiteCapability.BACKUP_ENVELOPE,
    ),
    ANTIVIRUS(
        packageId = "com.understory.antivirus",
        displayName = "Understory APK Check",
        purpose = "On-demand APK / installed-app auditor. Alongside Play Protect.",
        category = SuiteCategory.SECURITY,
        expectedCapability = SuiteCapability.APK_AUDITOR,
    ),
    FIREWALL(
        packageId = "com.understory.firewall",
        displayName = "Understory Net Audit",
        purpose = "Audits network posture (Private DNS, VPN slot, remote-admin grants).",
        category = SuiteCategory.SECURITY,
        expectedCapability = SuiteCapability.NET_POSTURE_AUDIT,
    ),
    BROWSER(
        packageId = "com.understory.browser",
        displayName = "Understory Browser",
        purpose = "Hardened browser. (No peer-facing capability yet.)",
        category = SuiteCategory.SECURITY,
        // v1 offers no peer-invocable surface; the dashboard shows no
        // capability chip for it, which is the honest state.
        expectedCapability = null,
    ),
    ;

    companion object {
        /** All apps in a stable dashboard order (category, then declaration). */
        fun all(): List<SuiteApp> = entries.sortedWith(
            compareBy({ it.category.ordinal }, { it.ordinal }),
        )

        /** Apps whose [category] is [SuiteCategory.VAULT] — the recovery center's scope. */
        fun vaultApps(): List<SuiteApp> = entries.filter { it.category == SuiteCategory.VAULT }

        /** Look up the deep-link target app for a given specialist, or null. */
        fun byPackage(packageId: String): SuiteApp? =
            entries.firstOrNull { it.packageId == packageId }
    }
}

/** Coarse grouping used by the dashboard and the recovery center. */
enum class SuiteCategory(val label: String) {
    /** Apps that hold user secrets/files the user must remember to back up. */
    VAULT("Vaults & backups"),

    /** Apps that audit or harden the device (no user vault to recover). */
    SECURITY("Security & network"),
}
