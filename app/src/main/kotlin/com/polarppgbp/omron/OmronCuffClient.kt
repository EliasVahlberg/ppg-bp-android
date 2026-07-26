/*
 * Omron Evolv (HEM-7600T / BP7000) BLE driver — Android port of
 * src/polar_ble/cuff.py, built on raw BluetoothGatt (the Polar SDK is
 * Polar-only) and the verified OmronProtocol primitives.
 *
 * Flow (device already paired from desktop -> key in EEPROM):
 *   scan -> connect -> ensure OS bond -> discover -> unlock(key) ->
 *   enable 4 RX notifies -> start transmission -> read user record region
 *   (ring buffer) -> end transmission -> decode -> disconnect.
 *
 * The legacy protocol splits packets >16 bytes across 4 RX/TX channels; we
 * reassemble in channel order and verify XOR-CRC. Commands are request/
 * response and strictly serial, so a single in-flight deferred suffices.
 *
 * Protocol research credit: omblepy. See omron_evolv_protocol.md.
 */

package com.polarppgbp.omron

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

private const val TAG = "OmronCuff"
private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

class CuffException(message: String) : Exception(message)

@SuppressLint("MissingPermission")
class OmronCuffClient(
    private val context: Context,
    private val unlockKey: ByteArray = OmronProtocol.DEFAULT_UNLOCK_KEY,
    private val onStatus: (String) -> Unit = {},
) {
    private val adapter: BluetoothAdapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null

    // One in-flight awaitable per callback kind (protocol is strictly serial).
    @Volatile private var connectDeferred: CompletableDeferred<Boolean>? = null
    @Volatile private var servicesDeferred: CompletableDeferred<Boolean>? = null
    @Volatile private var writeDeferred: CompletableDeferred<Boolean>? = null
    @Volatile private var descriptorDeferred: CompletableDeferred<Boolean>? = null
    @Volatile private var unlockDeferred: CompletableDeferred<ByteArray>? = null
    @Volatile private var packetDeferred: CompletableDeferred<OmronProtocol.InboundPacket>? = null

    private val rxChannels = arrayOfNulls<ByteArray>(OmronProtocol.NUM_CHANNELS)
    private val handleToChannel = HashMap<UUID, Int>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectDeferred?.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectDeferred?.complete(false)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descriptorDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API 33+ value-carrying callback.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotify(c.uuid, value)
        }
    }

    private fun handleNotify(uuid: UUID, value: ByteArray) {
        Log.i(TAG, "notify ${uuid.toString().substring(0, 8)} = ${OmronProtocol.bytesToHex(value)}")
        if (uuid.toString().equals(OmronProtocol.UNLOCK_UUID, ignoreCase = true)) {
            unlockDeferred?.complete(value.copyOf())
            return
        }
        val ch = handleToChannel[uuid] ?: return
        rxChannels[ch] = value.copyOf()
        val ch0 = rxChannels[0] ?: return
        val size = ch0[0].toInt() and 0xFF
        val required = (size + OmronProtocol.CHANNEL_WIDTH - 1) / OmronProtocol.CHANNEL_WIDTH
        if ((0 until required).any { rxChannels[it] == null }) return
        val combined = ArrayList<Byte>()
        for (i in 0 until required) rxChannels[i]!!.forEach { combined.add(it) }
        val raw = combined.toByteArray().copyOf(size)
        for (i in rxChannels.indices) rxChannels[i] = null
        try {
            packetDeferred?.complete(OmronProtocol.parseInbound(raw))
        } catch (e: Exception) {
            Log.w(TAG, "bad packet: ${e.message}")
        }
    }

    // ----------------------------------------------------------- public

    /**
     * A record read plus the clock comparison taken during the same connection.
     *
     * The clock read is folded in here rather than exposed as a separate call because
     * the cuff sleeps within about a minute of the transfer button being pressed, so a
     * second connection would need a second button press -- and because a caller that
     * can forget to measure drift is a caller that will (#9).
     */
    data class CuffReadResult(
        val readings: List<OmronProtocol.CuffReading>,
        val clock: CuffClockObservation,
        /** The cuff's own unread counter (#10), null when it could not be read. */
        val unreadOnDevice: Int? = null,
    )

    /**
     * Connect, unlock, read the full record ring buffer and the cuff clock, disconnect.
     */
    suspend fun readRecords(deviceAddress: String? = null): CuffReadResult {
        val device = resolveDevice(deviceAddress)
        onStatus("Connecting to ${device.address}")
        try {
            connect(device)
            discover()
            enableNotify(OmronProtocol.RX_CHANNEL_UUIDS[0]) // triggers SMP bond if needed
            waitForBonded()
            enableNotify(OmronProtocol.UNLOCK_UUID)
            unlock()
            enableRemainingRxNotifications()
            startTransmission()
            val region = readRegion(
                OmronProtocol.USER_RECORDS_ADDR,
                OmronProtocol.USER_RECORDS_COUNT * OmronProtocol.RECORD_SIZE,
            )
            val (clock, unread) = observeSettings()
            endTransmission()
            val readings = decodeRegion(region)
            onStatus("Read ${readings.size} record(s). ${clock.detail}")
            return CuffReadResult(readings, clock, unread)
        } finally {
            close()
        }
    }

    /**
     * Read the cuff's own clock and compare it to the phone, bracketing the BLE read
     * with phone timestamps so the measurement carries its own uncertainty.
     *
     * Must be called inside an open transmission session. Never throws: a failed clock
     * read degrades to an observation with `clockValid = false` rather than losing the
     * records that were just read successfully.
     */
    private suspend fun observeClock(): CuffClockObservation = observeSettings().first

    /**
     * One settings-region read yielding both the clock comparison and the unread counter.
     * They live in the same region, so reading it twice would cost a second round trip
     * and widen the clock measurement's uncertainty for nothing.
     */
    private suspend fun observeSettings(): Pair<CuffClockObservation, Int?> {
        val before = System.currentTimeMillis()
        var unread: Int? = null
        val clock = try {
            val region = readRegion(OmronProtocol.SETTINGS_READ_ADDR, OmronProtocol.TRANSMISSION_BLOCK_SIZE)
            unread = OmronProtocol.parseUnreadCount(region)
            val (from, to) = OmronProtocol.TIME_SYNC_RANGE
            if (region.size < to) {
                Log.w(TAG, "settings region too short for the clock block: ${region.size}")
                null
            } else {
                OmronProtocol.parseTimeSync(region.copyOfRange(from, from + OmronProtocol.TIME_SYNC_BLOCK_SIZE))
            }
        } catch (e: Exception) {
            Log.w(TAG, "settings read failed: ${e.message}")
            null
        }
        val after = System.currentTimeMillis()
        val observation = CuffClockObservation.of(clock, before, after).also {
            Log.i(TAG, "clock: ${it.detail} (offset=${it.offsetSeconds}s valid=${it.clockValid})")
        }
        return observation to unread
    }

    /**
     * Connect, unlock, and read the cuff's settings/config region raw.
     *
     * Shared primitive for two Phase 1 issues, so it is implemented once and
     * used twice (#9 and #10 both need this same region):
     *
     *   - #9  cuff clock: [OmronProtocol.TIME_SYNC_RANGE] (offsets 0x14..0x1E)
     *         holds the cuff's own clock, which is what makes the read-only
     *         design possible — no EEPROM write needed to measure drift.
     *   - #10 ring-buffer overflow: [OmronProtocol.UNREAD_RECORDS_RANGE]
     *         (offsets 0x00..0x08) holds the unread-record counters.
     *
     * Returns the raw bytes starting at [OmronProtocol.SETTINGS_READ_ADDR];
     * the caller indexes with the offset ranges above. Deliberately does no
     * decoding: the byte-level layout is inferred from omblepy and the protocol
     * notes and has not previously been read from real hardware, so the first
     * job is to look at the actual bytes.
     *
     * Read-only. Nothing here writes to the cuff.
     */
    suspend fun readSettingsRegion(
        deviceAddress: String? = null,
        numBytes: Int = OmronProtocol.TRANSMISSION_BLOCK_SIZE,
    ): ByteArray {
        val device = resolveDevice(deviceAddress)
        onStatus("Connecting to ${device.address}")
        try {
            connect(device)
            discover()
            enableNotify(OmronProtocol.RX_CHANNEL_UUIDS[0]) // triggers SMP bond if needed
            waitForBonded()
            enableNotify(OmronProtocol.UNLOCK_UUID)
            unlock()
            enableRemainingRxNotifications()
            startTransmission()
            val region = readRegion(OmronProtocol.SETTINGS_READ_ADDR, numBytes)
            endTransmission()
            onStatus("Read ${region.size} settings byte(s)")
            return region
        } finally {
            close()
        }
    }

    /**
     * Read only the cuff's clock, in its own connection. Used to verify a clock write
     * after the fact: settings writes stage and commit at end-of-transmission, so the
     * live value cannot be verified inside the writing session (verified on hardware
     * 2026-07-26).
     */
    suspend fun observeCuffClock(deviceAddress: String? = null): CuffClockObservation {
        val device = resolveDevice(deviceAddress)
        onStatus("Connecting to ${device.address} to read the clock")
        try {
            connect(device)
            discover()
            enableNotify(OmronProtocol.RX_CHANNEL_UUIDS[0])
            waitForBonded()
            enableNotify(OmronProtocol.UNLOCK_UUID)
            unlock()
            enableRemainingRxNotifications()
            startTransmission()
            val clock = observeClock()
            endTransmission()
            return clock
        } finally {
            close()
        }
    }

    /**
     * Write the cuff clock and then prove it is actually running (#18).
     *
     * The verification is a separate connection taken [CuffClockRepair.SETTLE_MS] after
     * the write, which is what makes a single read sufficient: a clock frozen at the
     * written value would by now be behind the phone by the settle time, so continued
     * agreement can only mean the RTC is ticking.
     *
     * Never throws. Every failure path returns a report naming the stage, because the
     * point of the guard is that a failed repair leaves the device no worse than before
     * and says so.
     */
    suspend fun repairCuffClock(
        deviceAddress: String? = null,
        force: Boolean = false,
        settleMs: Long = CuffClockRepair.SETTLE_MS,
    ): RepairReport {
        var wroteIso: String? = null
        val writeStart: Long
        try {
            val result = writeCuffClock(deviceAddress, setpoint = null, force = force)
            wroteIso = OmronProtocol.parseTimeSync(result.payload).iso()
            writeStart = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.w(TAG, "clock repair: write stage failed: ${e.message}")
            return CuffClockRepair.classify(null, null, 0, writeError = e.message ?: "unknown error")
        }

        val remaining = settleMs - (System.currentTimeMillis() - writeStart)
        if (remaining > 0) {
            onStatus("Waiting ${remaining / 1000}s to check the clock is advancing…")
            kotlinx.coroutines.delay(remaining)
        }

        val verification = try {
            observeCuffClock(deviceAddress)
        } catch (e: Exception) {
            // Very likely the cuff went back to sleep: recoverable, and not a failed write.
            Log.w(TAG, "clock repair: verification read failed: ${e.message}")
            null
        }
        val elapsed = System.currentTimeMillis() - writeStart
        return CuffClockRepair.classify(wroteIso, verification, elapsed).also {
            onStatus(it.detail)
            Log.i(TAG, "clock repair: ${it.outcome} — ${it.detail}")
        }
    }

    /** Explicit clock setpoint. Omit to use the phone clock sampled at write time. */
    data class ClockSetpoint(
        val year: Int,
        val month: Int,
        val day: Int,
        val hour: Int,
        val minute: Int,
        val second: Int,
    )

    /**
     * Result of a clock-write attempt. Carries enough raw material to audit the
     * write after the fact without re-reading the device.
     *
     * [clockAfter] is read *in the same session* and therefore normally still shows
     * the old value: settings writes stage into the write mirror and are committed
     * by the device at end-of-transmission (verified on hardware 2026-07-26). Real
     * verification requires a second connection — see [OmronProtocol.CuffClock].
     */
    data class ClockWriteResult(
        val snapshotBefore: ByteArray,
        val snapshotAfter: ByteArray,
        val payload: ByteArray,
        val writeAddress: Int,
        val clockBefore: OmronProtocol.CuffClock,
        val clockAfter: OmronProtocol.CuffClock,
        /** Offsets (from the settings read base) that changed but should not have. */
        val unexpectedChanges: List<Int>,
    )

    /**
     * Write the cuff's clock. **The only write path in this app.**
     *
     * The Evolv has no on-device clock UI, so once its RTC halts (battery change)
     * this is the sole recovery route — see issue #18.
     *
     * Safety measures, in order:
     *  1. Snapshot the settings read region *including* the write mirror before
     *     touching anything, so an unintended change is detectable.
     *  2. Refuse to write unless the clock is actually halted, unless [force].
     *  3. Write only the 10 time bytes, at [OmronProtocol.timeSyncWriteAddress].
     *  4. Re-read the snapshot and diff every byte outside both the time block and
     *     the write mirror's copy of it.
     *
     * The setpoint is sampled immediately before the write, not when the caller is
     * invoked: connect + bond + unlock costs several seconds and would otherwise be
     * baked into the cuff's clock as a permanent offset.
     */
    suspend fun writeCuffClock(
        deviceAddress: String? = null,
        setpoint: ClockSetpoint? = null,
        force: Boolean = false,
        snapshotBytes: Int = 0x4C,
    ): ClockWriteResult {
        val writeAddr = OmronProtocol.timeSyncWriteAddress()
        val timeOffset = OmronProtocol.TIME_SYNC_RANGE.first
        val mirrorOffset = OmronProtocol.SETTINGS_WRITE_ADDR - OmronProtocol.SETTINGS_READ_ADDR + timeOffset
        val device = resolveDevice(deviceAddress)
        onStatus("Connecting to ${device.address}")
        try {
            connect(device)
            discover()
            enableNotify(OmronProtocol.RX_CHANNEL_UUIDS[0])
            waitForBonded()
            enableNotify(OmronProtocol.UNLOCK_UUID)
            unlock()
            enableRemainingRxNotifications()
            startTransmission()

            val before = readRegion(OmronProtocol.SETTINGS_READ_ADDR, snapshotBytes)
            val clockBefore = OmronProtocol.parseTimeSync(
                before.copyOfRange(timeOffset, timeOffset + OmronProtocol.TIME_SYNC_BLOCK_SIZE),
            )
            onStatus("clock before: ${clockBefore.iso()} halted=${clockBefore.halted} crcOk=${clockBefore.checksumOk}")

            if (!clockBefore.halted && !force) {
                endTransmission()
                throw CuffException(
                    "Refusing to write: clock is not halted (${clockBefore.iso()}). Pass force to override.",
                )
            }

            // Sample the wall clock here, after all connection latency.
            val sp = setpoint ?: java.util.Calendar.getInstance().let {
                ClockSetpoint(
                    it.get(java.util.Calendar.YEAR),
                    it.get(java.util.Calendar.MONTH) + 1,
                    it.get(java.util.Calendar.DAY_OF_MONTH),
                    it.get(java.util.Calendar.HOUR_OF_DAY),
                    it.get(java.util.Calendar.MINUTE),
                    it.get(java.util.Calendar.SECOND),
                )
            }
            val payload = OmronProtocol.encodeTimeSync(
                sp.year, sp.month, sp.day, sp.hour, sp.minute, sp.second,
            )
            onStatus("writing ${OmronProtocol.bytesToHex(payload)} @ 0x${writeAddr.toString(16)} (setpoint ${sp.year}-${sp.month}-${sp.day} ${sp.hour}:${sp.minute}:${sp.second})")
            writeRegion(writeAddr, payload)

            val after = readRegion(OmronProtocol.SETTINGS_READ_ADDR, snapshotBytes)
            val clockAfter = OmronProtocol.parseTimeSync(
                after.copyOfRange(timeOffset, timeOffset + OmronProtocol.TIME_SYNC_BLOCK_SIZE),
            )
            // Commit happens here; the live block only changes after this returns.
            endTransmission()

            val expected = (timeOffset until timeOffset + OmronProtocol.TIME_SYNC_BLOCK_SIZE) +
                (mirrorOffset until mirrorOffset + OmronProtocol.TIME_SYNC_BLOCK_SIZE)
            val unexpected = before.indices
                .filter { it !in expected && it < after.size && before[it] != after[it] }

            onStatus("staged; live clock updates on end-of-transmission — verify with a fresh read")
            if (unexpected.isNotEmpty()) {
                onStatus("WARNING: ${unexpected.size} byte(s) changed outside the time block")
            }
            return ClockWriteResult(
                snapshotBefore = before,
                snapshotAfter = after,
                payload = payload,
                writeAddress = writeAddr,
                clockBefore = clockBefore,
                clockAfter = clockAfter,
                unexpectedChanges = unexpected,
            )
        } finally {
            close()
        }
    }

    fun close() {        runCatching { bondReceiver?.let { context.unregisterReceiver(it) } }
        bondReceiver = null
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    /**
     * One-time pairing: program the unlock key into the cuff (device must be in
     * -P- key-programming mode, display blinking "P"), finalise per omblepy, then
     * read records in the same session. Mirrors cuff.py OmronEvolvCuff.pair().
     */
    suspend fun pairAndRead(deviceAddress: String? = null): CuffReadResult {
        val device = resolveDevice(deviceAddress)
        onStatus("Pairing with ${device.address} (cuff must blink 'P')")
        try {
            clearStaleBond(device)
            connect(device)
            discover()
            // RX0 subscribe makes the cuff send its SMP security request, which
            // bonds in-GATT-context (reliable, unlike standalone createBond).
            enableNotify(OmronProtocol.RX_CHANNEL_UUIDS[0])
            waitForBonded()
            enableNotify(OmronProtocol.UNLOCK_UUID)
            enterProgrammingMode()
            programKey()
            enableRemainingRxNotifications()
            // omblepy: open+close a transmission so the cuff finalises post-pair
            // state, otherwise subsequent connects silently drop.
            startTransmission(); endTransmission()
            onStatus("Paired. Reading records…")
            startTransmission()
            val region = readRegion(
                OmronProtocol.USER_RECORDS_ADDR,
                OmronProtocol.USER_RECORDS_COUNT * OmronProtocol.RECORD_SIZE,
            )
            val (clock, unread) = observeSettings()
            endTransmission()
            val readings = decodeRegion(region)
            onStatus("Paired + read ${readings.size} record(s). ${clock.detail}")
            return CuffReadResult(readings, clock, unread)
        } finally {
            close()
        }
    }

    private suspend fun enterProgrammingMode() {
        for (attempt in 0 until 10) {
            unlockDeferred = CompletableDeferred()
            writeChar(OmronProtocol.UNLOCK_UUID, byteArrayOf(0x02) + ByteArray(OmronProtocol.UNLOCK_KEY_SIZE))
            val resp = withTimeoutOrNull(2_000) { unlockDeferred!!.await() }
            onStatus("pair-enter ${attempt + 1}: ${resp?.let { OmronProtocol.bytesToHex(it) } ?: "timeout"}")
            if (resp != null && resp.isNotEmpty() && resp[0] == OmronProtocol.RX_PAIR_MODE_ENTERED[0]) return
            delay(1_000)
        }
        throw CuffException("Could not enter key-programming mode. Hold the cuff button until it blinks 'P'.")
    }

    private suspend fun programKey() {
        unlockDeferred = CompletableDeferred()
        writeChar(OmronProtocol.UNLOCK_UUID, byteArrayOf(0x00) + unlockKey)
        val resp = withTimeout(3_000) { unlockDeferred!!.await() }
        onStatus("key-program response: ${OmronProtocol.bytesToHex(resp)}")
        if (resp.isEmpty() || resp[0] != OmronProtocol.RX_START_ACK[0]) {
            throw CuffException("Failed to program unlock key, response=${OmronProtocol.bytesToHex(resp)}")
        }
    }

    // ----------------------------------------------------------- BLE steps

    private suspend fun resolveDevice(address: String?): BluetoothDevice {
        if (address != null) return adapter.getRemoteDevice(address)
        // Prefer an already-bonded Omron, else scan for advertising one.
        adapter.bondedDevices.firstOrNull { isOmronName(it.name) }?.let {
            onStatus("Using bonded cuff ${it.name}")
            return it
        }
        return scanForCuff() ?: throw CuffException("No Omron cuff found. Press its button so it advertises.")
    }

    private fun isOmronName(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return "blesmart" in n || "evolv" in n
    }

    private suspend fun scanForCuff(timeoutMs: Long = 12_000): BluetoothDevice? {
        val scanner = adapter.bluetoothLeScanner ?: return null
        val found = CompletableDeferred<BluetoothDevice?>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (isOmronName(result.device.name)) found.complete(result.device)
            }
        }
        onStatus("Scanning for cuff…")
        scanner.startScan(cb)
        return try {
            withTimeoutOrNull(timeoutMs) { found.await() }
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
    }

    private suspend fun connect(device: BluetoothDevice) {
        registerBondLogger(device.address)
        connectDeferred = CompletableDeferred()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        val ok = withTimeout(15_000) { connectDeferred!!.await() }
        if (!ok) throw CuffException("BLE connect failed")
        onStatus("Connected (bondState=${device.bondState})")
    }

    private var bondReceiver: android.content.BroadcastReceiver? = null

    private fun registerBondLogger(address: String) {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) {
                @Suppress("DEPRECATION")
                val dev = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (dev?.address != address) return
                val s = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                val p = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                onStatus("bond $p -> $s")
            }
        }
        bondReceiver = r
        context.registerReceiver(r, android.content.IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
    }

    private suspend fun discover() {
        servicesDeferred = CompletableDeferred()
        gatt!!.discoverServices()
        if (!withTimeout(10_000) { servicesDeferred!!.await() }) {
            throw CuffException("Service discovery failed")
        }
        val svc = gatt!!.getService(UUID.fromString(OmronProtocol.SERVICE_UUID))
            ?: throw CuffException("Omron legacy service not present on this device")
        handleToChannel.clear()
        OmronProtocol.RX_CHANNEL_UUIDS.forEachIndexed { i, u ->
            if (svc.getCharacteristic(UUID.fromString(u)) == null) {
                throw CuffException("RX channel $i characteristic missing")
            }
            handleToChannel[UUID.fromString(u)] = i
        }
    }

    private fun characteristic(uuid: String): BluetoothGattCharacteristic =
        gatt!!.getService(UUID.fromString(OmronProtocol.SERVICE_UUID))
            .getCharacteristic(UUID.fromString(uuid))
            ?: throw CuffException("characteristic $uuid missing")

    private suspend fun enableNotify(uuid: String) {
        val ch = characteristic(uuid)
        gatt!!.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD_UUID) ?: throw CuffException("CCCD missing on $uuid")
        // The first encrypted subscribe (RX0) makes the cuff request SMP; Android
        // bonds in-context and the descriptor write completes once bonded (this can
        // take a while incl. accepting the pairing prompt). Retry once if it fails.
        for (attempt in 0 until 2) {
            descriptorDeferred = CompletableDeferred()
            val rc = gatt!!.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            val ok = withTimeoutOrNull(90_000) { descriptorDeferred!!.await() } ?: false
            if (ok) return
            onStatus("enable notify ${uuid.takeLast(4)} failed (rc=$rc, attempt ${attempt + 1})")
            delay(800)
        }
        throw CuffException("enable notify failed for $uuid")
    }

    private suspend fun enableRemainingRxNotifications() {
        for (i in 1 until OmronProtocol.NUM_CHANNELS) enableNotify(OmronProtocol.RX_CHANNEL_UUIDS[i])
    }

    private suspend fun waitForBonded(timeoutMs: Long = 90_000) {
        val dev = gatt!!.device
        if (dev.bondState == BluetoothDevice.BOND_BONDED) {
            onStatus("Already bonded")
            return
        }
        onStatus("Waiting for bond (accept the pairing prompt if shown)…")
        val ok = withTimeoutOrNull(timeoutMs) {
            while (dev.bondState != BluetoothDevice.BOND_BONDED) delay(400)
            true
        }
        if (ok != true) throw CuffException("Bonding not completed (state=${dev.bondState})")
        onStatus("Bonded")
    }

    /**
     * Remove any stale/half bond so the subsequent RX0 subscribe triggers a
     * fresh, properly-encrypted SMP bond. A bond left by a prior flaky
     * createBond reports BONDED but yields an unencrypted link (cuff rejects
     * writes with 82 08). We do NOT call createBond here — the cuff initiates
     * SMP itself when we subscribe to RX0, which produces an encrypted bond.
     */
    private suspend fun clearStaleBond(device: BluetoothDevice) {
        if (device.bondState != BluetoothDevice.BOND_BONDED) return
        onStatus("Removing stale bond (clean re-bond will happen on RX0 subscribe)")
        runCatching { device.javaClass.getMethod("removeBond").invoke(device) }
            .onFailure { onStatus("removeBond failed: ${it.message} — forget the cuff in Settings") }
        withTimeoutOrNull(5_000) {
            while (device.bondState != BluetoothDevice.BOND_NONE) delay(300)
            true
        }
        onStatus("Stale bond cleared (state=${device.bondState})")
    }

    private suspend fun writeChar(uuid: String, value: ByteArray) {
        writeDeferred = CompletableDeferred()
        val ch = characteristic(uuid)
        gatt!!.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        if (!withTimeout(5_000) { writeDeferred!!.await() }) {
            throw CuffException("write failed for $uuid")
        }
    }

    private suspend fun unlock() {
        onStatus("Unlocking…")
        enableNotify(OmronProtocol.UNLOCK_UUID)
        unlockDeferred = CompletableDeferred()
        writeChar(OmronProtocol.UNLOCK_UUID, byteArrayOf(0x01) + unlockKey)
        val resp = withTimeout(5_000) { unlockDeferred!!.await() }
        onStatus("unlock response: ${OmronProtocol.bytesToHex(resp)}")
        // 0x81 = unlock/read response marker. The desktop doc assumed the 2nd
        // byte is 0x00, but real HEM-7600T firmware returns e.g. 0x81 0x04 on a
        // successful unlock. Accept any 0x81.. and let startTransmission be the
        // real validator (a wrong key blocks the transmission session).
        if (resp.isEmpty() || resp[0] != OmronProtocol.RX_READ[0]) {
            throw CuffException("Unlock rejected, response=${OmronProtocol.bytesToHex(resp)}")
        }
        if (resp.size < 2 || resp[1] != OmronProtocol.RX_READ[1]) {
            onStatus("note: unlock 2nd byte = 0x%02x (proceeding)".format(resp.getOrElse(1) { 0 }))
        }
    }

    /** Send a command split across TX channels, await one inbound packet (with retries). */
    private suspend fun sendCommand(command: ByteArray, retries: Int = 5): OmronProtocol.InboundPacket {
        val channels = OmronProtocol.channelsNeeded(command.size)
        repeat(retries) { attempt ->
            for (i in rxChannels.indices) rxChannels[i] = null
            packetDeferred = CompletableDeferred()
            for (c in 0 until channels) {
                val chunk = command.copyOfRange(
                    c * OmronProtocol.CHANNEL_WIDTH,
                    minOf((c + 1) * OmronProtocol.CHANNEL_WIDTH, command.size),
                )
                Log.i(TAG, "tx ch$c -> ${OmronProtocol.bytesToHex(chunk)}")
                writeChar(OmronProtocol.TX_CHANNEL_UUIDS[c], chunk)
            }
            val pkt = withTimeoutOrNull(2_000) { packetDeferred!!.await() }
            if (pkt != null) return pkt
            Log.w(TAG, "cuff response timeout (attempt ${attempt + 1}/$retries)")
        }
        throw CuffException("No response from cuff after $retries attempts")
    }

    private suspend fun startTransmission() {
        val pkt = sendCommand(OmronProtocol.START_TRANSMISSION)
        if (!pkt.packetType.contentEquals(OmronProtocol.RX_START_ACK)) {
            throw CuffException("Unexpected start response: ${OmronProtocol.bytesToHex(pkt.packetType)}")
        }
    }

    private suspend fun endTransmission() {
        val pkt = sendCommand(OmronProtocol.END_TRANSMISSION)
        if (!pkt.packetType.contentEquals(OmronProtocol.RX_END)) {
            throw CuffException("Unexpected end response: ${OmronProtocol.bytesToHex(pkt.packetType)}")
        }
        if (pkt.data.isNotEmpty() && pkt.data[0].toInt() != 0) {
            throw CuffException("Cuff reported error code ${pkt.data[0]} on end")
        }
    }

    private suspend fun readRegion(startAddr: Int, numBytes: Int): ByteArray {
        val out = ArrayList<Byte>(numBytes)
        var addr = startAddr
        var remaining = numBytes
        while (remaining > 0) {
            val chunk = minOf(remaining, OmronProtocol.TRANSMISSION_BLOCK_SIZE)
            val pkt = sendCommand(OmronProtocol.buildReadEeprom(addr, chunk))
            if (!pkt.packetType.contentEquals(OmronProtocol.RX_READ)) {
                throw CuffException("Unexpected read response: ${OmronProtocol.bytesToHex(pkt.packetType)}")
            }
            if (pkt.eepromAddress != addr) {
                throw CuffException("Address mismatch: requested ${addr.toString(16)}, got ${pkt.eepromAddress.toString(16)}")
            }
            pkt.data.forEach { out.add(it) }
            addr += chunk
            remaining -= chunk
        }
        return out.toByteArray()
    }

    private suspend fun writeRegion(startAddr: Int, data: ByteArray) {
        val pkt = sendCommand(OmronProtocol.buildWriteEeprom(startAddr, data))
        if (!pkt.packetType.contentEquals(OmronProtocol.RX_WRITE)) {
            throw CuffException("Unexpected write response: ${OmronProtocol.bytesToHex(pkt.packetType)}")
        }
        if (pkt.eepromAddress != startAddr) {
            throw CuffException(
                "Write address mismatch: sent ${startAddr.toString(16)}, echoed ${pkt.eepromAddress.toString(16)}",
            )
        }
    }

    private fun decodeRegion(buffer: ByteArray): List<OmronProtocol.CuffReading> {
        val out = ArrayList<OmronProtocol.CuffReading>()
        var offset = 0
        while (offset + OmronProtocol.RECORD_SIZE <= buffer.size) {
            val chunk = buffer.copyOfRange(offset, offset + OmronProtocol.RECORD_SIZE)
            val slot = offset / OmronProtocol.RECORD_SIZE
            try {
                OmronProtocol.parseRecord(chunk)?.let { out.add(it.copy(slotIndex = slot)) }
            } catch (e: Exception) {
                Log.w(TAG, "skip bad record @${offset}: ${e.message}")
            }
            offset += OmronProtocol.RECORD_SIZE
        }
        return out
    }
}
