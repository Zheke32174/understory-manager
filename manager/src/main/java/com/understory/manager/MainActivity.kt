package com.understory.manager

import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.understory.elevation.Elevation
import com.understory.elevation.Outcome
import com.understory.elevation.PrivateDnsMode
import com.understory.elevation.ui.ElevationCard
import com.understory.elevation.ui.rememberElevationState
import com.understory.security.Diagnostics
import com.understory.security.DiagnosticsDump
import com.understory.security.DiagnosticsScreen
import com.understory.security.KeepAliveBackHandler
import com.understory.security.SecureButton
import com.understory.security.SecureOutlinedButton
import com.understory.security.SuiteAttestation
import com.understory.security.Tamper
import com.understory.security.TestingMode
import com.understory.security.ui.Bg
import com.understory.security.ui.components.EmptyState
import com.understory.security.ui.components.FatalScreen
import com.understory.security.ui.components.LoadingState
import com.understory.security.ui.components.SuiteCard
import com.understory.security.ui.components.SuiteSectionHeader
import com.understory.security.ui.theme.UnderstoryAccent
import com.understory.security.ui.theme.UnderstoryTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Understory Manager: the OPTIONAL suite control center.
 *
 * It detects the installed suite apps, offers one-tap launch + a verified
 * capability chip per peer, runs a rootless security sweep that summarizes +
 * routes to the deep scanners, hosts the shared elevation grant flow, and
 * reminds the user to export each vault app's recovery file. It never reads a
 * peer's data cross-UID, never renders a secret, and degrades honestly to
 * exactly what is installed. Every suite app works standalone without it.
 *
 * Accent: the Manager reuses [UnderstoryAccent.AEGIS]'s calm blue seed — there
 * is no dedicated Manager accent value in the (byte-identical, vendored)
 * common-security theme, and adding one would fork the shared source of truth.
 * The blue reads as a neutral control-center tone, distinct from the antivirus
 * teal, so the Manager still sits inside the suite family.
 */
class MainActivity : ComponentActivity() {

    /** Held for the block screen when suite attestation hard-fails. */
    private var tamperBlockReason: String? = null

