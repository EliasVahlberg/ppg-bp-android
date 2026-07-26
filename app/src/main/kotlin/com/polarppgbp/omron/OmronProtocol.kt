/*
 * Omron Evolv (HEM-7600T / BP7000) legacy BLE protocol primitives — Kotlin
 * port of src/polar_ble/omron_evolv.py. Pure logic (no Android/BLE I/O) so it
 * is JVM-unit-testable and cross-checked byte-for-byte against the Python
 * reference (see OmronProtocolTest).
 *
 * Protocol research credit: omblepy (https://github.com/userx14/omblepy).
 * Independent reimplementation from docs/design/omron_evolv_protocol.md.
 */

package com.polarppgbp.omron

object OmronProtocol {

    // GATT UUIDs (HEM-7600T legacy service)
    const val SERVICE_UUID = "ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b"
    val RX_CHANNEL_UUIDS = arrayOf(
        "49123040-aee8-11e1-a74d-0002a5d5c51b",
        "4d0bf320-aee8-11e1-a0d9-0002a5d5c51b",
        "5128ce60-aee8-11e1-b84b-0002a5d5c51b",
        "560f1420-aee8-11e1-8184-0002a5d5c51b",
    )
    val TX_CHANNEL_UUIDS = arrayOf(
        "db5b55e0-aee7-11e1-965e-0002a5d5c51b",
        "e0b8a060-aee7-11e1-92f4-0002a5d5c51b",
        "0ae12b00-aee8-11e1-a192-0002a5d5c51b",
        "10e1ba60-aee8-11e1-89e5-0002a5d5c51b",
    )
    const val UNLOCK_UUID = "b305b680-aee7-11e1-a730-0002a5d5c51b"

    const val CHANNEL_WIDTH = 16
    const val NUM_CHANNELS = 4

    // HEM-7600T EEPROM layout
    const val USER_RECORDS_ADDR = 0x02AC
    const val USER_RECORDS_COUNT = 100
    const val RECORD_SIZE = 14
    const val SETTINGS_READ_ADDR = 0x0260
    const val SETTINGS_WRITE_ADDR = 0x0286
    const val TRANSMISSION_BLOCK_SIZE = 0x38
    val UNREAD_RECORDS_RANGE = 0x00 to 0x08
    val TIME_SYNC_RANGE = 0x14 to 0x1E

    const val UNLOCK_KEY_SIZE = 16
    /** omblepy's default key; override per device for security. */
    val DEFAULT_UNLOCK_KEY: ByteArray = hexToBytes("deadbeaf12341234deadbeaf12341234")

    // Pre-encoded constant frames
    val START_TRANSMISSION: ByteArray = hexToBytes("0800000000100018")
    val END_TRANSMISSION: ByteArray = hexToBytes("080f000000000007")

    // Response packet types (bytes 1..2)
    val RX_START_ACK = byteArrayOf(0x80.toByte(), 0x00)
    val RX_READ = byteArrayOf(0x81.toByte(), 0x00)
    val RX_WRITE = byteArrayOf(0x81.toByte(), 0xC0.toByte())
    val RX_PAIR_MODE_ENTERED = byteArrayOf(0x82.toByte(), 0x00)
    val RX_END = byteArrayOf(0x8F.toByte(), 0x00)

    // ---- CRC ----

    /** XOR of all bytes. A correctly received packet XORs to 0 over its full length. */
    fun xorCrc(data: ByteArray): Int {
        var c = 0
        for (b in data) c = c xor (b.toInt() and 0xFF)
        return c
    }

    // ---- command frame builders ----

    /** Read EEPROM: 08 01 00 [addrHi addrLo] [N] 00 [CRC]. */
    fun buildReadEeprom(address: Int, numBytes: Int): ByteArray {
        require(address in 0..0xFFFF) { "address $address out of range" }
        require(numBytes in 1..0xFF) { "numBytes $numBytes out of range" }
        val head = byteArrayOf(
            0x08, 0x01, 0x00,
            (address ushr 8).toByte(), (address and 0xFF).toByte(),
            numBytes.toByte(), 0x00,
        )
        return head + xorCrc(head).toByte()
    }

    /** Write EEPROM: [N+8] 01 c0 [addrHi addrLo] [N] [data...] 00 [CRC]. */
    fun buildWriteEeprom(address: Int, data: ByteArray): ByteArray {
        require(address in 0..0xFFFF) { "address $address out of range" }
        require(data.size in 1..0xFF) { "data length ${data.size} out of range" }
        val total = data.size + 8
        val pkt = byteArrayOf(
            total.toByte(), 0x01, 0xC0.toByte(),
            (address ushr 8).toByte(), (address and 0xFF).toByte(),
            data.size.toByte(),
        ) + data + byteArrayOf(0x00)
        return pkt + xorCrc(pkt).toByte()
    }

