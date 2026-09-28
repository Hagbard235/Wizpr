package io.github.hagbard235.ringnotes.recording

import android.content.Context
import io.github.hagbard235.ringnotes.core.WizprBle
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Recording(
    val file: File,
    val createdAt: Long,
    val durationMs: Long,
    /** Recognized text; null until transcribed, empty when no speech was recognized. */
    val transcript: String?,
) {
    val name: String get() = file.nameWithoutExtension
}

/** WAV files in the app's private `recordings/` directory (shared via FileProvider). */
class RecordingStore(context: Context) {
    val directory: File = File(context.filesDir, "recordings").apply { mkdirs() }

    fun newFile(now: Long = System.currentTimeMillis()): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        var file = File(directory, "ring-$stamp.wav")
        var n = 2
        while (file.exists()) file = File(directory, "ring-$stamp-${n++}.wav")
        return file
    }

    fun list(): List<Recording> =
        directory.listFiles { f -> f.isFile && f.extension == "wav" }
            .orEmpty()
            .map { Recording(it, it.lastModified(), durationOf(it), transcriptFile(it).takeIf(File::exists)?.readText()) }
            .sortedByDescending { it.createdAt }

    fun delete(recording: Recording) {
        recording.file.delete()
        transcriptFile(recording.file).delete()
    }

    fun saveTranscript(wav: File, text: String) {
        transcriptFile(wav).writeText(text)
    }

    private fun transcriptFile(wav: File) = File(wav.parentFile, wav.nameWithoutExtension + ".txt")

    private fun durationOf(file: File): Long =
        ((file.length() - WAV_HEADER_BYTES).coerceAtLeast(0) / 2) * 1000 / WizprBle.SAMPLE_RATE_HZ

    private companion object {
        const val WAV_HEADER_BYTES = 44L
    }
}
