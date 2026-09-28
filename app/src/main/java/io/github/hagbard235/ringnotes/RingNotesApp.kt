package io.github.hagbard235.ringnotes

import android.app.Application
import android.content.Context
import io.github.hagbard235.ringnotes.transcription.TranscriptionManager

class RingNotesApp : Application() {
    val controller: RingController by lazy {
        RingController(this).also { c -> c.onRecordingSaved = { transcriptions.enqueue(it) } }
    }

    val transcriptions: TranscriptionManager by lazy {
        TranscriptionManager(this, controller.store) { controller.refreshRecordings() }
    }
}

val Context.transcriptions: TranscriptionManager
    get() = (applicationContext as RingNotesApp).transcriptions

val Context.ringController: RingController
    get() = (applicationContext as RingNotesApp).controller