    /** Channels required to carry a packet of [packetSize] bytes. */
    fun channelsNeeded(packetSize: Int): Int =
        maxOf(1, (packetSize + CHANNEL_WIDTH - 1) / CHANNEL_WIDTH)

    // ---- inbound packet ----

    data class InboundPacket(val packetType: ByteArray, val eepromAddress: Int, val data: ByteArray)

    /**
     * Parse a fully-reassembled inbound packet. Caller assembles RX channels
     * in order, trims to raw[0] bytes, and this verifies xorCrc == 0.
     */
    fun parseInbound(raw: ByteArray): InboundPacket {
        require(raw.size >= 8) { "packet too short: ${raw.size}" }
        val size = raw[0].toInt() and 0xFF
        require(size <= raw.size) { "declared size $size exceeds buffer ${raw.size}" }
        require(xorCrc(raw.copyOfRange(0, size)) == 0) { "CRC check failed" }
        val ptype = raw.copyOfRange(1, 3)
        val addr = ((raw[3].toInt() and 0xFF) shl 8) or (raw[4].toInt() and 0xFF)
        val nData = raw[5].toInt() and 0xFF
        val data = if (ptype.contentEquals(RX_END)) {
            raw.copyOfRange(6, 7) // end packet carries error byte at offset 6
        } else {
            raw.copyOfRange(6, 6 + nData)
        }
        return InboundPacket(ptype, addr, data)
    }

    // ---- record decoding ----

    data class CuffReading(
        val sysMmHg: Int,
        val diaMmHg: Int,
        val pulseBpm: Int,
        val year: Int,
        val month: Int,
        val day: Int,
        val hour: Int,
        val minute: Int,
        val second: Int,
        val irregularHeartbeat: Boolean,
        val bodyMovement: Boolean,
        /**
         * True when the record's raw seconds field exceeded 59, i.e. it was written
         * while the cuff's RTC was halted (see [CuffClock.halted]). The timestamp is
         * not trustworthy and must not be used as a calibration join key.
         *
         * Additive field with a default so [takenAtIso] — and therefore the dedup
         * identity in CuffStore — is unchanged.
         */
        val clockSuspect: Boolean = false,
        /**
         * Ring-buffer slot this record came from, or -1 when unknown. Needed to identify
         * a reading whose timestamp cannot be trusted, since [takenAtIso] is then not a
         * usable identity. Defaulted, so the dedup identity is unaffected.
         *
         * Not stable across buffer wrap: slot 0 is reused once 100 readings are exceeded
         * (#10).
         */
        val slotIndex: Int = -1,
    ) {
        /** Local-time ISO-8601 (no zone); the cuff stores naive local time. */
        fun takenAtIso(): String =
            "%04d-%02d-%02dT%02d:%02d:%02d".format(year, month, day, hour, minute, second)
    }

    /** Extract bits [first,last] (inclusive, bit 0 = MSB of byte 0) from the first 8 bytes. */
    private fun bitsBe(buf: ByteArray, first: Int, last: Int): Int {
        var l = 0L
        for (i in 0 until 8) l = (l shl 8) or (buf[i].toLong() and 0xFF)
        val numValid = last - first + 1
        val shift = 63 - last
        val mask = (1L shl numValid) - 1
        return ((l ushr shift) and mask).toInt()
    }

    /** Decode a 14-byte HEM-7600T record. Returns null for a blank (all-0xFF) slot. */
    fun parseRecord(buf: ByteArray): CuffReading? {
        require(buf.size == RECORD_SIZE) { "record must be $RECORD_SIZE bytes, got ${buf.size}" }
        if (buf.all { it == 0xFF.toByte() }) return null

        val dia = bitsBe(buf, 0, 7)
        val sys = bitsBe(buf, 8, 15) + 25 // firmware stores systolic - 25
        val year = bitsBe(buf, 16, 23) + 2000
        val bpm = bitsBe(buf, 24, 31)
        val mov = bitsBe(buf, 32, 32) != 0
        val ihb = bitsBe(buf, 33, 33) != 0
        val month = bitsBe(buf, 34, 37)
        val day = bitsBe(buf, 38, 42)
        val hour = bitsBe(buf, 43, 47)
        val minute = bitsBe(buf, 52, 57)
        val secondRaw = bitsBe(buf, 58, 63)
        // Clip for the timestamp (omblepy does the same) but keep the fact that the
        // raw value was out of range: that is the halted-RTC sentinel.
        val second = minOf(secondRaw, 59)

        require(month in 1..12 && day in 1..31 && hour in 0..23 && minute in 0..59) {
            "record decoded to invalid datetime $year-$month-$day $hour:$minute:$second"
        }
        return CuffReading(
            sys, dia, bpm, year, month, day, hour, minute, second, ihb, mov,
            clockSuspect = secondRaw > 59,
        )
    }

    // ---- ring-buffer read planning ----

    data class EepromRead(val address: Int, val size: Int)