    private fun initialize() {
        val debuggerAttached = Debug.isDebuggerConnected() || Debug.waitingForDebugger()
        // The Manager itself is cert-pinned (Tamper), and it verifies siblings
        // (SuiteAttestation). A tampered sibling drags the whole suite down —
        // for a control center that reads peers' signature-gated providers, that
        // is exactly the right posture.
        val tamper = Tamper.check(applicationContext)
        val attestation = SuiteAttestation.verify(applicationContext)

        val hardBlockReason = when {
            debuggerAttached -> "a debugger is attached to this build"
            !tamper.signatureMatches -> "the Manager’s own signature check failed"
            attestation.hardFail -> attestation.tamperedSiblings.joinToString(", ")
            else -> null
        }

        if (!TestingMode.ALLOW_SCREENSHOTS) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.setHideOverlayWindows(true)
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setRecentsScreenshotEnabled(false)
            }
        }
        runCatching { WindowCompat.setDecorFitsSystemWindows(window, false) }

        tamperBlockReason = hardBlockReason

        setContent {
            UnderstoryTheme(accent = UnderstoryAccent.AEGIS) {
                val reason = tamperBlockReason
                if (reason != null) {
                    TamperBlockScreen(reason = reason, onClose = { finishAndRemoveTask() })
                } else {
                    ManagerApp()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DiagnosticsDump.activateIfEng(this)
        Diagnostics.log("manager.MainActivity", "onCreate (savedInstanceState=${savedInstanceState != null})")
        super.onCreate(savedInstanceState)
        try {
            initialize()
        } catch (t: Throwable) {
            Diagnostics.error("manager.MainActivity", "onCreate threw: ${t.javaClass.simpleName}: ${t.message}")
            setContent {
                UnderstoryTheme(accent = UnderstoryAccent.AEGIS) {
                    FatalScreen(
                        title = getString(R.string.crash_title),
                        reason = getString(R.string.crash_reason),
                        details = t.toString(),
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Diagnostics.log("manager.MainActivity", "onResume")
        // Re-verify the suite on resume: a sibling could have been replaced with
        // a tampered build while we were backgrounded.
        Tamper.invalidate()
        val tamper = Tamper.check(applicationContext)
        val attestation = SuiteAttestation.verify(applicationContext)
        if (!tamper.signatureMatches || attestation.hardFail) {
            Diagnostics.error("manager.MainActivity", "attestation hardFail on resume — finishing")
            finishAndRemoveTask()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Diagnostics.log("manager.MainActivity", "onDestroy")
    }
}

/** Full-screen honest attestation explanation — never a bare silent finish. */
@Composable
private fun TamperBlockScreen(reason: String, onClose: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        FatalScreen(
            title = stringResource(R.string.tamper_block_title),
            reason = stringResource(R.string.tamper_block_reason, reason),
            modifier = Modifier.weight(1f),
        )
        SecureButton(
            onClick = onClose,
            modifier = Modifier
                .fillMaxWidth()
                .padding(UnderstoryTheme.spacing.lg),
        ) {
            Text(stringResource(R.string.tamper_block_close))
        }
    }
}

/** Top-level destinations shown in the NavigationBar. */
private enum class Dest(
    val labelRes: Int,
    val cdRes: Int,
    val titleRes: Int,
    val icon: ImageVector,
) {
    Suite(R.string.nav_suite, R.string.cd_nav_suite, R.string.title_suite, Icons.Filled.Apps),
    Sweep(R.string.nav_sweep, R.string.cd_nav_sweep, R.string.title_sweep, Icons.Filled.HealthAndSafety),
    Tools(R.string.nav_tools, R.string.cd_nav_tools, R.string.title_tools, Icons.Filled.Settings),
}

/**
 * Shared state across configuration change. Snapshots are re-derivable (a re-run
 * of the detection/sweep reproduces them), so process death only costs a
 * recompute.
 */
class ManagerViewModel : ViewModel() {
    var suite by mutableStateOf<SuiteDetection.Snapshot?>(null)
    var suiteLoading by mutableStateOf(false)
    var sweep by mutableStateOf<DevicePosture.Report?>(null)
    var sweepRunning by mutableStateOf(false)
    var elevRefreshKey by mutableStateOf(0)
    var dnsOutcome by mutableStateOf<String?>(null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManagerApp() {
    var destName by rememberSaveable { mutableStateOf(Dest.Suite.name) }
    val dest = remember(destName) { Dest.valueOf(destName) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    val isEng = BuildConfig.FLAVOR == "eng"

    KeepAliveBackHandler("manager.App")

    if (isEng && showDiagnostics) {
        BackHandler { showDiagnostics = false }
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            DiagnosticsScreen(onBack = { showDiagnostics = false })
        }
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(dest.titleRes), style = MaterialTheme.typography.titleLarge) },
                actions = {
                    if (isEng) {
                        IconButton(onClick = { showDiagnostics = true }) {
                            Icon(
                                imageVector = Icons.Filled.BugReport,
                                contentDescription = stringResource(R.string.cd_diagnostics),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                for (d in Dest.entries) {
                    val selected = d == dest
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (d != dest) {
                                Diagnostics.log("manager.App", "nav ${dest.name} → ${d.name}")
                                destName = d.name
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = d.icon,
                                contentDescription = stringResource(d.cdRes),
                            )
                        },
                        label = { Text(stringResource(d.labelRes)) },
                    )
                }
            }
        },
    ) { pad ->
        when (dest) {
            Dest.Suite -> SuiteSection(pad)
            Dest.Sweep -> SweepSection(pad)
            Dest.Tools -> ToolsSection(pad)
        }
    }
}

// ------------------------------------------------------------------
// Suite — the dashboard of installed suite apps.
// ------------------------------------------------------------------

@Composable
private fun SuiteSection(pad: PaddingValues) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm: ManagerViewModel = viewModel()

    // Load once, and refresh whenever a package is added/removed while open.
    LaunchedEffect(Unit) {
        if (vm.suite == null && !vm.suiteLoading) {
            vm.suiteLoading = true
            vm.suite = withContext(Bg.io) { SuiteDetection.snapshot(ctx) }
            vm.suiteLoading = false
        }
    }
    OnSuiteChange {
        scope.launch {
            vm.suite = withContext(Bg.io) { SuiteDetection.snapshot(ctx) }
        }
    }

    val snap = vm.suite
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(pad)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = UnderstoryTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(UnderstoryTheme.spacing.sm),
    ) {
        Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
        if (snap == null) {
            Box(Modifier.fillMaxWidth().height(240.dp)) { LoadingState() }
        } else {
            ManagerInfoCard(
                "The Manager is optional — every app below works on its own. It gives you " +
                    "one place to open them, confirm each is genuine, and remember to back up " +
                    "your vaults. ${snap.verifiedCount} of ${snap.installedCount} installed " +
                    "app(s) verified against the suite key.",
            )

            // Empty-suite state: only the Manager itself is present.
            if (snap.installedCount == 0) {
                Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
                SingleAppEmptyState()
            }

            for (cat in SuiteCategory.entries) {
                val group = snap.forCategory(cat)
                if (group.isEmpty()) continue
                SuiteSectionHeader(cat.label)
                group.forEach { status ->
                    SuiteAppCard(
                        status = status,
                        onOpen = { ManagerDeepLinks.launch(ctx, status.app) },
                    )
                }
            }
        }
        Spacer(Modifier.height(UnderstoryTheme.spacing.xxl))
    }
}

// ------------------------------------------------------------------
// Sweep — one-tap rootless posture read.
// ------------------------------------------------------------------

@Composable
private fun SweepSection(pad: PaddingValues) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm: ManagerViewModel = viewModel()

    val runSweep: () -> Unit = {
        if (!vm.sweepRunning) {
            vm.sweepRunning = true
            scope.launch {
                vm.sweep = withContext(Bg.io) { DevicePosture.sweep(ctx) }
                vm.sweepRunning = false
            }
        }
    }

    val report = vm.sweep
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(pad)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = UnderstoryTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(UnderstoryTheme.spacing.sm),
    ) {
        Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
        when {
            vm.sweepRunning && report == null ->
                Box(Modifier.fillMaxWidth().height(240.dp)) {
                    LoadingState(label = "Reading device posture…")
                }
            report == null ->
                Box(Modifier.fillMaxWidth().height(400.dp)) {
                    EmptyState(
                        title = "Run a security sweep",
                        body = "A quick, rootless read of what the Manager itself can see: which apps " +
                            "hold accessibility, device-admin, or notification-listener access, your " +
                            "Private DNS state, and how many apps are installed. It summarizes and " +
                            "routes you to a specialist app for a deeper audit — it does not duplicate " +
                            "the deep scanners.",
                        icon = Icons.Filled.HealthAndSafety,
                        action = {
                            SecureButton(onClick = runSweep, modifier = Modifier.fillMaxWidth()) {
                                Text("Run security sweep")
                            }
                        },
                    )
                }
            else -> {
                SweepSummaryBanner(report)
                Spacer(Modifier.height(UnderstoryTheme.spacing.xs))
                report.items.forEach { item ->
                    val target = item.deepLink
                    val canOpen = target != null &&
                        ManagerDeepLinks.canOpenPostureTarget(ctx, target)
                    PostureItemCard(
                        item = item,
                        onDeepLink = if (canOpen) {
                            { ManagerDeepLinks.openPostureTarget(ctx, target!!) }
                        } else null,
                        deepLinkLabel = if (canOpen) deepLinkLabel(target!!) else null,
                    )
                }
                SecureButton(
                    onClick = runSweep,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = UnderstoryTheme.spacing.sm),
                ) {
                    Text("Re-run sweep")
                }
            }
        }
        Spacer(Modifier.height(UnderstoryTheme.spacing.xxl))
    }
}

