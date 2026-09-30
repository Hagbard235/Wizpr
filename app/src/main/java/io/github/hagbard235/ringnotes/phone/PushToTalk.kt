package io.github.hagbard235.ringnotes.phone

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.KeyEvent
import io.github.hagbard235.ringnotes.feedback.Feedback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hold volume-down to talk. Key events come from the activity (app in front) or
 * from [VolumeKeyService] (anywhere, screen on). Volume-down is consumed so the
 * volume does not change while talking; a short press is turned back into a
 * normal "volume down" so the key keeps working as usual.
 */
class PushToTalk(
    private val context: Context,
    private val recorder: PhoneRecorder,
    private val feedback: Feedback,
) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("phone", Context.MODE_PRIVATE)
    private var keyDown = false
    private var talking = false

    private val _volumeKeyEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val volumeKeyEnabled: StateFlow<Boolean> = _volumeKeyEnabled.asStateFlow()

    /** Recording was started right at key-down (so the first words are not lost). */
    private var keyRecording = false
    private var held = false

    /** Fires once the key has been held long enough: from now on it is push-to-talk, not volume-down. */
    private val confirmHold = Runnable {
        if (!keyDown) return@Runnable
        held = true
        if (keyRecording) vibrate(START_MS) else feedback.tone(Feedback.Tone.ERROR)
    }

    fun setVolumeKeyEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _volumeKeyEnabled.value = enabled
    }

    /** Whether the accessibility service for use outside the app is switched on. */
    fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(context, VolumeKeyService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** Returns true when the event was consumed. Must be called on the main thread. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN || !_volumeKeyEnabled.value) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0 && !keyDown) {
                    keyDown = true
                    held = false
                    // Start recording immediately; a short press discards it again.
                    keyRecording = recorder.start()
                    main.postDelayed(confirmHold, HOLD_MS)
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                main.removeCallbacks(confirmHold)
                if (held) {
                    if (keyRecording) end()
                } else if (keyDown) {
                    if (keyRecording) recorder.cancel()
                    // Short press: behave like a normal volume-down.
                    context.getSystemService(AudioManager::class.java).adjustSuggestedStreamVolume(
                        AudioManager.ADJUST_LOWER, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI,
                    )
                }
                keyDown = false
                held = false
                keyRecording = false
                return true
            }
        }
        return false
    }

    /** For the on-screen hold button. */
    fun pressStart(): Boolean = begin().also { talking = it }

    fun pressEnd() {
        if (talking) end()
        talking = false
    }

    private fun begin(): Boolean {
        val started = recorder.start()
        if (started) vibrate(START_MS) else feedback.tone(Feedback.Tone.ERROR)
        return started
    }

    private fun end() {
        recorder.stop()
        vibrate(STOP_MS)
    }

    @Suppress("DEPRECATION")
    private fun vibrate(ms: Long) {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private companion object {
        const val KEY_ENABLED = "volumeKeyPushToTalk"
        const val HOLD_MS = 400L
        const val START_MS = 40L
        const val STOP_MS = 25L
    }
}
