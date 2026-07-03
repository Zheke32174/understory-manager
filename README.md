# understory-manager

**Understory Manager** — the OPTIONAL suite control center. It gives you one place to see which Understory apps are installed, open them, confirm each is genuine (signed with the suite key), run a quick rootless security sweep, grant Shizuku once for a couple of suite-wide conveniences, and remember to export each vault app's recovery file. It is complement-not-replace: every suite app works fully standalone, and the Manager degrades honestly to exactly what is installed.

What it does:

- **Suite dashboard** — detects the seven suite apps via `PackageManager`, shows each installed app's name, one-line purpose, `versionName`, a "verified" chip (cert-pinned against the suite key), and a "provides…" capability chip when the live `SuiteCapabilityRegistry` confirms it. Not-installed apps show subtly as "not installed"; a tampered sibling (installed but cert-mismatched) is flagged in red. One-tap **Open** launches any installed app.
- **Security sweep** — a one-tap, rootless device-posture read the Manager can do itself: which apps hold accessibility / device-admin / notification-listener access, the Private DNS state, and the installed-app count. It summarizes with a per-item verdict and deep-links to a specialist app (APK Check for a full audit, Net Audit for network posture). It does not duplicate the deep scanners.
- **Elevation center** — hosts the shared `ElevationCard` so you can grant Shizuku to the Manager in one place. When granted, it offers a suite-wide convenience it can perform itself (Apply Private DNS via `Elevation.setPrivateDns`); ungranted, it degrades to a rootless Settings deep-link. Each app still manages its own Shizuku grant separately.
- **Recovery center** — lists the installed vault apps (PassGen, Aegis, Vault Folder, Backups) with a reminder to export each app's recovery file and an **Open** deep-link. The Manager cannot read a vault cross-UID — it orchestrates and reminds. It never renders or asks for any recovery secret.

Honest by design: no fabricated cross-app data, no dead controls, no secret on screen. Rootless by default; Shizuku is strictly optional.

Status: **alpha** (functional; part of the suite release-blockers list in understory-common).

## Build

Requires JDK 17+ and the Android SDK with platform 35 + build-tools 35.0.0.

```bash
# Copy local.properties.example to local.properties, set sdk.dir
gradle :manager:assembleDebug
# APK: manager/build/outputs/apk/**/manager-*-debug.apk
```

CI (GitHub Actions) builds the debug APK + runs unit tests on every push; the APK is attached as a workflow artifact. Debug builds are signed with the committed suite debug keystore so the signing-cert digest matches the suite pin (`Tamper.EXPECTED_CERT_SHA256`) — installs update-in-place over other suite-pin builds, and the Manager can read siblings' signature-gated capability providers because it shares the suite key.

## Provenance & suite

Part of the **Understory Suite** — rootless, in-bounds, local-first Android security apps (design constraints: no root by default, Shizuku optional, public APIs only, zero network).

Shared modules vendored here for a self-contained build: `common-security/` and `elevation/` (byte-identical copies) and `keystore/` (pinned suite debug keystore — cert digest is the Tamper/SuiteAttestation pin). **Do not edit shared modules in this repo.** Their canonical home is [`understory-common`](https://github.com/Zheke32174/understory-common).

Suite-level docs live in `understory-common`.

## Verify your install

Before trusting the app, confirm the APK is signed by the suite key:

```bash
apksigner verify --print-certs the-downloaded.apk | grep -i 'SHA-256'
```

The signer certificate SHA-256 digest must be exactly one of the two suite pins (single source of truth: `common-security/.../SuitePins.kt`):

- **Debug** builds: `aba68a81a0d63b5549794e586875a4f04e6dba3a6fe25d363e04eb75f46df69e`
- **Release** builds: `59a3dee7feb8262170e4dcabb3dbe7bc323abe8715ab49f5bed5133046a45c4a`

Any other digest means the APK was not signed by the suite keys — do not install it. The app also enforces these pins at runtime (Tamper self-check + SuiteAttestation cross-check of installed siblings).
