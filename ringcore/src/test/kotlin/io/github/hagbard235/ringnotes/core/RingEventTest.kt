package io.github.hagbard235.ringnotes.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RingEventTest {
    @Test
    fun parsesSimpleKeywords() {
        assertEquals(RingEvent.MicOn, parseOperation("MIC_ON"))
        assertEquals(RingEvent.MicOff, parseOperation("MIC_OFF"))
        assertEquals(RingEvent.PowerOff, parseOperation("POWER_OFF"))
    }

    private fun battery(text: String) = assertIs<RingEvent.BatteryUpdate>(parseOperation(text))

    @Test
    fun batteryFullVoltageIs100() {
        val b = battery("BATT=4.2")
        assertEquals(4.2f, b.voltage, 1e-3f)
        assertEquals(100, b.level)
    }

    @Test
    fun batteryTableVoltage() {
        assertEquals(25, battery("BATT=3.350").level)
        assertEquals(4, battery("BATT=3.0").level)
        assertEquals(0, battery("BATT=2.5").level)
    }

    @Test
    fun parsesBatteryPercentVoltageFormat() {
        val b = battery("BATTERY 75(3.524322)")
        assertEquals(3.524322f, b.voltage, 1e-3f)
        assertEquals(55, b.level)
    }

    @Test
    fun parsesBattWithTrailingText() {
        assertEquals(55, battery("BATT=3.524322 OK").level)
    }

    @Test
    fun malformedAndUnknownStringsAreRawOperations() {
        assertEquals(RingEvent.Operation("BATT=unknown"), parseOperation("BATT=unknown"))
        assertEquals(RingEvent.Operation("FUTURE_CODE"), parseOperation("FUTURE_CODE"))
    }
}

class CandidateTest {
    @Test
    fun matchesServiceOrName() {
        kotlin.test.assertTrue(isRingCandidate(null, listOf(WizprBle.SERVICE)))
        kotlin.test.assertTrue(isRingCandidate("Wizpr Ring-A1:B2", emptyList()))
        kotlin.test.assertFalse(isRingCandidate("Headphones", emptyList()))
        kotlin.test.assertFalse(isRingCandidate(null, emptyList()))
    }
}
