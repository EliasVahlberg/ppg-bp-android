/*
 * Recorder UI. Observes the repository's real connection + recording state,
 * so it reflects reality whether recording was started from the button or the
 * debug ADB command interface (#22). The big colour-coded box gives at-a-glance
 * status; a recent-sessions list sits above the start/stop button.
 */

package com.polarppgbp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.polarppgbp.omron.BufferHealth
import com.polarppgbp.omron.CuffBufferHealth
import com.polarppgbp.omron.CuffClockObservation
import com.polarppgbp.omron.CuffClockRepair
import com.polarppgbp.omron.CuffStore
import com.polarppgbp.omron.OmronCuffClient
import com.polarppgbp.omron.RepairReason
import com.polarppgbp.settings.ProfileChoice
import com.polarppgbp.settings.RecordingSettings
import com.polarppgbp.settings.RotationPeriod
import com.polarppgbp.settings.ServerConfig
import com.polarppgbp.settings.ServerConfigResult
import com.polarppgbp.settings.SettingsStore
import com.polarppgbp.settings.SupportedRates
import com.polarppgbp.sync.ServerHealth
import com.polarppgbp.sync.HealthStage
import com.polarppgbp.sync.HealthReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.polarppgbp.recorder.BatteryHealth
import com.polarppgbp.recorder.CalibrationSessionController
import com.polarppgbp.recorder.SensorBattery
import com.polarppgbp.rop.SensorType
import com.polarppgbp.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import com.polarppgbp.ui.BrandBackground
import com.polarppgbp.ui.MonoReadout
import com.polarppgbp.ui.MonoReadoutLarge
import com.polarppgbp.ui.PpgBpTheme
import com.polarppgbp.ui.Spacing
import com.polarppgbp.ui.StatusColors

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainViewModel(application) as T
        }
    }

    // Registered once at Activity creation (required by the Activity Result
    // API), but re-triggerable by any checklist item -- see setupPermissionRequest.
    private var pendingPermissionResult: (() -> Unit)? = null
    private val setupPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> pendingPermissionResult?.invoke() }

    private var pendingSettingsResult: (() -> Unit)? = null
    private val setupSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { _ -> pendingSettingsResult?.invoke() }

    /** Called by FirstRunScreen checklist items -- fires the real system
     * permission dialog for exactly the permissions that item needs,
     * mirroring what a normal Android permission request looks like
     * (no bespoke bulk-request UI). */
    fun requestSetupPermissions(permissions: Array<String>, onResult: () -> Unit) {
        pendingPermissionResult = onResult
        setupPermissionLauncher.launch(permissions)
    }

    /** For checklist items granted via a Settings screen rather than a
     * runtime permission (currently: battery-optimization exemption).
     * Uses StartActivityForResult (not a plain startActivity) purely to
     * get a callback on return, so the checklist re-checks live state
     * immediately instead of waiting for the next onResume. */
    fun requestSetupSettingsIntent(intent: Intent, onResult: () -> Unit) {
        pendingSettingsResult = onResult
        setupSettingsLauncher.launch(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PpgBpTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    val startDestination = if (allSetupRequirementsMet(this)) "recorder" else "setup"

                    // A permission revoked mid-use (Android lets users revoke anytime,
                    // e.g. from system Settings) routes back to the checklist instead
                    // of the recorder/settings screens silently degrading -- #4's
                    // explicit "re-surfacing" requirement. viewModel.permissionsGranted
                    // is a mutableStateOf property, so reading it here already makes
                    // this LaunchedEffect's key recompute on change.
                    LaunchedEffect(viewModel.permissionsGranted) {
                        val current = navController.currentBackStackEntry?.destination?.route
                        if (!viewModel.permissionsGranted && current != "setup") {
                            navController.navigate("setup") { popUpTo(0) }
                        }
                    }

                    NavHost(navController = navController, startDestination = startDestination) {
                        composable("setup") {
                            // Bumped after any grant action forces FirstRunScreen to
                            // recompose and re-read live permission/battery state.
                            var refreshTrigger by remember { mutableStateOf(0) }
                            FirstRunScreen(
                                context = this@MainActivity,
                                refreshKey = refreshTrigger,
                                onRequestPermissions = { perms ->
                                    requestSetupPermissions(perms) { refreshTrigger++ }
                                },
                                onRequestSettingsIntent = { buildIntent ->
                                    requestSetupSettingsIntent(buildIntent(this@MainActivity)) { refreshTrigger++ }
                                },
                                onContinue = {
                                    viewModel.permissionsGranted = true
                                    navController.navigate("recorder") {
                                        popUpTo("setup") { inclusive = true }
                                    }
                                },
                            )
                        }
                        composable("recorder") {
                            RecorderScreen(
                                viewModel,
                                onOpenSettings = { navController.navigate("settings") },
                            )
                        }
                        composable("settings") {
                            SettingsScreen(viewModel, onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.permissionsGranted = allSetupRequirementsMet(this)
        // #17: re-check on every resume, not only first run. A permission revoked or
        // Bluetooth switched off while backgrounded must surface now, not at the next
        // failed connection attempt.
        viewModel.refreshBlocker()
        // Server config can change outside the ViewModel (debug broadcast, or a return
        // from another screen), so re-read it rather than trusting cached state.
        viewModel.refreshServerState()
        viewModel.refreshDeviceAndAdvanced()
        viewModel.refreshSessions()
    }
}


/** Coarse recorder phase derived from (recording, connectionState). onColor
 * picks readable text for each phase's (bright vs muted/dark) background. */
internal enum class Phase(val label: String, val color: Color, val onColor: Color) {
    STOPPED("Stopped", StatusColors.Stopped, Color(0xFFE4EAE7)),
    CONNECTING("Connecting…", StatusColors.Connecting, BrandBackground),
    CAPTURING("● Capturing", StatusColors.Capturing, BrandBackground),
    RECONNECTING("Lost connection — reconnecting", StatusColors.Reconnecting, BrandBackground),

    /**
     * #17: a hard blocker, shown whether or not a recording was requested. Kept
     * separate from RECONNECTING because that label promises a recovery that will
     * never happen while the radio is off or a permission is missing.
     */
    BLOCKED("Cannot record", StatusColors.Blocked, BrandBackground),
}

internal fun phaseOf(recording: Boolean, s: ConnectionState): Phase = when {
    // A blocker outranks "stopped": it is worth telling the user the app cannot
    // record even before they press start.
    s is ConnectionState.Blocked -> Phase.BLOCKED
    !recording -> Phase.STOPPED
    s is ConnectionState.Connected -> Phase.CAPTURING
    s is ConnectionState.Connecting || s is ConnectionState.Searching -> Phase.CONNECTING
    else -> Phase.RECONNECTING // recording but Idle/Failed => link dropped
}

internal fun detailOf(recording: Boolean, s: ConnectionState): String = when (s) {
    is ConnectionState.Blocked -> "${s.cause.label} — ${s.cause.remedy}"
    is ConnectionState.Connected -> s.name
    is ConnectionState.Connecting -> "device ${s.deviceId}"
    is ConnectionState.Searching -> s.message
    is ConnectionState.Failed -> s.reason
    is ConnectionState.Idle -> if (recording) "waiting to reconnect…" else "not connected"
}

@Composable
private fun RecorderScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit,
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val recording by viewModel.recording.collectAsState()
    val metrics by viewModel.metrics.collectAsState()
    val cuffStatus by viewModel.cuffStatus.collectAsState()
    val cuffBusy by viewModel.cuffBusy.collectAsState()
    val phase = phaseOf(recording, connectionState)

    // Refresh the recent-sessions list shortly after recording starts/stops.
    LaunchedEffect(recording) {
        delay(800)
        viewModel.refreshSessions()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Spacing.lg)
                // Without this, the status box + counters + cuff section + recent
                // sessions list simply run off the bottom of a smaller/lower-density
                // screen than the one this was built on, with no way to reach the
                // Start/Stop button. dp/sp are already density-independent, so the
                // fix is a scroll container, not a resize -- the same fix already
                // applied to SettingsScreen for the same reason.
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Polar BP Recorder", style = MaterialTheme.typography.headlineMedium)
                // Settings used to be locked out entirely while recording, which made
                // it impossible to reach the calibration Start/Stop session controls --
                // exactly the controls that need an active recording to mean anything
                // (a marker is stored inside the session). Settings screen guards the
                // few controls that are genuinely unsafe to change mid-recording
                // (profile / sample rate) on their own; nothing else needs blocking here.
                IconButton(onClick = onOpenSettings) {
                    Text("⚙", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }

            // Big colour-coded status box.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(phase.color)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(phase.label, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = phase.onColor)
                Spacer(Modifier.height(6.dp))
                Text(detailOf(recording, connectionState), fontSize = 15.sp, color = phase.onColor)
            }

            // #17: one-tap remediation. Sending the user hunting through OS settings is
            // the difference between a fixable state and an app that just does not work.
            // A missing permission routes to the setup checklist automatically (see the
            // permissionsGranted LaunchedEffect), so only the radio needs a button here.
            (connectionState as? ConnectionState.Blocked)
                ?.takeIf { it.cause == Blocker.BLUETOOTH_OFF }
                ?.let {
                    val ctx = LocalContext.current
                    Button(
                        onClick = {
                            runCatching {
                                ctx.startActivity(
                                    Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Turn on Bluetooth") }
                }

            // Live sample counters. A silent sensor is coloured as an error rather than
            // shown as a neutral zero: #19 produced sessions where a stationary "0" next
            // to a healthy heart rate read as "not started yet" for the whole recording.
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                @Composable
                fun counter(label: String, value: Long, sensor: SensorType) {
                    val silent = metrics.silentSensors.contains(sensor)
                    // #19: while a counter is still zero, say how far along the sensors
                    // that did start are. A couple of seconds is reconnect backoff and
                    // resolves itself; a gap that keeps widening while the others count
                    // is a real fault. Without this the two look identical.
                    val others = metrics.firstSampleElapsedMs.values.minOrNull()
                    val suffix =
                        if (value == 0L && others != null) " (others at ${others / 1000}s)" else ""
                    Text(
                        "$label $value$suffix",
                        style = MonoReadout,
                        color = if (silent) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                counter("PPG", metrics.ppgSamples, SensorType.PPG)
                counter("ACC", metrics.accSamples, SensorType.ACC)
                counter("GYRO", metrics.gyroSamples, SensorType.GYRO)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                metrics.hr?.let {
                    Text(
                        "HR $it bpm",
                        style = MonoReadout,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                // #14: a flat sensor and a sensor nobody put on look identical from
                // here, so show the battery whenever the sensor has reported one.
                SensorBattery.readout(metrics.batteryPercent, metrics.chargeStatus)?.let {
                    val health = SensorBattery.healthOf(metrics.batteryPercent)
                    Text(
                        it,
                        style = MonoReadout,
                        color = when (health) {
                            BatteryHealth.CRITICAL -> MaterialTheme.colorScheme.error
                            BatteryHealth.LOW -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.secondary
                        },
                    )
                }
            }
            SensorBattery.warning(metrics.batteryPercent, metrics.chargeStatus)?.let {
                Text(
                    "⚠ $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            metrics.streamWarning?.let {
                Text(
                    "⚠ $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(Spacing.sm))

            // Recent sessions list.
            Text(
                "Recent sessions",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.fillMaxWidth(),
            )
            if (viewModel.sessions.isEmpty()) {
                Text("None yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            } else {
                viewModel.sessions.take(6).forEach { s ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${if (s.completed) "✓" else "…"}  ${s.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (s.completed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${"%.1f".format(s.sizeBytes / 1e6)} MB",
                            style = MonoReadout.copy(fontSize = 13.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Omron cuff: reference BP readings.
            Text(
                "Omron cuff",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(cuffStatus, style = MaterialTheme.typography.bodySmall)

            // #10: the cuff overwrites past 100 readings with no error, so both the
            // headroom and any detected loss have to be visible rather than logged.
            val buffer by viewModel.cuffBuffer.collectAsState()
            buffer?.let { b ->
                Text(b.detail, style = MaterialTheme.typography.bodySmall)
                b.warning?.let { w ->
                    Text(
                        "⚠ $w",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // #18: a cuff clock that has stopped, or was never set, cannot be fixed from
            // the cuff itself -- it has no clock UI at all -- so offer the only recovery
            // route, but never write without asking.
            val haltedClock by viewModel.cuffClockHalted.collectAsState()
            haltedClock?.let { clock ->
                var confirming by remember { mutableStateOf(false) }
                val reason = CuffClockRepair.reason(clock)
                Text(
                    when (reason) {
                        RepairReason.HALTED ->
                            "⚠ Cuff clock has stopped (frozen at ${clock.cuffIso}). New readings " +
                                "cannot be matched to a recording until it is set."
                        // A new cuff arrives like this, and it reads as an ordinary
                        // date, so say plainly how far off it is.
                        else ->
                            "⚠ Cuff clock is set to ${clock.cuffIso}, which is " +
                                "${describeOffset(clock.offsetSeconds)} from this phone. " +
                                "Readings will be filed under the wrong date until it is set."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(
                    onClick = { confirming = true },
                    enabled = viewModel.permissionsGranted && !cuffBusy && !recording,
                ) { Text("Set cuff clock") }

                if (confirming) {
                    val proposed = viewModel.proposedClockIso()
                    AlertDialog(
                        onDismissRequest = { confirming = false },
                        title = { Text("Set the cuff's clock?") },
                        text = {
                            Text(
                                "This writes to the cuff — the only write this app performs.\n\n" +
                                    "Cuff now: ${clock.cuffIso}" +
                                    (if (reason == RepairReason.HALTED) " (stopped)" else "") +
                                    "\nWill write: $proposed\n\n" +
                                    "Stored readings are not touched. Their timestamps stay as " +
                                    "recorded, so nothing already synced is affected — but " +
                                    "readings taken before and after this point are on different " +
                                    "clocks, which is expected and is recorded in the clock log.\n\n" +
                                    "The cuff may need a second press of its transfer button to " +
                                    "confirm the clock is running.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                confirming = false
                                viewModel.repairCuffClock()
                            }) { Text("Write clock") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirming = false }) { Text("Cancel") }
                        },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.buttonGap),
            ) {
                OutlinedButton(
                    onClick = { viewModel.pairCuff() },
                    // Deliberately not gated on !recording. The cuff is a separate
                    // peripheral: OmronCuffClient runs its own scan and its own
                    // connectGatt, and CuffStore appends to its own file, sharing
                    // no lock with the recorder's SessionWriter. Concurrent GATT
                    // connections to distinct devices are supported on the
                    // minSdk 33 floor, and taking a cuff reading *during* a
                    // recording is the whole point of a calibration session --
                    // that is the pairing the BP model is trained on.
                    enabled = viewModel.permissionsGranted && !cuffBusy,
                    modifier = Modifier.weight(1f).heightIn(min = Spacing.minTouchTarget),
                ) { Text("Pair (hold P)") }
                OutlinedButton(
                    onClick = { viewModel.readCuff() },
                    enabled = viewModel.permissionsGranted && !cuffBusy,
                    modifier = Modifier.weight(1f).heightIn(min = Spacing.minTouchTarget),
                ) { Text("Read Cuff") }
            }

            Spacer(Modifier.height(Spacing.md))

            Button(
                onClick = { if (recording) viewModel.stopRecording() else viewModel.startRecording() },
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget),
            ) {
                Text(if (recording) "Stop Recording" else "Start Recording")
            }

            Spacer(Modifier.height(Spacing.sm))

            // Opens the same server this phone syncs to, in the system browser, so
            // checking the dashboard does not require typing the address by hand on
            // a phone that is not the one being carried around during a visit.
            if (viewModel.serverConfigured) {
                val ctx = LocalContext.current
                OutlinedButton(
                    onClick = { viewModel.openWebView(ctx) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget),
                ) { Text("View on web") }
            }
        }
    }
}

/*
 * Settings screen (#1). Styled per the app theme (Theme.kt) — dark brand
 * palette, JetBrains Mono for the Hz readouts, consistent Spacing scale.
 */
/**
 * Render a cuff-vs-phone offset the way a person would say it. Used in the clock warning,
 * where "-190512334s" tells the reader nothing.
 */
private fun describeOffset(offsetSeconds: Long?): String {
    if (offsetSeconds == null) return "an unknown amount"
    val s = kotlin.math.abs(offsetSeconds)
    val direction = if (offsetSeconds < 0) "behind" else "ahead of"
    val magnitude = when {
        s < 3600 -> "${s / 60} min"
        s < 86_400 -> "${s / 3600} h"
        s < 365L * 86_400 -> "${s / 86_400} days"
        else -> "${s / (365L * 86_400)} years"
    }
    return "$magnitude $direction"
}

/**
 * #3: the paired Polar, viewable and clearable without ADB or a reinstall. Previously the
 * binding was written automatically on connect with no way to see or change it, so moving
 * to a second sensor meant clearing app data.
 *
 * "Connect sensor" is the only way a device ID is ever written in the first place -- see
 * MainViewModel.connectSensor(). Recording refuses to start without one (RecordingService
 * logs "No device ID configured" and stops itself), so this button is not optional polish,
 * it is the missing first step of setup on a phone that has never paired before.
 */
@Composable
private fun DeviceSection(viewModel: MainViewModel) {
    var confirming by remember { mutableStateOf(false) }
    Text("Polar sensor", style = MaterialTheme.typography.titleMedium)
    val id = viewModel.pairedDeviceId
    val connectionState by viewModel.connectionState.collectAsState()
    if (id == null) {
        Text(
            "No sensor paired. Connect once and this phone will remember it.",
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        Text("Paired: $id", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { confirming = true }) { Text("Forget sensor") }
    }

    val searching = connectionState is ConnectionState.Searching ||
        connectionState is ConnectionState.Connecting
    Button(
        onClick = { viewModel.connectSensor() },
        enabled = !searching,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (searching) "Searching…" else if (id == null) "Connect sensor" else "Reconnect / find a different sensor") }
    when (val s = connectionState) {
        is ConnectionState.Searching -> Text(
            s.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        is ConnectionState.Connecting -> Text(
            "Connecting to ${s.deviceId}…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        is ConnectionState.Connected -> Text(
            "Connected: ${s.name} (${s.deviceId})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        is ConnectionState.Failed -> Text(
            "⚠ ${s.reason}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        is ConnectionState.Blocked -> Text(
            "⚠ ${s.cause.label} — ${s.cause.remedy}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        ConnectionState.Idle -> Unit
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Forget $id?") },
            text = {
                Text(
                    "Recordings already on this phone are kept — each session records which " +
                        "sensor it came from.\n\nThe next recording will need a sensor to be " +
                        "connected first.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    viewModel.forgetDevice()
                }) { Text("Forget") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * #3: session-file rotation period, previously a hardcoded 15 in PolarRepository.
 */
@Composable
private fun AdvancedSection(viewModel: MainViewModel) {
    var raw by remember(viewModel.rotationMinutes) {
        mutableStateOf(viewModel.rotationMinutes.toString())
    }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }

    Text("Advanced", style = MaterialTheme.typography.titleMedium)
    Text(
        "Recordings are split into files as they are written. Shorter periods limit how " +
            "much one interrupted file can cost; longer periods mean fewer files to sync.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        value = raw,
        onValueChange = { raw = it; error = null; saved = false },
        label = { Text("File rotation (minutes)") },
        isError = error != null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RotationPeriod.PRESETS.forEach { preset ->
            OutlinedButton(onClick = { raw = preset.toString(); error = null; saved = false }) {
                Text("$preset")
            }
        }
    }
    error?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (saved) {
        Text(
            "Saved: ${RotationPeriod.describe(viewModel.rotationMinutes)}. Applies to the next " +
                "recording.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Button(onClick = {
        val err = viewModel.setRotationMinutes(raw)
        error = err
        saved = err == null
    }) { Text("Save rotation") }
}

/**
 * Server section of the settings screen (#15).
 *
 * Before this existed the server was only settable over ADB, so a release build could
 * record but could never upload -- the debug receiver that wrote KEY_SERVER_URL is
 * stripped by R8. Validation happens here rather than in the sync worker, because a
 * malformed value only shows up there as sync silently never running.
 */
@Composable
private fun ServerSection(viewModel: MainViewModel) {
    var url by remember { mutableStateOf(viewModel.serverUrl.orEmpty()) }
    var token by remember { mutableStateOf("") }
    var revealToken by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var tokenError by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }

    Text("Server", style = MaterialTheme.typography.titleSmall)

    Text(
        if (viewModel.serverConfigured) {
            "Configured: ${viewModel.serverUrl}  token ${ServerConfig.maskToken(viewModel.serverToken)}"
        } else {
            "Not configured — recordings stay on this phone until a server is set."
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (viewModel.serverConfigured) {
            MaterialTheme.colorScheme.outline
        } else {
            MaterialTheme.colorScheme.error
        },
    )

    OutlinedTextField(
        value = url,
        onValueChange = { url = it; urlError = null; saved = false },
        label = { Text("Address") },
        placeholder = { Text("192.168.1.5:8000") },
        singleLine = true,
        isError = urlError != null,
        supportingText = urlError?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = token,
        onValueChange = { token = it; tokenError = null; saved = false },
        label = { Text("Access token") },
        placeholder = { Text(if (viewModel.serverConfigured) "leave blank to keep current" else "") },
        singleLine = true,
        isError = tokenError != null,
        supportingText = tokenError?.let { { Text(it) } },
        // Masked by default: this is a bearer token on a device that may be handed
        // around, and it should not sit in plain view or in a screenshot.
        visualTransformation = if (revealToken) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            TextButton(onClick = { revealToken = !revealToken }) {
                Text(if (revealToken) "Hide" else "Show")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )

    if (token.isNotBlank() && !ServerConfig.looksLikeServerToken(token)) {
        Text(
            "That does not look like a server token (expected 64 hex characters). " +
                "It will still be saved.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Button(
            onClick = {
                // An empty token field means "keep the stored one", so a user editing
                // only the address does not have to retype 64 characters.
                val effectiveToken = token.ifBlank { viewModel.serverToken.orEmpty() }
                when (val result = ServerConfig.validate(url, effectiveToken)) {
                    is ServerConfigResult.Valid -> {
                        viewModel.setServer(result)
                        url = result.url
                        token = ""
                        urlError = null
                        tokenError = null
                        saved = true
                    }
                    is ServerConfigResult.Invalid -> {
                        urlError = result.urlError
                        tokenError = result.tokenError
                        saved = false
                    }
                }
            },
            modifier = Modifier.weight(1f),
        ) { Text(if (saved) "Saved" else "Save server") }

        if (viewModel.serverConfigured) {
            OutlinedButton(
                onClick = {
                    viewModel.clearServer()
                    url = ""
                    token = ""
                    saved = false
                },
            ) { Text("Clear") }
        }
    }

    // #16: on-demand only. A failed check warns but never blocks saving -- the phone
    // may legitimately be configured while away from the server's network.
    OutlinedButton(
        onClick = { viewModel.checkServerHealth() },
        enabled = viewModel.serverConfigured && !viewModel.serverHealthRunning,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (viewModel.serverHealthRunning) "Testing…" else "Test connection") }

    viewModel.serverHealth?.let { report ->
        Text(
            report.detail,
            style = MaterialTheme.typography.bodySmall,
            color = if (report.ok) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

/**
 * Calibration session delimiters.
 *
 * Marks which slice of a recording was a protocol run, with a name and tags, so the
 * boundaries do not have to be reconstructed from a paper log and a wall clock later.
 * Detailed in-session annotation (posture changes, cuff readings, symptoms) is not
 * here: those belong on the recording screen where they would actually be tapped.
 */
@Composable
private fun CalibrationSection(viewModel: MainViewModel) {
    val recording by viewModel.recording.collectAsState()

    Text("Calibration session", style = MaterialTheme.typography.titleSmall)

    OutlinedTextField(
        value = viewModel.calibrationName,
        onValueChange = { viewModel.calibrationName = it },
        label = { Text("Session name") },
        singleLine = true,
        enabled = !viewModel.calibrationOpen,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = viewModel.calibrationTags,
        onValueChange = { viewModel.calibrationTags = it },
        label = { Text("Tags (comma separated)") },
        singleLine = true,
        enabled = !viewModel.calibrationOpen,
        modifier = Modifier.fillMaxWidth(),
    )

    if (viewModel.calibrationOpen) {
        val started = remember(viewModel.calibrationStartedAtMs) {
            SimpleDateFormat("HH:mm", Locale.US)
                .format(Date(viewModel.calibrationStartedAtMs))
        }
        Text(
            "Open since $started",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.buttonGap)) {
        Button(
            onClick = { viewModel.startCalibrationSession() },
            enabled = !viewModel.calibrationOpen,
            modifier = Modifier.weight(1f).heightIn(min = Spacing.minTouchTarget),
        ) { Text("Start session") }
        OutlinedButton(
            onClick = { viewModel.stopCalibrationSession() },
            enabled = viewModel.calibrationOpen,
            modifier = Modifier.weight(1f).heightIn(min = Spacing.minTouchTarget),
        ) { Text("Stop session") }
    }

    // The marker lives in the recording's notes, so without a recording there is
    // nowhere to put it. Say so before the button is pressed, not after.
    if (!recording && !viewModel.calibrationOpen) {
        Text(
            "Start a recording first. The marker is stored inside the session.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
    viewModel.calibrationStatus?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun SettingsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val settings = viewModel.settings
    var showResetConfirm by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Spacing.lg)
                // The screen gained a Server section (#15) and no longer fits on one
                // display, so it scrolls. This also replaces the weight(1f) spacer that
                // previously pinned the reset button to the bottom.
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Spacer(Modifier.weight(1f))
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
            }

            Text("Recording profile", style = MaterialTheme.typography.titleSmall)
            val recordingNow by viewModel.recording.collectAsState()
            if (recordingNow) {
                Text(
                    "Locked while recording — stop the recording to change profile or rates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            ProfileChoice.entries.forEach { choice ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RadioButton(
                        selected = settings.profileChoice == choice,
                        onClick = { viewModel.setProfileChoice(choice) },
                        enabled = !recordingNow,
                    )
                    Text(choice.label, style = MaterialTheme.typography.bodyLarge)
                }
            }

            Spacer(Modifier.height(Spacing.xs))
            Text("Per-sensor sample rate", style = MaterialTheme.typography.titleSmall)
            val customEnabled = settings.profileChoice == ProfileChoice.CUSTOM && !recordingNow
            RateDropdown(
                label = "PPG (Hz)",
                selectedHz = if (customEnabled) settings.customPpgHz else settings.toProfile().rates[SensorType.PPG],
                options = SupportedRates.PPG,
                enabled = customEnabled,
                onSelect = { viewModel.setCustomRate(SensorType.PPG, it) },
            )
            RateDropdown(
                label = "ACC (Hz)",
                selectedHz = if (customEnabled) settings.customAccHz else settings.toProfile().rates[SensorType.ACC],
                options = SupportedRates.ACC,
                enabled = customEnabled,
                onSelect = { viewModel.setCustomRate(SensorType.ACC, it) },
            )
            RateDropdown(
                label = "GYRO (Hz)",
                selectedHz = if (customEnabled) settings.customGyroHz else settings.toProfile().rates[SensorType.GYRO],
                options = SupportedRates.GYRO,
                enabled = customEnabled,
                onSelect = { viewModel.setCustomRate(SensorType.GYRO, it) },
            )
            if (!customEnabled) {
                Text(
                    "Select \"Custom\" above to choose individual rates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                "A rate not supported by the connected sensor will stop recording " +
                    "with a clear error rather than silently using a different rate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            Spacer(Modifier.height(Spacing.sm))
            CalibrationSection(viewModel)
            Spacer(Modifier.height(Spacing.sm))
            ServerSection(viewModel)
            Spacer(Modifier.height(Spacing.sm))
            DeviceSection(viewModel)
            Spacer(Modifier.height(Spacing.sm))
            AdvancedSection(viewModel)

            Spacer(Modifier.height(Spacing.lg))

            OutlinedButton(
                onClick = { showResetConfirm = true },
                enabled = !recordingNow,
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            ) { Text("Reset all settings") }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset all settings?") },
            text = { Text("This reverts the recording profile and sample rates to their defaults (Calibration). This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetSettingsToDefaults()
                    showResetConfirm = false
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun RateDropdown(
    label: String,
    selectedHz: Int?,
    options: List<Int>,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = selectedHz?.toString() ?: "—",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            textStyle = MonoReadout,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                // Read-only-but-still-informational when a fixed preset is
                // selected (Calibration/Monitor) — should stay legible, not
                // fade to Material3's default ~38% disabled alpha, which is
                // hard to read against the brand's near-black surface.
                disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledLabelColor = MaterialTheme.colorScheme.outline,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
                disabledTrailingIconColor = MaterialTheme.colorScheme.outline,
            ),
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { hz ->
                DropdownMenuItem(
                    text = { Text("$hz Hz", style = MonoReadout) },
                    onClick = { onSelect(hz); expanded = false },
                )
            }
        }
    }
}

class MainViewModel(application: android.app.Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val settingsStore = SettingsStore(context)

    val connectionState: StateFlow<ConnectionState>
    val metrics: StateFlow<LiveMetrics>
    val recording: StateFlow<Boolean>

    var permissionsGranted: Boolean by mutableStateOf(false)
    var sessions: List<PolarRepository.SessionInfo> by mutableStateOf(emptyList())
        private set

    var settings: RecordingSettings by mutableStateOf(settingsStore.get())
        private set

    private val _cuffStatus = MutableStateFlow("Cuff: idle")
    val cuffStatus: StateFlow<String> = _cuffStatus
    private val _cuffBusy = MutableStateFlow(false)
    val cuffBusy: StateFlow<Boolean> = _cuffBusy

    init {
        if (SharedRepo.repo == null) SharedRepo.repo = PolarRepository(application)
        val repo = SharedRepo.repo!!
        connectionState = repo.connectionState
        metrics = repo.metrics
        recording = repo.recording
        refreshSessions()
        sweepUnsyncedBundles()
    }

    /**
     * Re-enqueue any recorded bundle that never made it to the server.
     *
     * A session is enqueued for upload exactly once, when recording stops
     * (RecordingService "STOP"). If that WorkManager job exhausts its retries --
     * server unreachable for long enough, WiFi lost mid-upload, phone rebooted
     * while offline -- nothing re-enqueued it, and since local bundles are never
     * deleted the recording then sat on the phone indefinitely: present locally,
     * absent from the server, with nothing surfacing that anything was wrong.
     *
     * Observed live: three sessions recorded during the 2026-07-27 calibration
     * visit were still `open` server-side four days later, with only a stray ACC
     * file staged and no PPG at all. The sweep that recovers this
     * (SyncScheduler.enqueueAllUnsynced) already existed, but was wired only into
     * DebugCommandReceiver, which lives in src/debug and so does not exist in the
     * release build the phones actually run -- meaning there was no retry path at
     * all in practice.
     *
     * Runs on every app start. It is a directory listing filtered to bundles with
     * a manifest and no completion marker, and enqueue is unique-per-bundle with
     * KEEP, so re-running cannot duplicate work or disturb an upload in flight.
     */
    private fun sweepUnsyncedBundles() {
        val root = SharedRepo.repo?.bundlesRoot() ?: return
        val n = SyncScheduler.enqueueAllUnsynced(context, root)
        // Logged even when nothing needed re-queuing: the failure this exists to
        // catch was silent, so "swept, found nothing" is itself worth having in a
        // bug report, and it is one line per app start.
        Log.i("MainActivity", "startup sync sweep: re-enqueued $n unsynced bundle(s)")
    }

    fun refreshSessions() {
        sessions = SharedRepo.repo?.listRecentSessions(8) ?: emptyList()
    }

    // ---- calibration session markers ----

    private val calibrationController = CalibrationSessionController(context)

    var calibrationName: String by mutableStateOf(settingsStore.calibrationName().orEmpty())
    var calibrationTags: String by mutableStateOf(settingsStore.calibrationTags().orEmpty())
    var calibrationOpen: Boolean by mutableStateOf(settingsStore.isCalibrationOpen())
        private set
    var calibrationStartedAtMs: Long by mutableStateOf(settingsStore.calibrationStartedAt())
        private set
    var calibrationStatus: String? by mutableStateOf(null)
        private set

    fun refreshCalibrationState() {
        calibrationOpen = settingsStore.isCalibrationOpen()
        calibrationStartedAtMs = settingsStore.calibrationStartedAt()
    }

    /**
     * Both actions delegate to [CalibrationSessionController] so the Settings UI and
     * the debug broadcast harness run the same code path rather than two copies of it.
     */
    fun startCalibrationSession() {
        val outcome = calibrationController.start(calibrationName, calibrationTags)
        calibrationStatus = outcome.message
        refreshCalibrationState()
    }

    fun stopCalibrationSession() {
        val outcome = calibrationController.stop(calibrationName, calibrationTags)
        calibrationStatus = outcome.message
        refreshCalibrationState()
    }

    /** #17: re-publish (or clear) the hard blocker; called from onResume. */
    fun refreshBlocker() {
        SharedRepo.repo?.refreshBlocker()
    }

    // ---- server configuration (#15) ----

    var serverUrl: String? by mutableStateOf(settingsStore.getServerUrl())
        private set
    var serverToken: String? by mutableStateOf(settingsStore.getServerToken())
        private set
    var serverConfigured: Boolean by mutableStateOf(settingsStore.isServerConfigured())
        private set

    fun setServer(valid: ServerConfigResult.Valid) {
        settingsStore.setServer(valid)
        refreshServerState()
    }

    fun clearServer() {
        settingsStore.clearServer()
        refreshServerState()
    }

    // ------------------------------------------------------------------ #3

    var pairedDeviceId: String? by mutableStateOf(null)
        private set
    var rotationMinutes: Int by mutableStateOf(RotationPeriod.DEFAULT_MINUTES)
        private set

    fun refreshDeviceAndAdvanced() {
        pairedDeviceId = settingsStore.getDeviceId()
        rotationMinutes = settingsStore.getRotationPeriodMinutes()
    }

    /**
     * Forget the paired Polar. Recordings are untouched -- sessions record their device at
     * write time, so dropping the binding cannot orphan existing data.
     */
    fun forgetDevice() {
        settingsStore.clearDeviceId()
        refreshDeviceAndAdvanced()
    }

    /** Returns an error message, or null on success. */
    fun setRotationMinutes(raw: String): String? {
        val parsed = RotationPeriod.parse(raw) ?: return RotationPeriod.errorFor(raw)
        settingsStore.setRotationPeriodMinutes(parsed)
        refreshDeviceAndAdvanced()
        return null
    }

    /** #16: last health-check result, null until the user runs one. */
    var serverHealth: HealthReport? by mutableStateOf(null)
        private set
    var serverHealthRunning: Boolean by mutableStateOf(false)
        private set

    fun checkServerHealth() {
        if (serverHealthRunning) return
        serverHealthRunning = true
        viewModelScope.launch {
            serverHealth = try {
                ServerHealth.check(context)
            } catch (e: Exception) {
                // The check itself must never take the app down.
                HealthReport(
                    stage = HealthStage.UNREACHABLE,
                    url = serverUrl,
                    detail = "Check failed: ${e.message ?: e.javaClass.simpleName}",
                )
            }
            serverHealthRunning = false
        }
    }

    fun refreshServerState() {
        serverUrl = settingsStore.getServerUrl()
        serverToken = settingsStore.getServerToken()
        serverConfigured = settingsStore.isServerConfigured()
        // A result for the previous address would be actively misleading.
        serverHealth = null
    }

    fun startRecording() {
        // #17: check before starting so the user sees the cause immediately. The service
        // repeats this check, since it can also be started by the debug broadcast.
        SharedRepo.repo?.currentBlocker()?.let {
            SharedRepo.repo?.refreshBlocker()
            return
        }
        val intent = Intent(context, RecordingService::class.java).apply { action = "START" }
        context.startForegroundService(intent)
    }

    fun stopRecording() {
        val intent = Intent(context, RecordingService::class.java).apply { action = "STOP" }
        context.startService(intent)
    }

    // ---- settings (#1) ----
    // Write-through: every setter persists immediately via SettingsStore, then
    // refreshes the in-memory `settings` so the UI recomposes. The debug
    // broadcast receiver calls these same functions rather than duplicating
    // the persistence logic, so a debug command is always equivalent to the
    // matching user action.

    fun setProfileChoice(choice: ProfileChoice) {
        settingsStore.setProfileChoice(choice)
        settings = settingsStore.get()
    }

    fun setCustomRate(sensor: SensorType, hz: Int) {
        settingsStore.setCustomRate(sensor, hz)
        settings = settingsStore.get()
    }

    fun resetSettingsToDefaults() {
        settingsStore.resetToDefaults()
        settings = settingsStore.get()
    }

    /**
     * Opens the configured server's dashboard in the system browser. Deliberately
     * does not pass the token in the URL: the server's own login flow (POST to
     * /app/login) exists specifically so a token never lands in a URL, browser
     * history, or an access log -- see web.py's login() docstring. The person
     * opening this signs in once in that browser; the resulting session cookie is
     * good for 90 days, so this is a one-time step per browser, not per visit.
     */
    fun openWebView(context: android.content.Context) {
        val base = serverUrl?.trimEnd('/') ?: return
        val url = if (base.contains("/app")) base else "$base/app"
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }

    /**
     * The only place a device ID is ever written before a recording can start (see
     * RecordingService's "No device ID configured" refusal). Delegates to
     * PolarRepository.scanAndConnect(), which was previously dead code -- reachable from
     * nowhere in the UI, so a phone that had never paired before had no way to record at
     * all short of an ADB debug broadcast. A successful connect callback writes
     * KEY_DEVICE_ID (RecordingService's connectionState collector), so this only needs
     * to run once per sensor; reconnects on subsequent app/recording starts are automatic.
     */
    fun connectSensor() {
        SharedRepo.repo?.scanAndConnect()
    }

    /** Pair (first time, cuff held in -P- mode) then read. Reprograms the key. */
    /**
     * Set when the last cuff read found a halted RTC (#18). The Evolv has no clock UI
     * and no button path to set the time, so the user cannot fix this without the app --
     * but the write still requires their confirmation, since it is the only write this
     * app performs.
     */
    /** Ring-buffer state from the last cuff read (#10). */
    private val _cuffBuffer = MutableStateFlow<BufferHealth?>(null)
    val cuffBuffer: StateFlow<BufferHealth?> = _cuffBuffer

    private val _cuffClockHalted = MutableStateFlow<CuffClockObservation?>(null)
    val cuffClockHalted: StateFlow<CuffClockObservation?> = _cuffClockHalted

    /** What the repair would write, sampled fresh so the dialog shows the real value. */
    fun proposedClockIso(): String =
        java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        )

    /**
     * Write the cuff clock after explicit confirmation, then verify it is advancing.
     * Takes two connections, so the cuff's transfer button may need a second press.
     */
    fun repairCuffClock() {
        if (_cuffBusy.value) return
        _cuffBusy.value = true
        _cuffStatus.value = "Cuff: setting the clock…"
        viewModelScope.launch {
            try {
                val client = OmronCuffClient(context, onStatus = { _cuffStatus.value = "Cuff: $it" })
                val report = withContext(Dispatchers.IO) { client.repairCuffClock(null) }
                _cuffStatus.value = "Cuff: ${report.detail}"
                if (report.succeeded) _cuffClockHalted.value = null
            } catch (e: Exception) {
                _cuffStatus.value = "Cuff clock repair failed: ${e.message}"
            } finally {
                _cuffBusy.value = false
            }
        }
    }

    fun pairCuff() = runCuff(pair = true)

    /** Routine read using the existing bond (cuff just needs to be advertising). */
    fun readCuff() = runCuff(pair = false)

    private fun runCuff(pair: Boolean) {
        if (_cuffBusy.value) return
        _cuffBusy.value = true
        _cuffStatus.value = if (pair) "Cuff: pairing…" else "Cuff: reading…"
        viewModelScope.launch {
            try {
                // StateFlow.value is thread-safe; onStatus fires from the BLE thread.
                val client = OmronCuffClient(context, onStatus = { _cuffStatus.value = "Cuff: $it" })
                val result = withContext(Dispatchers.IO) {
                    if (pair) client.pairAndRead(null) else client.readRecords(null)
                }
                val readings = result.readings
                val store = CuffStore(File(context.filesDir, "cuff"))
                // Sampled before ingest: it is the reference point for detecting that the
                // cuff overwrote readings between syncs (#10).
                val newestStored = withContext(Dispatchers.IO) { store.newestStoredIso() }
                val res = withContext(Dispatchers.IO) { store.ingest(readings, null, result.clock) }
                val buffer = CuffBufferHealth.assess(readings, newestStored, result.unreadOnDevice)
                _cuffBuffer.value = buffer
                SyncScheduler.enqueueCuff(context)
                // #9: surface clock trouble in the UI. Silent drift is the failure mode
                // this issue exists to prevent, so it must not be log-only.
                val clockNote = when {
                    !result.clock.clockValid -> " · ⚠ ${result.clock.detail}"
                    result.clock.exceeds(CuffClockObservation.NOTABLE_DRIFT_SECONDS) ->
                        " · ⚠ ${result.clock.detail}"
                    else -> ""
                }
                val quarantineNote =
                    if (res.quarantinedCount > 0) " · ${res.quarantinedCount} quarantined" else ""
                _cuffClockHalted.value =
                    if (CuffClockRepair.needed(result.clock)) result.clock else null
                _cuffStatus.value =
                    "Cuff: ${readings.size} read · ${res.newCount} new · ${res.total} stored" +
                        quarantineNote + clockNote
            } catch (e: Exception) {
                _cuffStatus.value = "Cuff failed: ${e.message}"
            } finally {
                _cuffBusy.value = false
            }
        }
    }
}
