/*
 * Wrapper around the Polar BLE SDK that streams decoded samples into a
 * SessionWriter (raw ROP bundle on disk). Replaces the earlier Room/SQLite
 * persistence — the phone now stages ROP and the server runs the shared
 * converter (Option A).
 *
 * Lifecycle:
 *   1. construct PolarRepository(context)
 *   2. startSession(profile)            — opens a bundle dir + SessionWriter
 *   3. connect(deviceId) / scanAndConnect()
 *   4. SDK frames -> packed ROP records -> SessionWriter.appendRecords
 *   5. stopSession()                    — finalises manifest.json, returns dir
 *
 * The Polar Android SDK already exposes a per-sample timeStamp (device epoch
 * 2000-01-01, ns), so we write sample.timeStamp directly — no interpolation
 * needed. epoch_offset_ns is captured from the first sample of the session.
 */

package com.polarppgbp

import android.content.Context
import android.util.Log
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.PolarBleApi.PolarBleSdkFeature
import com.polar.sdk.api.errors.PolarInvalidArgument
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.androidcommunications.api.ble.model.gatt.client.ChargeState
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHealthThermometerData
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarPpgData
import com.polar.sdk.api.model.PolarAccelerometerData
import com.polar.sdk.api.model.PolarGyroData
import com.polar.sdk.api.model.PolarSensorSetting
import com.polar.sdk.api.model.PolarSensorSetting.SettingType
import com.polarppgbp.recorder.Profile
import com.polarppgbp.recorder.ChargeStatus
import com.polarppgbp.recorder.SensorBattery
import com.polarppgbp.recorder.StreamWatchdog
import com.polarppgbp.recorder.WatchdogVerdict
import com.polarppgbp.settings.RotationPeriod
import com.polarppgbp.recorder.SessionWriter
import com.polarppgbp.rop.SensorType
import com.polarppgbp.rop.packAcc
import com.polarppgbp.rop.packGyro
import com.polarppgbp.rop.packPpg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val TAG = "PolarRepo"

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data class Searching(val message: String = "Searching for Polar device…") : ConnectionState
    data class Connecting(val deviceId: String) : ConnectionState
    data class Connected(val deviceId: String, val name: String) : ConnectionState
    data class Failed(val reason: String) : ConnectionState

    /**
     * A hard condition that no amount of retrying will fix (#17). Distinct from
     * [Failed] precisely so the reconnect loop and the UI can tell "try again" apart
     * from "this will never work until the user does something".
     */
    data class Blocked(val cause: Blocker) : ConnectionState
}

data class LiveMetrics(
    val hr: Int? = null,
    val ppgSamples: Long = 0,
    val accSamples: Long = 0,
    val gyroSamples: Long = 0,
    /**
     * #19: sensors the watchdog found silent. Non-empty means this recording is
     * missing data for those sensors right now, whatever the connection state says.
     */
    val silentSensors: Set<SensorType> = emptySet(),
    /** Human-readable version of the above, null when nothing is wrong. */
    val streamWarning: String? = null,
    /** #14: sensor battery percent, null until the sensor reports it. */
    val batteryPercent: Int? = null,
    /** #14: sensor charge state, UNKNOWN until reported. */
    val chargeStatus: ChargeStatus = ChargeStatus.UNKNOWN,
)

/** Thrown by chooseSetting() when a requested sample rate isn't offered by
 * the connected device (#1: configuration errors should fail loudly, not
 * silently fall back to a different rate). */
/** How often the #19 watchdog samples the counters. Cheap: it reads a StateFlow. */
private const val WATCHDOG_POLL_MS = 1_000L

class UnsupportedRateException(val requestedHz: Int, val availableHz: Set<Int>) :
    Exception("requested ${requestedHz}Hz not in $availableHz")

