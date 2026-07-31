/*
 * Which recorded bundles still need uploading.
 *
 * This is the decision that stranded three sessions from the 2026-07-27
 * calibration visit: a session is enqueued for upload exactly once, when
 * recording stops, and before the startup sweep existed nothing ever retried a
 * job that had exhausted its attempts. The bundles sat on the phone -- complete,
 * unmarked, never uploaded -- while the server showed them as `open` for four
 * days with no PPG staged at all.
 *
 * A false negative here means a recording is silently never uploaded, so the
 * selection rule is tested directly rather than left to on-device observation.
 * (It cannot be verified end-to-end on a real phone without actually
 * re-uploading, and re-completing an already-uploaded session appends its PPG
 * rows a second time -- convert_session runs with append=True -- so a "harmless"
 * live test would duplicate real data.)
 */

package com.polarppgbp.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SyncSchedulerFindUnsyncedTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun bundle(name: String, manifest: Boolean, marker: Boolean): File {
        val d = tmp.newFolder(name)
        if (manifest) File(d, "manifest.json").writeText("""{"session_uuid":"$name"}""")
        if (marker) File(d, SyncWorker.MARKER).writeText("123")
        return d
    }

    @Test
    fun `a finalised bundle with no marker is selected`() {
        bundle("session-a", manifest = true, marker = false)
        val found = SyncScheduler.findUnsynced(tmp.root)
        assertEquals(listOf("session-a"), found.map { it.name })
    }

    @Test
    fun `an already-synced bundle is skipped`() {
        bundle("session-a", manifest = true, marker = true)
        assertTrue(SyncScheduler.findUnsynced(tmp.root).isEmpty())
    }

    @Test
    fun `a dir without a manifest is skipped`() {
        // Either still being written, or abandoned mid-recording. Nothing
        // coherent to upload, and uploading a partial bundle would stage data
        // the server cannot convert.
        bundle("session-a", manifest = false, marker = false)
        assertTrue(SyncScheduler.findUnsynced(tmp.root).isEmpty())
    }

    @Test
    fun `only the unsynced ones are selected from a mixed set`() {
        bundle("session-a", manifest = true, marker = true)
        bundle("session-b", manifest = true, marker = false)
        bundle("session-c", manifest = true, marker = true)
        bundle("session-d", manifest = true, marker = false)
        bundle("session-e", manifest = false, marker = false)

        val found = SyncScheduler.findUnsynced(tmp.root).map { it.name }
        assertEquals(listOf("session-b", "session-d"), found)
    }

    @Test
    fun `a missing root is not an error`() {
        // The sweep runs on every app start, including a fresh install where no
        // recording has happened yet and the sessions dir does not exist.
        val absent = File(tmp.root, "does-not-exist")
        assertTrue(SyncScheduler.findUnsynced(absent).isEmpty())
    }

    @Test
    fun `a loose file in the root is ignored`() {
        File(tmp.root, "stray.txt").writeText("x")
        assertTrue(SyncScheduler.findUnsynced(tmp.root).isEmpty())
    }
}
