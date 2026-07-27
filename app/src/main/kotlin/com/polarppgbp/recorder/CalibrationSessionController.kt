package com.polarppgbp.recorder

import android.content.Context
import com.polarppgbp.SharedRepo
import com.polarppgbp.settings.SettingsStore

/**
 * Start/stop a calibration-session marker.
 *
 * Lives outside the ViewModel so the Settings UI and the debug broadcast harness
 * drive exactly the same code. A test harness that reimplements the behaviour it is
 * meant to be testing verifies nothing.
 */
class CalibrationSessionController(context: Context) {

    private val store = SettingsStore(context)

    data class Outcome(val ok: Boolean, val message: String)

    fun isOpen(): Boolean = store.isCalibrationOpen()

    fun name(): String? = store.calibrationName()

    fun tags(): String? = store.calibrationTags()

    fun startedAtMs(): Long = store.calibrationStartedAt()

    /**
     * Requires an active recording, because the marker is stored as a note inside the
     * session bundle and has nowhere else to go. Refusing loudly beats accepting a
     * marker that silently vanishes, which would leave the paper log and the data
     * disagreeing with nothing to show why.
     */
    fun start(name: String?, tags: String?): Outcome {
        if (isOpen()) return Outcome(false, "A calibration session is already open.")
        val repo = SharedRepo.repo
        if (repo == null || !repo.recording.value) {
            return Outcome(
                false,
                "Start a recording first — the marker is stored in the session.",
            )
        }
        if (!repo.appendNote(CalibrationMarker.startPayload(name, tags))) {
            return Outcome(false, "Could not write the marker. Is a recording running?")
        }
        store.openCalibration(name, tags, System.currentTimeMillis())
        return Outcome(true, "Calibration session started.")
    }

    /**
     * Closes the marker whether or not the note could be written. If the recording
     * ended before the user got here, refusing to close would leave the session
     * permanently open and make every later run look like a continuation of it.
     */
    fun stop(name: String?, tags: String?): Outcome {
        if (!isOpen()) return Outcome(false, "No calibration session is open.")
        val wrote = SharedRepo.repo
            ?.appendNote(CalibrationMarker.stopPayload(name, tags))
            ?: false
        store.closeCalibration()
        return if (wrote) {
            Outcome(true, "Calibration session stopped.")
        } else {
            Outcome(
                false,
                "Session closed, but no recording was running so no stop marker was written.",
            )
        }
    }
}