class PolarRepository(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob())

    /** App-private storage root for session bundles (no runtime permission needed). */
    private val sessionsRoot: File =
        (appContext.getExternalFilesDir(null) ?: appContext.filesDir).resolve("sessions")

    // ---- session state ----
    @Volatile private var session: SessionWriter? = null
    @Volatile private var lastBundleDir: File? = null
    @Volatile private var segmentId: Int = 0
    @Volatile private var deviceAddress: String? = null
    @Volatile private var deviceName: String? = null
    private var activeProfile: Profile = Profile.CALIBRATION

    private val sdkFeatures = setOf(
        PolarBleSdkFeature.FEATURE_HR,
        PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
        PolarBleSdkFeature.FEATURE_POLAR_SDK_MODE,
        PolarBleSdkFeature.FEATURE_BATTERY_INFO,
    )

    private val api: PolarBleApi by lazy {
        PolarBleApiDefaultImpl.defaultImplementation(appContext, sdkFeatures)
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _metrics = MutableStateFlow(LiveMetrics())
    val metrics: StateFlow<LiveMetrics> = _metrics.asStateFlow()

    private val _recording = MutableStateFlow(false)
    /** True between startSession() and stopSession(), regardless of how it was triggered. */
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private var hrJob: Job? = null
    private var ppgJob: Job? = null
    private var accJob: Job? = null
    private var gyroJob: Job? = null
    private var watchdogJob: Job? = null
    private var searchJob: Job? = null

    init {
        api.setApiCallback(object : PolarBleApiCallback() {
            override fun blePowerStateChanged(powered: Boolean) {
                Log.i(TAG, "BLE power: $powered")
                // #17: this used to log and discard, leaving the UI to guess. Radio off
                // is a blocker, not a dropped link.
                if (!powered) {
                    _connectionState.value = ConnectionState.Blocked(Blocker.BLUETOOTH_OFF)
                } else if (_connectionState.value.let {
                        it is ConnectionState.Blocked && it.cause == Blocker.BLUETOOTH_OFF
                    }
                ) {
                    // Radio came back. Drop to Idle so the reconnect loop may resume.
                    _connectionState.value = ConnectionState.Idle
                }
            }

            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                _connectionState.value = ConnectionState.Connecting(polarDeviceInfo.deviceId)
            }

            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.i(TAG, "Connected to ${polarDeviceInfo.deviceId} (${polarDeviceInfo.name})")
                deviceAddress = polarDeviceInfo.address
                deviceName = polarDeviceInfo.name
                _connectionState.value = ConnectionState.Connected(
                    deviceId = polarDeviceInfo.deviceId,
                    name = polarDeviceInfo.name,
                )
                // New segment for this (re)connection.
                segmentId += 1
                session?.setDevice(deviceName, deviceAddress)
                session?.appendSegment(
                    event = "connect",
                    segmentId = segmentId,
                    tsEpochSec = nowSec(),
                    reason = if (segmentId == 1) "initial" else "reconnect",
                    device = deviceAddress,
                )
                startHrStream(polarDeviceInfo.deviceId)
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.i(TAG, "Disconnected from ${polarDeviceInfo.deviceId}")
                hrJob?.cancel(); hrJob = null
                ppgJob?.cancel(); ppgJob = null
                accJob?.cancel(); accJob = null
                gyroJob?.cancel(); gyroJob = null
                watchdogJob?.cancel(); watchdogJob = null
                session?.appendSegment(
                    event = "disconnect",
                    segmentId = segmentId,
                    tsEpochSec = nowSec(),
                    reason = "disconnect",
                    device = deviceAddress,
                )
                _connectionState.value = ConnectionState.Idle
                // #14: battery is cleared with hr. A percentage from before the link
                // dropped describes a sensor we are no longer talking to, and a stale
                // "BAT 80%" beside a disconnected sensor is worse than no reading.
                _metrics.value = _metrics.value.copy(
                    hr = null,
                    batteryPercent = null,
                    chargeStatus = ChargeStatus.UNKNOWN,
                )
            }

            override fun bleSdkFeatureReady(identifier: String, feature: PolarBleSdkFeature) {
                if (feature == PolarBleSdkFeature.FEATURE_POLAR_SDK_MODE) {
                    scope.launch {
                        runCatching { api.enableSDKMode(identifier) }
                            .onFailure { e -> Log.e(TAG, "Failed to enable SDK mode", e) }
                    }
                }
                if (feature == PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING) {
                    val rates = activeProfile.rates
                    if (rates.containsKey(SensorType.PPG)) startPpgStream(identifier)
                    if (rates.containsKey(SensorType.ACC)) startAccStream(identifier)
                    if (rates.containsKey(SensorType.GYRO)) startGyroStream(identifier)
                    startStreamWatchdog(identifier, rates.keys)
                }
            }

            // #14: FEATURE_BATTERY_INFO was already enabled but these callbacks were
            // never overridden, so the reading was fetched and thrown away.
            override fun batteryLevelReceived(identifier: String, level: Int) {
                Log.i(TAG, "Battery: $level%")
                _metrics.value = _metrics.value.copy(batteryPercent = level)
            }

            override fun batteryChargingStatusReceived(
                identifier: String,
                chargingStatus: ChargeState,
            ) {
                val mapped = when (chargingStatus) {
                    ChargeState.CHARGING -> ChargeStatus.CHARGING
                    ChargeState.DISCHARGING_ACTIVE,
                    ChargeState.DISCHARGING_INACTIVE,
                    -> ChargeStatus.DISCHARGING
                    ChargeState.UNKNOWN -> ChargeStatus.UNKNOWN
                }
                // The SDK distinguishes active from inactive discharging; both are
                // collapsed to DISCHARGING because the difference is not documented
                // clearly enough to show a user a claim about it.
                Log.i(TAG, "Charge state: $chargingStatus -> $mapped")
                _metrics.value = _metrics.value.copy(chargeStatus = mapped)
            }

            override fun disInformationReceived(identifier: String, disInfo: DisInfo) {}
            override fun htsNotificationReceived(identifier: String, data: PolarHealthThermometerData) {}
        })
    }

    // --------------------------------------------------------- session API

    /** Open a new session bundle. Call before connecting. */
    /**
     * @param rotationPeriodMinutes how often to start a new file; user-configurable (#3).
     */
    fun startSession(profile: Profile, rotationPeriodMinutes: Int = RotationPeriod.DEFAULT_MINUTES) {
        activeProfile = profile
        segmentId = 0
        // Sample counters restart per session, but battery belongs to the sensor, not
        // to the session. The sensor reports it once shortly after connecting, so a
        // blanket reset here would blank the readout for any session started while
        // already connected (stop then start, with no disconnect in between) and it
        // would never come back.
        _metrics.value = LiveMetrics(
            batteryPercent = _metrics.value.batteryPercent,
            chargeStatus = _metrics.value.chargeStatus,
        )
        val uuid = UUID.randomUUID()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dirName = "${profile.name}_${stamp}_${uuid.toString().take(8)}"
        val dir = File(sessionsRoot, dirName)
        session = SessionWriter(
            sessionDir = dir,
            sessionUuid = uuid,
            profile = profile,
            deviceName = deviceName,
            deviceAddress = deviceAddress,
            startedAtEpochSec = nowSec(),
            rotationPeriodMinutes = rotationPeriodMinutes,
        )
        _recording.value = true
        Log.i(TAG, "Started session ${dir.absolutePath}")
    }

    /** Finalise the current session and return its bundle directory (for sync). */
    fun stopSession(): File? {
        val s = session ?: return null
        s.markEnded(nowSec())
        s.close()
        session = null
        _recording.value = false
        lastBundleDir = s.sessionDir
        Log.i(TAG, "Stopped session ${s.sessionDir.absolutePath}")
        return s.sessionDir
    }

    /** Absolute path of the active session bundle dir, or the last finalised one. */
    fun currentSessionDir(): String? = (session?.sessionDir ?: lastBundleDir)?.absolutePath

    /** Root directory holding all session bundles. */
    fun bundlesRoot(): File = sessionsRoot

    /** Lightweight metadata for a recorded session bundle (for the UI list). */
    data class SessionInfo(
        val name: String,
        val sizeBytes: Long,
        val modifiedEpochMs: Long,
        val completed: Boolean,
    )

    /** Most-recent session bundles on disk, newest first. */
    fun listRecentSessions(limit: Int = 8): List<SessionInfo> {
        val dirs = sessionsRoot.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.sortedByDescending { it.lastModified() }.take(limit).map { d ->
            val size = d.listFiles()?.sumOf { it.length() } ?: 0L
            SessionInfo(
                name = d.name,
                sizeBytes = size,
                modifiedEpochMs = d.lastModified(),
                completed = File(d, "manifest.json").exists(),
            )
        }
    }

    /** fsync open ROP writers — called from the service heartbeat. */
    fun syncSession() {
        runCatching { session?.sync() }
    }

    /**
     * Append a note to the active recording, returning false when there is no
     * recording to append to.
     *
     * Returning a boolean rather than throwing or silently no-op'ing: the caller
     * (the calibration marker UI) has to be able to tell the user that a marker went
     * nowhere. A marker the user believes was recorded, but wasn't, is worse than a
     * refused one, because the paper log and the data will disagree with no sign of it.
     */
    fun appendNote(note: String): Boolean {
        val writer = session ?: return false
        return runCatching { writer.appendNote(nowSec(), note); true }.getOrDefault(false)
    }

    // --------------------------------------------------------- connection API

    /** Current hard blocker, or null when the link is permitted to work. */
    fun currentBlocker(): Blocker? = BleReadiness.current(appContext)

    /**
     * Publish the current blocker (or clear a stale one). Called on resume so a
     * permission revoked while the app was backgrounded surfaces immediately rather
     * than at the next connection attempt.
     */
    fun refreshBlocker() {
        val blocker = currentBlocker()
        val state = _connectionState.value
        if (blocker != null) {
            if (state !is ConnectionState.Connected) {
                _connectionState.value = ConnectionState.Blocked(blocker)
            }
        } else if (state is ConnectionState.Blocked) {
            _connectionState.value = ConnectionState.Idle
        }
    }

    fun connect(deviceId: String) {
        currentBlocker()?.let {
            Log.w(TAG, "Not connecting: ${it.label}")
            _connectionState.value = ConnectionState.Blocked(it)
            return
        }
        try {
            _connectionState.value = ConnectionState.Connecting(deviceId)
            api.connectToDevice(deviceId)
        } catch (e: PolarInvalidArgument) {
            _connectionState.value = ConnectionState.Failed("Bad device id: ${e.message}")
        }
    }

    fun scanAndConnect() {
        currentBlocker()?.let {
            Log.w(TAG, "Not scanning: ${it.label}")
            _connectionState.value = ConnectionState.Blocked(it)
            return
        }
        searchJob?.cancel()
        _connectionState.value = ConnectionState.Searching()
        searchJob = scope.launch {
            api.searchForDevice()
                .catch { e -> _connectionState.value = ConnectionState.Failed("Scan failed: ${e.message}") }
                .collect { info ->
                    if (_connectionState.value is ConnectionState.Searching) {
                        searchJob?.cancel()
                        connect(info.deviceId)
                    }
                }
        }
    }

    fun disconnect() {
        when (val s = _connectionState.value) {
            is ConnectionState.Connected -> runCatching { api.disconnectFromDevice(s.deviceId) }
            is ConnectionState.Connecting -> runCatching { api.disconnectFromDevice(s.deviceId) }
            else -> Unit
        }
        searchJob?.cancel(); searchJob = null
        hrJob?.cancel(); hrJob = null
        ppgJob?.cancel(); ppgJob = null
        accJob?.cancel(); accJob = null
        gyroJob?.cancel(); gyroJob = null
        _connectionState.value = ConnectionState.Idle
    }

    fun shutdown() {
        disconnect()
        stopSession()
        runCatching { api.shutDown() }
    }

    // --------------------------------------------------------- streaming

    private fun nowSec(): Double = System.currentTimeMillis() / 1000.0
    private fun wallNs(): Long = System.currentTimeMillis() * 1_000_000L

    /**
     * Launch a sensor stream that is resilient to startup/stream failures.
     *
     * [block] requests stream settings and collects the flow; it suspends until
     * the device disconnects (which cancels this job) or it throws. A throw —
     * e.g. requestFullStreamSettings failing transiently right after connect, as
     * happened on 2026-06-18 where PPG/ACC silently never started while gyro ran
     * the whole session — is logged and retried with backoff instead of killing
     * the sensor for the rest of the session.
     */
    private fun launchStream(name: String, maxAttempts: Int = 6, block: suspend () -> Unit): Job =
        scope.launch {
            var attempt = 0
            while (isActive) {
                try {
                    block()
                    Log.i(TAG, "$name stream ended")
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: UnsupportedRateException) {
                    // Configuration error, not a transient BLE glitch — retrying
                    // won't help, the device's supported rates aren't changing.
                    // Fail loudly and stop the whole session rather than silently
                    // leaving this sensor MISSING while others keep recording.
                    Log.e(TAG, "$name: unsupported rate requested: ${e.message}")
                    _connectionState.value = ConnectionState.Failed(
                        "Unsupported $name rate: ${e.requestedHz}Hz not in ${e.availableHz}",
                    )
                    disconnect()
                    break
                } catch (e: Exception) {
                    attempt++
                    Log.e(TAG, "$name stream failed (attempt $attempt/$maxAttempts): ${e.message}", e)
                    if (attempt >= maxAttempts) {
                        Log.e(TAG, "$name: giving up — sensor will be MISSING for this session")
                        break
                    }
                    delay(1500L * attempt)
                }
            }
        }

    /**
     * Pick a sensor setting requesting [targetRate] for SAMPLE_RATE and the
     * max of every other available key.
     *
     * Per #1's acceptance criteria, an unsupported target rate is now a hard
     * failure (throws UnsupportedRateException) rather than a silent
     * fallback to the device's max available rate — a user who explicitly
     * configured e.g. 176Hz PPG should find out immediately if that's not
     * achievable on the connected device, not discover a lower rate was
     * used only after the fact.
     */
    private fun chooseSetting(available: PolarSensorSetting, targetRate: Int): Pair<PolarSensorSetting, Int> {
        val rates = available.settings[SettingType.SAMPLE_RATE].orEmpty()
        if (rates.isNotEmpty() && !rates.contains(targetRate)) {
            throw UnsupportedRateException(targetRate, rates)
        }
        val selected = HashMap<SettingType, Int>()
        for ((key, values) in available.settings) {
            selected[key] = if (key == SettingType.SAMPLE_RATE) targetRate else (values.maxOrNull() ?: continue)
        }
        if (!selected.containsKey(SettingType.SAMPLE_RATE)) selected[SettingType.SAMPLE_RATE] = targetRate
        return PolarSensorSetting(selected) to targetRate
    }

    private fun startHrStream(deviceId: String) {
        hrJob?.cancel()
        hrJob = scope.launch {
            api.startHrStreaming(deviceId)
                .catch { e -> Log.e(TAG, "HR stream error", e) }
                .collect { hrData: PolarHrData ->
                    hrData.samples.firstOrNull()?.hr?.let { if (it > 0) _metrics.value = _metrics.value.copy(hr = it) }
                }
        }
    }

    /**
     * #19: watch per-sensor sample counts and act when a stream reports started but
     * delivers nothing.
     *
     * Deliberately keyed on counts rather than connection state or heart rate. In all
     * three observed occurrences the link was up and HR was arriving normally while the
     * PMD streams produced nothing, so anything else would have looked healthy.
     *
     * One restart is attempted, because the failure has recovered on its own on a
     * subsequent run and a restart is the cheapest thing that might reproduce that. If it
     * does not help, stop trying and say so: a silent retry loop would hide the problem
     * for the whole recording, which is exactly what happened before this existed.
     */
    private fun startStreamWatchdog(deviceId: String, expected: Set<SensorType>) {
        watchdogJob?.cancel()
        if (expected.isEmpty()) return
        watchdogJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            var restartedAtElapsed: Long? = null
            var countsAtRestart: Map<SensorType, Long> = emptyMap()

            while (isActive) {
                delay(WATCHDOG_POLL_MS)
                val m = _metrics.value
                val counts = mapOf(
                    SensorType.PPG to m.ppgSamples,
                    SensorType.ACC to m.accSamples,
                    SensorType.GYRO to m.gyroSamples,
                )
                val elapsed = System.currentTimeMillis() - startedAt
                when (
                    val verdict = StreamWatchdog.evaluate(
                        counts = counts,
                        expected = expected,
                        elapsedSinceStartMs = elapsed,
                        restartedAtElapsedMs = restartedAtElapsed,
                        countsAtRestart = countsAtRestart,
                    )
                ) {
                    is WatchdogVerdict.Waiting -> Unit

                    is WatchdogVerdict.Healthy -> {
                        if (_metrics.value.streamWarning != null) {
                            _metrics.value = _metrics.value.copy(
                                silentSensors = emptySet(),
                                streamWarning = null,
                            )
                        }
                        // Nothing more to prove once every stream has delivered.
                        if (restartedAtElapsed != null) break
                    }

                    is WatchdogVerdict.Restart -> {
                        Log.w(TAG, "WATCHDOG: no samples from ${verdict.silent} after ${elapsed}ms " +
                            "(hr=${m.hr}) — restarting those streams (#19)")
                        restartedAtElapsed = elapsed
                        countsAtRestart = counts
                        _metrics.value = _metrics.value.copy(
                            silentSensors = verdict.silent,
                            streamWarning = StreamWatchdog.describe(verdict.silent, recovered = true),
                        )
                        verdict.silent.forEach { sensor ->
                            when (sensor) {
                                SensorType.PPG -> startPpgStream(deviceId)
                                SensorType.ACC -> startAccStream(deviceId)
                                SensorType.GYRO -> startGyroStream(deviceId)
                                else -> Unit
                            }
                        }
                    }

                    is WatchdogVerdict.Failed -> {
                        Log.e(TAG, "WATCHDOG: ${verdict.silent} still silent after a restart " +
                            "(${elapsed}ms, hr=${m.hr}) — this recording is missing those sensors (#19)")
                        _metrics.value = _metrics.value.copy(
                            silentSensors = verdict.silent,
                            streamWarning = StreamWatchdog.describe(verdict.silent, recovered = false),
                        )
                        break
                    }
                }
            }
        }
    }

    private fun startPpgStream(deviceId: String) {
        ppgJob?.cancel()
        ppgJob = launchStream("PPG") {
            val available = api.requestFullStreamSettings(deviceId, PolarBleApi.PolarDeviceDataType.PPG)
            val target = activeProfile.rates[SensorType.PPG] ?: 176
            val (setting, rate) = chooseSetting(available, target)
            Log.i(TAG, "PPG streaming @ ${rate}Hz")
            api.startPpgStreaming(deviceId, setting)
                .collect { ppgData: PolarPpgData ->
                    val s = session
                    val n = ppgData.samples.size
                    if (n == 0) return@collect
                    s?.captureEpochOffset(ppgData.samples.first().timeStamp, wallNs())
                    val seg = segmentId
                    val buf = ByteArrayOutputStream(n * SensorType.PPG.recordSize)
                    for (sample in ppgData.samples) {
                        val cs = sample.channelSamples
                        buf.write(
                            packPpg(
                                sample.timeStamp, seg,
                                cs.getOrElse(0) { 0 }, cs.getOrElse(1) { 0 },
                                cs.getOrElse(2) { 0 }, cs.getOrElse(3) { 0 },
                            )
                        )
                    }
                    _metrics.value = _metrics.value.copy(ppgSamples = _metrics.value.ppgSamples + n)
                    withContext(Dispatchers.IO) {
                        s?.appendRecords(SensorType.PPG, buf.toByteArray(), n, rate, System.currentTimeMillis())
                    }
                }
        }
    }

    private fun startAccStream(deviceId: String) {
        accJob?.cancel()
        accJob = launchStream("ACC") {
            val available = api.requestFullStreamSettings(deviceId, PolarBleApi.PolarDeviceDataType.ACC)
            val target = activeProfile.rates[SensorType.ACC] ?: 52
            val (setting, rate) = chooseSetting(available, target)
            Log.i(TAG, "ACC streaming @ ${rate}Hz")
            api.startAccStreaming(deviceId, setting)
                .collect { accData: PolarAccelerometerData ->
                    val s = session
                    val n = accData.samples.size
                    if (n == 0) return@collect
                    s?.captureEpochOffset(accData.samples.first().timeStamp, wallNs())
                    val seg = segmentId
                    val buf = ByteArrayOutputStream(n * SensorType.ACC.recordSize)
                    for (sample in accData.samples) {
                        buf.write(packAcc(sample.timeStamp, seg, sample.x, sample.y, sample.z))
                    }
                    _metrics.value = _metrics.value.copy(accSamples = _metrics.value.accSamples + n)
                    withContext(Dispatchers.IO) {
                        s?.appendRecords(SensorType.ACC, buf.toByteArray(), n, rate, System.currentTimeMillis())
                    }
                }
        }
    }

    private fun startGyroStream(deviceId: String) {
        gyroJob?.cancel()
        gyroJob = launchStream("GYRO") {
            val available = api.requestFullStreamSettings(deviceId, PolarBleApi.PolarDeviceDataType.GYRO)
            val target = activeProfile.rates[SensorType.GYRO] ?: 52
            val (setting, rate) = chooseSetting(available, target)
            Log.i(TAG, "GYRO streaming @ ${rate}Hz")
            api.startGyroStreaming(deviceId, setting)
                .collect { gyroData: PolarGyroData ->
                    val s = session
                    val n = gyroData.samples.size
                    if (n == 0) return@collect
                    s?.captureEpochOffset(gyroData.samples.first().timeStamp, wallNs())
                    val seg = segmentId
                    val buf = ByteArrayOutputStream(n * SensorType.GYRO.recordSize)
                    for (sample in gyroData.samples) {
                        buf.write(packGyro(sample.timeStamp, seg, sample.x, sample.y, sample.z))
                    }
                    _metrics.value = _metrics.value.copy(gyroSamples = _metrics.value.gyroSamples + n)
                    withContext(Dispatchers.IO) {
                        s?.appendRecords(SensorType.GYRO, buf.toByteArray(), n, rate, System.currentTimeMillis())
                    }
                }
        }
    }
}