/** Human label for the deep-link button on a posture item. */
private fun deepLinkLabel(target: DevicePosture.PostureDeepLink): String = when (target) {
    DevicePosture.PostureDeepLink.APK_CHECK -> "Open APK Check"
    DevicePosture.PostureDeepLink.NET_AUDIT -> "Open Net Audit"
    DevicePosture.PostureDeepLink.ACCESSIBILITY_SETTINGS -> "Open accessibility settings"
}

// ------------------------------------------------------------------
// Tools — Elevation Center + Recovery Center.
// ------------------------------------------------------------------

@Composable
private fun ToolsSection(pad: PaddingValues) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm: ManagerViewModel = viewModel()

    // Ensure we have a suite snapshot for the recovery center (reuse Suite's).
    LaunchedEffect(Unit) {
        if (vm.suite == null && !vm.suiteLoading) {
            vm.suiteLoading = true
            vm.suite = withContext(Bg.io) { SuiteDetection.snapshot(ctx) }
            vm.suiteLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(pad)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = UnderstoryTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(UnderstoryTheme.spacing.sm),
    ) {
        Spacer(Modifier.height(UnderstoryTheme.spacing.sm))

        // ---- Elevation Center ----
        SuiteSectionHeader("Elevation center")
        ManagerInfoCard(
            "The suite is rootless by default. Optionally grant Shizuku to the Manager here to " +
                "unlock a couple of suite-wide conveniences it can do itself. Each app still " +
                "manages its own Shizuku grant separately — granting it to the Manager does not " +
                "grant it to any other app.",
        )
        ElevationCard(
            unlocks = listOf(
                "Apply Private DNS across the device (Automatic or Off)",
                "Suite-wide conveniences the Manager can perform itself",
            ),
            rootlessFallback = "open the system app list, or set Private DNS in Settings yourself",
            onRootlessFallback = { ManagerDeepLinks.openApplicationSettings(ctx) },
        )
        ElevatedConveniences(
            onResult = { vm.dnsOutcome = it },
        )
        vm.dnsOutcome?.let { msg ->
            ManagerInfoCard(msg)
        }

        Spacer(Modifier.height(UnderstoryTheme.spacing.md))

        // ---- Recovery Center ----
        SuiteSectionHeader("Recovery center")
        ManagerInfoCard(
            "If you lose or reset this device, each vault app is only recoverable from ITS OWN " +
                "recovery file — not from the Manager. The Manager cannot read those vaults, so it " +
                "reminds you and opens each app; it never shows or asks for a recovery secret.",
        )
        val snap = vm.suite
        if (snap == null) {
            Box(Modifier.fillMaxWidth().height(160.dp)) { LoadingState() }
        } else {
            val vaultStatuses = snap.forCategory(SuiteCategory.VAULT)
            val anyInstalled = vaultStatuses.any { it.installed }
            if (!anyInstalled) {
                ManagerInfoCard("No vault apps are installed yet, so there is nothing to back up here.")
            }
            vaultStatuses.forEach { status ->
                RecoveryAppCard(
                    status = status,
                    onOpen = { ManagerDeepLinks.launch(ctx, status.app) },
                )
            }
        }
        Spacer(Modifier.height(UnderstoryTheme.spacing.xxl))
    }
}