    /**
     * Plan EEPROM reads to fetch user records. Whole region unless [onlyUnread],
     * in which case 1-2 reads covering the most-recent [unreadCount] records,
     * accounting for ring-buffer wrap.
     */
    fun planRecordReads(
        userStartAddr: Int = USER_RECORDS_ADDR,
        userRecordCount: Int = USER_RECORDS_COUNT,
        recordSize: Int = RECORD_SIZE,
        onlyUnread: Boolean = false,
        lastWrittenSlot: Int = 0,
        unreadCount: Int = 0,
    ): List<EepromRead> {
        if (!onlyUnread) return listOf(EepromRead(userStartAddr, userRecordCount * recordSize))
        if (unreadCount == 0) return emptyList()
        if (lastWrittenSlot >= unreadCount) {
            val addr = userStartAddr + (lastWrittenSlot - unreadCount) * recordSize
            return listOf(EepromRead(addr, unreadCount * recordSize))
        }
        val head = EepromRead(userStartAddr, lastWrittenSlot * recordSize)
        val wrap = unreadCount - lastWrittenSlot
        val tail = EepromRead(userStartAddr + (userRecordCount - wrap) * recordSize, wrap * recordSize)
        return listOf(tail, head)
    }

    // ---- cuff clock (settings time-sync block) ----

    const val TIME_SYNC_BLOCK_SIZE = 10

    /**
     * Decoded cuff clock from the 10-byte settings time-sync block.
     *
     * [secondRaw] is the unclipped seconds field. The field is 6 bits wide, so it
     * can hold 0..63 while a legal seconds value is 0..59. Hardware test
     * 2026-07-26: after a battery pull the cuff halts its RTC and writes
     * `secondRaw == 0x3F` (63) with a *valid* checksum — a deliberate
     * "clock not set" sentinel, not corruption. Never clip this away; it is the
     * only in-band signal that the timestamp cannot be trusted.
     */
    data class CuffClock(
        val year: Int,
        val month: Int,
        val day: Int,
        val hour: Int,
        val minute: Int,
        val second: Int,
        val secondRaw: Int,
        val checksumOk: Boolean,
    ) {
        /** True when the seconds field carries the halted-clock sentinel. */
        val halted: Boolean get() = secondRaw > 59

        /** Only trust the timestamp when the checksum verifies and all fields are in range. */
        val valid: Boolean
            get() = checksumOk && !halted &&
                month in 1..12 && day in 1..31 &&
                hour in 0..23 && minute in 0..59

        fun iso(): String =
            "%04d-%02d-%02dT%02d:%02d:%02d".format(year, month, day, hour, minute, second)
    }

    /**
     * Encode the 10-byte time-sync block.
     *
     * Layout (verified on hardware, four samples 2026-07-26):
     * `[A0][C0][month][year-2000][hour][day][second][minute][~sum][sum]`
     *
     * The trailing pair is a checksum over bytes 0..7 only: byte 9 is the low byte
     * of the sum, byte 8 is its one's complement, so `byte8 + byte9 == 0xFF`.
     */
    fun encodeTimeSync(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
    ): ByteArray {
        require(year in 2000..2255) { "year $year out of range" }
        require(month in 1..12) { "month $month out of range" }
        require(day in 1..31) { "day $day out of range" }
        require(hour in 0..23) { "hour $hour out of range" }
        require(minute in 0..59) { "minute $minute out of range" }
        require(second in 0..59) { "second $second out of range" }
        val body = byteArrayOf(
            0xA0.toByte(), 0xC0.toByte(),
            month.toByte(), (year - 2000).toByte(),
            hour.toByte(), day.toByte(),
            second.toByte(), minute.toByte(),
        )
        val sum = body.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) } and 0xFF
        return body + byteArrayOf(((0xFF - sum) and 0xFF).toByte(), sum.toByte())
    }

    /** Decode the 10-byte time-sync block. Does not throw on an invalid clock. */
    fun parseTimeSync(block: ByteArray): CuffClock {
        require(block.size == TIME_SYNC_BLOCK_SIZE) {
            "time block must be $TIME_SYNC_BLOCK_SIZE bytes, got ${block.size}"
        }
        fun u(i: Int) = block[i].toInt() and 0xFF
        val sum = (0 until 8).fold(0) { acc, i -> acc + u(i) } and 0xFF
        val checksumOk = u(9) == sum && ((u(8) + u(9)) and 0xFF) == 0xFF
        val secondRaw = u(6)
        return CuffClock(
            year = 2000 + u(3),
            month = u(2),
            day = u(5),
            hour = u(4),
            minute = u(7),
            second = minOf(secondRaw, 59),
            secondRaw = secondRaw,
            checksumOk = checksumOk,
        )
    }

    /** Absolute EEPROM address the time block is written to (settings *write* mirror). */
    fun timeSyncWriteAddress(): Int = SETTINGS_WRITE_ADDR + TIME_SYNC_RANGE.first

    // ---- hex helpers ----

    fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte() }

    fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
