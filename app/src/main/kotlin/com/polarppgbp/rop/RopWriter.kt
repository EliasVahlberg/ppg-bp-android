/*
 * Append-only ROP file writer — Kotlin port of RopWriter in
 * `src/polar_ble/rop_format.py`.
 *
 * One instance per sensor per rotation window. The constructor creates the
 * file (if absent) and writes the 64-byte header; subsequent writeRecord /
 * writeRecords calls append fixed-size records. A partial trailing write
 * (process killed mid-record) is detectable by the reader as
 * (file_size - 64) % record_size != 0 and truncated on read.
 *
 * Contract: exactly ONE writer per file for its lifetime (a new rotation =>
 * a new file). The header is written unconditionally on construction, so do
 * not point a new writer at a file that already has content.
 */

package com.polarppgbp.rop

import java.io.File
import java.io.FileOutputStream

class RopWriter(val file: File, val header: RopHeader) : AutoCloseable {

    private val out: FileOutputStream
    var bytesWritten: Long = 0
        private set
    var recordsWritten: Long = 0
        private set

    init {
        file.parentFile?.mkdirs()
        out = FileOutputStream(file, /* append = */ true)
        val packed = header.pack()
        out.write(packed)
        bytesWritten += packed.size
    }

    /** Append one packed record. Must be exactly [SensorType.recordSize] bytes. */
    fun writeRecord(record: ByteArray) {
        require(record.size == header.sensor.recordSize) {
            "record length ${record.size} != record_size ${header.sensor.recordSize}"
        }
        out.write(record)
        bytesWritten += record.size
        recordsWritten += 1
    }

    /** Append [count] records concatenated as one byte array (single write). */
    fun writeRecords(records: ByteArray, count: Int) {
        val expected = count * header.sensor.recordSize
        require(records.size == expected) {
            "records bytes ${records.size} != count*record_size $expected"
        }
        out.write(records)
        bytesWritten += records.size
        recordsWritten += count
    }

    /** Flush JVM/OS buffers to the page cache. */
    fun flush() = out.flush()

    /** Flush and fsync to durable storage. Call periodically, not per record. */
    fun sync() {
        out.flush()
        out.fd.sync()
    }

    override fun close() {
        runCatching {
            out.flush()
            out.close()
        }
    }
}
