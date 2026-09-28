// Ported from wizpr-ring (connection.rs, DispatchState) of https://github.com/vtouchio/wizpr-ring-sdk
// Copyright VTouch Inc., licensed under Apache-2.0. Modified: translated to Kotlin.
package io.github.hagbard235.ringnotes.core

import java.util.UUID

/**
 * Turns raw GATT notifications into decoded audio and [RingEvent]s.
 *
 * Pure state machine without timers or threads: the caller arms a
 * [DOUBLE_CLICK_WINDOW_MS] timer when [Output.clickTimer] says [ClickTimer.ARM]
 * and calls [onClickTimeout] when it fires. Not thread-safe; feed it from a
 * single thread.
 */
class NotificationDispatcher {
    private val decoder = ImaAdpcmDecoder()
    private var clickCount = 0

    enum class ClickTimer { NONE, ARM, DISARM }

    data class Output(
        val audio: ShortArray? = null,
        val events: List<RingEvent> = emptyList(),
        val clickTimer: ClickTimer = ClickTimer.NONE,
    )

    fun handle(uuid: UUID, value: ByteArray): Output = when (uuid) {
        WizprBle.AUDIO_CHAR -> Output(audio = decoder.decode(value))
        WizprBle.TRANSFER_STATUS_CHAR -> handleTransferStatus(value)
        WizprBle.OPERATION_CHAR -> handleOperation(value)
        else -> Output()
    }

    private fun handleTransferStatus(value: ByteArray): Output {
        val event = when (value.firstOrNull()) {
            TransferStatus.START -> {
                decoder.reset()
                RingEvent.RecordingStarted
            }
            TransferStatus.STOP -> RingEvent.RecordingStopped
            else -> return Output()
        }
        return Output(events = listOf(event))
    }

    private fun handleOperation(value: ByteArray): Output {
        val text = String(value, Charsets.UTF_8)
        if ("CLICK" in text) return handleClick()
        return Output(events = listOf(parseOperation(text)))
    }

    private fun handleClick(): Output {
        clickCount++
        return if (clickCount >= 2) {
            clickCount = 0
            Output(events = listOf(RingEvent.DoubleClick), clickTimer = ClickTimer.DISARM)
        } else {
            Output(clickTimer = ClickTimer.ARM)
        }
    }

    /** Call when the double-click window armed by [ClickTimer.ARM] elapses. */
    fun onClickTimeout(): RingEvent? {
        val event = if (clickCount == 1) RingEvent.Click else null
        clickCount = 0
        return event
    }

    /** Test hook: the decoder's current step index. */
    internal val decoderStepIndex: Int get() = decoder.stepIndex

    companion object {
        const val DOUBLE_CLICK_WINDOW_MS = 1_000L
    }
}
