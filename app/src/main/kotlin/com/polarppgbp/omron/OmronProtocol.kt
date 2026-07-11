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
        val second = minOf(bitsBe(buf, 58, 63), 59)

        require(month in 1..12 && day in 1..31 && hour in 0..23 && minute in 0..59) {
            "record decoded to invalid datetime $year-$month-$day $hour:$minute:$second"
        }
        return CuffReading(sys, dia, bpm, year, month, day, hour, minute, second, ihb, mov)
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

    // ---- hex helpers ----

    fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte() }

    fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
