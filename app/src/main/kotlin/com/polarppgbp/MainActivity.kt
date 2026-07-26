/*
 * Recorder UI. Observes the repository's real connection + recording state,
 * so it reflects reality whether recording was started from the button or the
 * debug ADB command interface (#22). The big colour-coded box gives at-a-glance
 * status; a recent-sessions list sits above the start/stop button.
 */

package com.polarppgbp

import android.content.Intent
import android.os.Bundle
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
import com.polarppgbp.omron.CuffStore
import com.polarppgbp.omron.OmronCuffClient
import com.polarppgbp.settings.ProfileChoice
import com.polarppgbp.settings.RecordingSettings
import com.polarppgbp.settings.ServerConfig
import com.polarppgbp.settings.ServerConfigResult
import com.polarppgbp.settings.SettingsStore
import com.polarppgbp.settings.SupportedRates
import com.polarppgbp.sync.ServerHealth
import com.polarppgbp.sync.HealthStage
import com.polarppgbp.sync.HealthReport
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Polar BP Recorder", style = MaterialTheme.typography.headlineMedium)
                IconButton(onClick = onOpenSettings, enabled = !recording) {
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

            // Live sample counters.
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                Text("PPG ${metrics.ppgSamples}", style = MonoReadout, color = MaterialTheme.colorScheme.primary)
                Text("ACC ${metrics.accSamples}", style = MonoReadout, color = MaterialTheme.colorScheme.primary)
                Text("GYRO ${metrics.gyroSamples}", style = MonoReadout, color = MaterialTheme.colorScheme.primary)
            }
            metrics.hr?.let {
                Text("HR $it bpm", style = MonoReadout, color = MaterialTheme.colorScheme.secondary)
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                OutlinedButton(
                    onClick = { viewModel.pairCuff() },
                    enabled = viewModel.permissionsGranted && !cuffBusy && !recording,
                    modifier = Modifier.weight(1f),
                ) { Text("Pair (hold P)") }
                OutlinedButton(
                    onClick = { viewModel.readCuff() },
                    enabled = viewModel.permissionsGranted && !cuffBusy && !recording,
                    modifier = Modifier.weight(1f),
                ) { Text("Read Cuff") }
            }

            Spacer(Modifier.height(Spacing.xs))

            Button(
                onClick = { if (recording) viewModel.stopRecording() else viewModel.startRecording() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (recording) "Stop Recording" else "Start Recording")
            }
        }
    }
}

/*
 * Settings screen (#1). Styled per the app theme (Theme.kt) — dark brand
 * palette, JetBrains Mono for the Hz readouts, consistent Spacing scale.
 */
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
            ProfileChoice.entries.forEach { choice ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RadioButton(
                        selected = settings.profileChoice == choice,
                        onClick = { viewModel.setProfileChoice(choice) },
                    )
                    Text(choice.label, style = MaterialTheme.typography.bodyLarge)
                }
            }

            Spacer(Modifier.height(Spacing.xs))
            Text("Per-sensor sample rate", style = MaterialTheme.typography.titleSmall)
            val customEnabled = settings.profileChoice == ProfileChoice.CUSTOM
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
            ServerSection(viewModel)

            Spacer(Modifier.height(Spacing.lg))

            OutlinedButton(
                onClick = { showResetConfirm = true },
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
    }

    fun refreshSessions() {
        sessions = SharedRepo.repo?.listRecentSessions(8) ?: emptyList()
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

    /** Pair (first time, cuff held in -P- mode) then read. Reprograms the key. */
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
                val readings = withContext(Dispatchers.IO) {
                    if (pair) client.pairAndRead(null) else client.readRecords(null)
                }
                val res = withContext(Dispatchers.IO) {
                    CuffStore(File(context.filesDir, "cuff")).ingest(readings, null)
                }
                SyncScheduler.enqueueCuff(context)
                _cuffStatus.value =
                    "Cuff: ${readings.size} read · ${res.newCount} new · ${res.total} stored (uploading)"
            } catch (e: Exception) {
                _cuffStatus.value = "Cuff failed: ${e.message}"
            } finally {
                _cuffBusy.value = false
            }
        }
    }
}
