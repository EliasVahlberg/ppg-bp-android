package com.polarppgbp.recorder

/**
 * Charge state as reported by the sensor, mapped off the SDK's own enum (#14).
 *
 * Duplicated rather than referenced so this file stays free of Polar SDK imports and
 * can be unit-tested on the JVM. The mapping lives in [PolarRepository].
 */
enum class ChargeStatus { UNKNOWN, CHARGING, DISCHARGING }

/** How the battery reading should be presented, in increasing severity. */
enum class BatteryHealth { UNKNOWN, OK, LOW, CRITICAL }

/**
 * Sensor battery presentation (#14).
 *
 * A dead sensor and a sensor that was never put on look identical from the app: no
 * samples arrive either way. The battery reading is the cheapest way to tell those
 * apart before a recording rather than after it, which matters most for an unattended
 * deployment where nobody is watching the counters.
 *
 * Thresholds are deliberately generous. The Verity Sense runs roughly 20 hours while
 * streaming, so 20% is still hours of recording -- LOW is not "stop", it's "charge it
 * tonight rather than being surprised tomorrow". CRITICAL at 10% is the point where a
 * long session is likely to end early.
 */
object SensorBattery {

    const val LOW_PERCENT = 20
    const val CRITICAL_PERCENT = 10

    fun healthOf(percent: Int?): BatteryHealth = when {
        percent == null -> BatteryHealth.UNKNOWN
        percent <= CRITICAL_PERCENT -> BatteryHealth.CRITICAL
        percent <= LOW_PERCENT -> BatteryHealth.LOW
        else -> BatteryHealth.OK
    }

    /**
     * Short readout for the main screen, e.g. `"BAT 78%"`, or null when there is
     * nothing to say.
     *
     * Returns null rather than a placeholder when the level is unknown: the sensor
     * reports battery once shortly after connecting, so a "BAT --" that appears for a
     * second on every connection is noise, and a stale value after disconnect is worse
     * than no value.
     */
    fun readout(percent: Int?, charge: ChargeStatus): String? {
        if (percent == null) return null
        val suffix = if (charge == ChargeStatus.CHARGING) " (charging)" else ""
        return "BAT $percent%$suffix"
    }

    /**
     * Warning text, or null when no warning is warranted.
     *
     * Charging suppresses the low warning. A sensor on the charger reading 8% is doing
     * exactly what it should, and telling the user to charge something that is already
     * charging trains them to ignore warnings.
     */
    fun warning(percent: Int?, charge: ChargeStatus): String? {
        if (charge == ChargeStatus.CHARGING) return null
        return when (healthOf(percent)) {
            BatteryHealth.CRITICAL ->
                "Sensor battery at $percent%. Charge it before the next recording."
            BatteryHealth.LOW ->
                "Sensor battery at $percent%. Charge it tonight."
            else -> null
        }
    }
}
