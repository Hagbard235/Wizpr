package io.github.hagbard235.ringnotes.recording

import android.media.MediaPlayer
import android.util.Log
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Plays one recording at a time; [playing] is the file currently playing. Main thread only. */
class AudioPlayer {
    private var player: MediaPlayer? = null
    private val _playing = MutableStateFlow<File?>(null)
    val playing: StateFlow<File?> = _playing.asStateFlow()

    fun toggle(file: File) {
        val wasPlaying = _playing.value
        stop()
        if (wasPlaying == file) return
        try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
            _playing.value = file
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Cannot play $file", e)
            stop()
        }
    }

    fun stop() {
        player?.release()
        player = null
        _playing.value = null
    }
}
