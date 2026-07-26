package com.polarppgbp.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #3: rotation period was a hardcoded 15; the bounds are what stop it becoming absurd. */
class RotationPeriodTest {

    @Test
    fun acceptsValuesInsideTheBounds() {
        assertEquals(1, RotationPeriod.parse("1"))
        assertEquals(15, RotationPeriod.parse("15"))
        assertEquals(120, RotationPeriod.parse("120"))
        assertEquals(30, RotationPeriod.parse("  30  "))
    }

    @Test
    fun rejectsOutOfRangeRatherThanClamping() {
        // Clamping would silently give the user a different setting than they asked for.
        assertNull(RotationPeriod.parse("0"))
        assertNull(RotationPeriod.parse("-5"))
        assertNull(RotationPeriod.parse("121"))
        assertNull(RotationPeriod.parse("1440"))
    }

    @Test
    fun rejectsNonNumericAndBlank() {
        assertNull(RotationPeriod.parse(""))
        assertNull(RotationPeriod.parse("   "))
        assertNull(RotationPeriod.parse("fifteen"))
        assertNull(RotationPeriod.parse("15min"))
        assertNull(RotationPeriod.parse("1.5"))
        assertNull(RotationPeriod.parse(null))
    }

    @Test
    fun errorMessagesDistinguishTheReason() {
        assertTrue(RotationPeriod.errorFor("").contains("Enter a rotation period"))
        assertTrue(RotationPeriod.errorFor("abc").contains("whole number"))
        assertTrue(RotationPeriod.errorFor("999").contains("between 1 and 120"))
    }

    @Test
    fun describeReadsNaturallyAtEachScale() {
        assertEquals("15 min per file", RotationPeriod.describe(15))
        assertEquals("1 h per file", RotationPeriod.describe(60))
        assertEquals("2 h per file", RotationPeriod.describe(120))
        assertEquals("1 h 30 min per file", RotationPeriod.describe(90))
    }

    @Test
    fun presetsAreAllValidAndIncludeTheDefault() {
        RotationPeriod.PRESETS.forEach {
            assertEquals("preset $it must be accepted", it, RotationPeriod.normalise(it))
        }
        assertTrue(RotationPeriod.PRESETS.contains(RotationPeriod.DEFAULT_MINUTES))
    }

    @Test
    fun defaultIsUnchangedFromTheHardcodedValue() {
        // Changing this silently would alter every future session's file layout.
        assertEquals(15, RotationPeriod.DEFAULT_MINUTES)
    }
}
