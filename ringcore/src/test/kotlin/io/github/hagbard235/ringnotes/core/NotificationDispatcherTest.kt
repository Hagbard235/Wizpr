package io.github.hagbard235.ringnotes.core

import io.github.hagbard235.ringnotes.core.NotificationDispatcher.ClickTimer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationDispatcherTest {
    @Test
    fun audioNotificationDecodesPcm() {
        val out = NotificationDispatcher().handle(WizprBle.AUDIO_CHAR, byteArrayOf(0x12, 0x34))
        assertEquals(4, out.audio?.size)
        assertTrue(out.events.isEmpty())
    }

    @Test
    fun transferStartResetsCodecAndEmitsEvent() {
        val d = NotificationDispatcher()
        d.handle(WizprBle.AUDIO_CHAR, ByteArray(4) { 0x77 })
        assertNotEquals(0, d.decoderStepIndex)
        val out = d.handle(WizprBle.TRANSFER_STATUS_CHAR, byteArrayOf(TransferStatus.START))
        assertEquals(listOf<RingEvent>(RingEvent.RecordingStarted), out.events)
        assertEquals(0, d.decoderStepIndex)
    }

    @Test
    fun transferStopEmitsEventAndUnknownIsIgnored() {
        val d = NotificationDispatcher()
        assertEquals(
            listOf<RingEvent>(RingEvent.RecordingStopped),
            d.handle(WizprBle.TRANSFER_STATUS_CHAR, byteArrayOf(TransferStatus.STOP)).events,
        )
        assertEquals(NotificationDispatcher.Output(), d.handle(WizprBle.TRANSFER_STATUS_CHAR, "x".toByteArray()))
    }

    @Test
    fun operationUsesParser() {
        val out = NotificationDispatcher().handle(WizprBle.OPERATION_CHAR, "MIC_ON".toByteArray())
        assertEquals(listOf<RingEvent>(RingEvent.MicOn), out.events)
    }

    @Test
    fun firstClickArmsTimerAndTimeoutEmitsSingleClick() {
        val d = NotificationDispatcher()
        val out = d.handle(WizprBle.OPERATION_CHAR, "CLICK".toByteArray())
        assertTrue(out.events.isEmpty())
        assertEquals(ClickTimer.ARM, out.clickTimer)
        assertEquals(RingEvent.Click, d.onClickTimeout())
        assertNull(d.onClickTimeout())
    }

    @Test
    fun secondClickEmitsDoubleClick() {
        val d = NotificationDispatcher()
        d.handle(WizprBle.OPERATION_CHAR, "CLICK".toByteArray())
        val second = d.handle(WizprBle.OPERATION_CHAR, "CLICK".toByteArray())
        assertEquals(listOf<RingEvent>(RingEvent.DoubleClick), second.events)
        assertEquals(ClickTimer.DISARM, second.clickTimer)
        assertNull(d.onClickTimeout())
    }

    @Test
    fun commandsStayAllowlisted() {
        assertEquals("sample_rate 16", OperationCommand.SAMPLE_RATE_16.wire)
        assertEquals("BATTERY", OperationCommand.BATTERY_STATUS.wire)
        assertEquals("LOCK", OperationCommand.LOCK.wire)
    }
}