/**
 * The one suite-wide elevated action the Manager performs ITSELF when Shizuku is
 * granted: applying Private DNS via [Elevation.setPrivateDns]. Degrades to the
 * rootless deep-link (handled by the [ElevationCard] fallback) when not granted —
 * this card renders its action buttons only when a shell tier can run them.
 */
@Composable
private fun ElevatedConveniences(onResult: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Only offer the elevated action when a tier is actually granted; otherwise
    // the ElevationCard above already shows the rootless path — no dead control.
    // Reads the shared elevation state so this appears/disappears reactively as
    // the user completes (or revokes) the grant.
    val elevation = rememberElevationState()
    val canRun = elevation.isElevated && Elevation.canWriteSecureSettings(ctx)
    if (!canRun) return

    SuiteCard {
        Text(
            "Apply Private DNS",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Set the device Private DNS mode using the elevation you granted the Manager.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = UnderstoryTheme.spacing.xs),
        )
        Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
        val apply: (PrivateDnsMode, String) -> Unit = { mode, label ->
            scope.launch {
                val outcome = withContext(Bg.io) { Elevation.setPrivateDns(ctx, mode) }
                onResult(
                    when (outcome) {
                        is Outcome.Success -> "Private DNS set to $label."
                        is Outcome.Unsupported -> "Couldn’t apply: ${outcome.reason}"
                        is Outcome.Failed -> "Couldn’t apply: ${outcome.message}"
                    },
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(UnderstoryTheme.spacing.sm),
        ) {
            SecureOutlinedButton(onClick = { apply(PrivateDnsMode.AUTOMATIC, "Automatic") }) {
                Text("Automatic")
            }
            SecureOutlinedButton(onClick = { apply(PrivateDnsMode.OFF, "Off") }) {
                Text("Off")
            }
        }
    }
}

/**
 * Refresh the suite dashboard live while open: a context-registered receiver for
 * PACKAGE_ADDED / PACKAGE_REMOVED / PACKAGE_REPLACED that lives only while this
 * composable is STARTED (the only rootless place such a receiver fires). Not
 * background real-time — purely a "keep the list fresh while you look at it".
 */
@Composable
private fun OnSuiteChange(onChange: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                val pkg = intent?.data?.schemeSpecificPart ?: return
                // Only react to suite packages — ignore unrelated churn.
                if (SuiteApp.byPackage(pkg) != null) onChange()
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
            addAction(android.content.Intent.ACTION_PACKAGE_REMOVED)
            addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME ->
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            ctx.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
                        } else {
                            @Suppress("UnspecifiedRegisterReceiverFlag")
                            ctx.registerReceiver(receiver, filter)
                        }
                    }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE ->
                    runCatching { ctx.unregisterReceiver(receiver) }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { ctx.unregisterReceiver(receiver) }
        }
    }
}
