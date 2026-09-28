package io.github.hagbard235.ringnotes

import android.app.Application
import android.content.Context
import io.github.hagbard235.ringnotes.ai.AiSettings
import io.github.hagbard235.ringnotes.feedback.Feedback
import io.github.hagbard235.ringnotes.symcon.SymconJobs
import io.github.hagbard235.ringnotes.transcription.TranscriptionManager

class RingNotesApp : Application() {
    val controller: RingController by lazy {
        RingController(this).also { c -> c.onRecordingSaved = { transcriptions.enqueue(it) } }
    }

    val aiSettings: AiSettings by lazy { AiSettings(this) }

    val feedback: Feedback by lazy { Feedback(this) }

    val symconJobs: SymconJobs by lazy { SymconJobs(this, controller.store.directory, aiSettings, feedback) }

    val transcriptions: TranscriptionManager by lazy {
        TranscriptionManager(this, controller.store, aiSettings, symconJobs) { controller.refreshRecordings() }
    }
}

val Context.transcriptions: TranscriptionManager
    get() = (applicationContext as RingNotesApp).transcriptions

val Context.aiSettings: AiSettings
    get() = (applicationContext as RingNotesApp).aiSettings

val Context.symconJobs: SymconJobs
    get() = (applicationContext as RingNotesApp).symconJobs

val Context.ringController: RingController
    get() = (applicationContext as RingNotesApp).controller
