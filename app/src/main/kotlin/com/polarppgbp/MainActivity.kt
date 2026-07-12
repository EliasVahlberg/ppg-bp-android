/*
 * Recorder UI. Observes the repository's real connection + recording state,
 * so it reflects reality whether recording was started from the button or the
 * debug ADB command interface (#22). The big colour-coded box gives at-a-glance
 * status; a recent-sessions list sits above the start/stop button.
 */

package com.polarppgbp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.polarppgbp.omron.CuffStore
import com.polarppgbp.omron.OmronCuffClient
import com.polarppgbp.settings.ProfileChoice
import com.polarppgbp.settings.RecordingSettings
import com.polarppgbp.settings.SettingsStore
import com.polarppgbp.settings.SupportedRates
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainViewModel(application) as T
        }
    }

    private val requiredPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.BODY_SENSORS,
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results -> viewModel.permissionsGranted = results.values.all { it } }

    private fun checkAndMaybeRequestPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) viewModel.permissionsGranted = true
        else permissionLauncher.launch(missing.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndMaybeRequestPermissions()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = "recorder") {
                        composable("recorder") {
                            RecorderScreen(
                                viewModel,
                                onRequestPermissions = { checkAndMaybeRequestPermissions() },
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
        viewModel.permissionsGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        viewModel.refreshSessions()
    }
}

/** Coarse recorder phase derived from (recording, connectionState). */
private enum class Phase(val label: String, val color: Color) {
    STOPPED("Stopped", Color(0xFF546E7A)),
    CONNECTING("Connecting…", Color(0xFFF9A825)),
    CAPTURING("● Capturing", Color(0xFF2E7D32)),
    RECONNECTING("Lost connection — reconnecting", Color(0xFFC62828)),
}

private fun phaseOf(recording: Boolean, s: ConnectionState): Phase = when {
    !recording -> Phase.STOPPED
    s is ConnectionState.Connected -> Phase.CAPTURING
    s is ConnectionState.Connecting || s is ConnectionState.Searching -> Phase.CONNECTING
    else -> Phase.RECONNECTING // recording but Idle/Failed => link dropped
}

private fun detailOf(recording: Boolean, s: ConnectionState): String = when (s) {
    is ConnectionState.Connected -> s.name
    is ConnectionState.Connecting -> "device ${s.deviceId}"
    is ConnectionState.Searching -> s.message
    is ConnectionState.Failed -> s.reason
    is ConnectionState.Idle -> if (recording) "waiting to reconnect…" else "not connected"
}

@Composable
private fun RecorderScreen(
    viewModel: MainViewModel,
    onRequestPermissions: () -> Unit,
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Polar BP Recorder", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = onOpenSettings, enabled = !recording) {
                    Text("⚙", fontSize = 22.sp)
                }
            }

            // Big colour-coded status box.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(phase.color)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(phase.label, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text(detailOf(recording, connectionState), fontSize = 15.sp, color = Color.White)
            }

            // Live sample counters.
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("PPG ${metrics.ppgSamples}", style = MaterialTheme.typography.titleMedium)
                Text("ACC ${metrics.accSamples}", style = MaterialTheme.typography.titleMedium)
                Text("GYRO ${metrics.gyroSamples}", style = MaterialTheme.typography.titleMedium)
            }
            metrics.hr?.let { Text("HR $it bpm", style = MaterialTheme.typography.bodyMedium) }

            Spacer(Modifier.height(8.dp))

            // Recent sessions list.
            Text(
                "Recent sessions",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
            )
            if (viewModel.sessions.isEmpty()) {
                Text("None yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            } else {
                viewModel.sessions.take(6).forEach { s ->
                    Text(
                        text = "${if (s.completed) "✓" else "…"}  ${s.name}   ${"%.1f".format(s.sizeBytes / 1e6)} MB",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // Omron cuff: reference BP readings.
            Text(
                "Omron cuff",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(cuffStatus, style = MaterialTheme.typography.bodySmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { viewModel.pairCuff() },
                    enabled = viewModel.permissionsGranted && !cuffBusy && !recording,
                    modifier = Modifier.weight(1f),
                ) { Text("Pair (hold P)") }
                Button(
                    onClick = { viewModel.readCuff() },
                    enabled = viewModel.permissionsGranted && !cuffBusy && !recording,
                    modifier = Modifier.weight(1f),
                ) { Text("Read Cuff") }
            }

            Spacer(Modifier.height(4.dp))

            if (!viewModel.permissionsGranted) {
                Button(onClick = onRequestPermissions) { Text("Grant Bluetooth permissions") }
            } else {
                Button(onClick = { if (recording) viewModel.stopRecording() else viewModel.startRecording() }) {
                    Text(if (recording) "Stop Recording" else "Start Recording")
                }
            }
        }
    }
}

/*
 * Settings screen (#1). Plain Material3 for now, deliberately undecorated —
 * the visual pass (colour palette, borders, spacing) is a separate, explicit
 * follow-up once the UI/UX look-and-feel direction is settled, so this isn't
 * built twice. The functional pieces (profile choice, per-sensor rate
 * pickers, reset) are real and write-through immediately.
 */
@Composable
private fun SettingsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val settings = viewModel.settings
    var showResetConfirm by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Spacer(Modifier.weight(1f))
                Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }

            Text("Recording profile", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            ProfileChoice.entries.forEach { choice ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RadioButton(
                        selected = settings.profileChoice == choice,
                        onClick = { viewModel.setProfileChoice(choice) },
                    )
                    Text(choice.label)
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "Per-sensor sample rate",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
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

            Spacer(Modifier.weight(1f))

            Button(
                onClick = { showResetConfirm = true },
                modifier = Modifier.fillMaxWidth(),
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
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { hz ->
                DropdownMenuItem(
                    text = { Text("$hz Hz") },
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

    fun startRecording() {
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
