/*
 * Unit tests for the pure (no-Android) parts of the settings model: profile
 * resolution and defaults. SettingsStore itself wraps SharedPreferences,
 * which needs a real (or Robolectric) Context — not pulling in Robolectric
 * for this yet, so SettingsStore's persistence round-trip is instead covered
 * by the device-in-the-loop e2e suite (tests_e2e/), which exercises it via
 * the real app over adb.
 *
 * Pure JVM (no Android deps); runs under testDebugUnitTest.
 */

package com.polarppgbp.settings

import com.polarppgbp.recorder.Profile
import com.polarppgbp.rop.SensorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSettingsTest {

    @Test
    fun defaultResolvesToCalibrationProfile() {
        val resolved = RecordingSettings.DEFAULT.toProfile()
        assertEquals(Profile.CALIBRATION.rates, resolved.rates)
        assertEquals(ProfileChoice.CALIBRATION, RecordingSettings.DEFAULT.profileChoice)
    }

    @Test
    fun calibrationChoiceIgnoresCustomRates() {
        // Even if stale custom_* values are sitting in prefs from a previous
        // Custom session, selecting Calibration must resolve to the fixed
        // Calibration rates, not the stale custom ones.
        val settings = RecordingSettings(
            profileChoice = ProfileChoice.CALIBRATION,
            customPpgHz = 999,
            customAccHz = 999,
            customGyroHz = 999,
        )
        assertEquals(Profile.CALIBRATION.rates, settings.toProfile().rates)
    }

    @Test
    fun monitorChoiceResolvesToMonitorProfile() {
        val settings = RecordingSettings(
            profileChoice = ProfileChoice.MONITOR,
            customPpgHz = 176,
            customAccHz = 416,
            customGyroHz = 416,
        )
        assertEquals(Profile.MONITOR.rates, settings.toProfile().rates)
    }

    @Test
    fun customChoiceUsesStoredRates() {
        val settings = RecordingSettings(
            profileChoice = ProfileChoice.CUSTOM,
            customPpgHz = 135,
            customAccHz = 200,
            customGyroHz = 104,
        )
        val resolved = settings.toProfile()
        assertEquals("custom", resolved.name)
        assertEquals(135, resolved.rates[SensorType.PPG])
        assertEquals(200, resolved.rates[SensorType.ACC])
        assertEquals(104, resolved.rates[SensorType.GYRO])
    }

    @Test
    fun profileChoiceFromStorageNameRoundTrips() {
        for (choice in ProfileChoice.entries) {
            assertEquals(choice, ProfileChoice.fromStorageName(choice.storageName))
        }
    }

    @Test
    fun profileChoiceFromUnknownOrNullNameFallsBackToDefault() {
        assertEquals(ProfileChoice.DEFAULT, ProfileChoice.fromStorageName(null))
        assertEquals(ProfileChoice.DEFAULT, ProfileChoice.fromStorageName("bogus"))
    }

    @Test
    fun supportedRatesAreNonEmptyForEveryStreamedSensor() {
        // MAG/PPI aren't part of the settings UI yet, so only assert PPG/ACC/GYRO.
        assertTrue(SupportedRates.forSensor(SensorType.PPG).isNotEmpty())
        assertTrue(SupportedRates.forSensor(SensorType.ACC).isNotEmpty())
        assertTrue(SupportedRates.forSensor(SensorType.GYRO).isNotEmpty())
    }

    @Test
    fun defaultCustomRatesAreWithinSupportedCandidates() {
        // The DEFAULT custom_* seed values (mirroring Calibration) must
        // actually appear in the candidate lists a user would pick from,
        // otherwise switching to Custom right after Calibration would show
        // a rate that isn't one of the dropdown options.
        assertTrue(RecordingSettings.DEFAULT.customPpgHz in SupportedRates.PPG)
        assertTrue(RecordingSettings.DEFAULT.customAccHz in SupportedRates.ACC)
        assertTrue(RecordingSettings.DEFAULT.customGyroHz in SupportedRates.GYRO)
    }
}
