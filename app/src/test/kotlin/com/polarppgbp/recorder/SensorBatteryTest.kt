package com.polarppgbp.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensorBatteryTest {

    @Test
    fun `unknown until the sensor reports a level`() {
        assertEquals(BatteryHealth.UNKNOWN, SensorBattery.healthOf(null))
        assertNull(SensorBattery.readout(null, ChargeStatus.UNKNOWN))
        assertNull(SensorBattery.warning(null, ChargeStatus.UNKNOWN))
    }

    @Test
    fun `a healthy level warrants a readout but no warning`() {
        assertEquals(BatteryHealth.OK, SensorBattery.healthOf(78))
        assertEquals("BAT 78%", SensorBattery.readout(78, ChargeStatus.DISCHARGING))
        assertNull(SensorBattery.warning(78, ChargeStatus.DISCHARGING))
    }

    @Test
    fun `thresholds are inclusive at the boundary`() {
        assertEquals(BatteryHealth.OK, SensorBattery.healthOf(21))
        assertEquals(BatteryHealth.LOW, SensorBattery.healthOf(SensorBattery.LOW_PERCENT))
        assertEquals(BatteryHealth.LOW, SensorBattery.healthOf(11))
        assertEquals(BatteryHealth.CRITICAL, SensorBattery.healthOf(SensorBattery.CRITICAL_PERCENT))
        assertEquals(BatteryHealth.CRITICAL, SensorBattery.healthOf(0))
    }

    @Test
    fun `low and critical warnings differ in urgency`() {
        val low = SensorBattery.warning(15, ChargeStatus.DISCHARGING)
        val critical = SensorBattery.warning(5, ChargeStatus.DISCHARGING)
        assertEquals("Sensor battery at 15%. Charge it tonight.", low)
        assertEquals("Sensor battery at 5%. Charge it before the next recording.", critical)
    }

    /**
     * Telling someone to charge a sensor that is already on the charger is how users
     * learn to ignore warnings.
     */
    @Test
    fun `charging suppresses the warning at any level`() {
        assertNull(SensorBattery.warning(5, ChargeStatus.CHARGING))
        assertNull(SensorBattery.warning(15, ChargeStatus.CHARGING))
        assertEquals("BAT 5% (charging)", SensorBattery.readout(5, ChargeStatus.CHARGING))
    }

    @Test
    fun `unknown charge state is treated as not charging`() {
        assertEquals(
            "Sensor battery at 8%. Charge it before the next recording.",
            SensorBattery.warning(8, ChargeStatus.UNKNOWN),
        )
        assertEquals("BAT 8%", SensorBattery.readout(8, ChargeStatus.UNKNOWN))
    }
}
