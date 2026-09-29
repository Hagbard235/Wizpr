package io.github.hagbard235.ringnotes

import android.app.Application
import android.content.Context
import io.github.hagbard235.ringnotes.ai.AiSettings
import io.github.hagbard235.ringnotes.feedback.Feedback
import io.github.hagbard235.ringnotes.notes.NoteStore
import io.github.hagbard235.ringnotes.phone.PhoneRecorder
import io.github.hagbard235.ringnotes.phone.PushToTalk
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
        TranscriptionManager(this, controller.store, aiSettings, symconJobs, noteStore, feedback) {
            controller.refreshRecordings()
        }
    }

    val noteStore: NoteStore by lazy { NoteStore(this) }

    val phoneRecorder: PhoneRecorder by lazy {
        PhoneRecorder(
            context = this,
            store = controller.store,
            onSaved = { file ->
                controller.logNote("Handy-Aufnahme gespeichert: ${file.name}")
                controller.refreshRecordings()
                transcriptions.enqueue(file)
            },
            onSilent = {
                controller.logNote("Handy-Aufnahme verworfen: nur Stille (Mikrofon im Hintergrund gesperrt?)")
                feedback.tone(Feedback.Tone.ERROR)
            },
        )
    }

    val pushToTalk: PushToTalk by lazy { PushToTalk(this, phoneRecorder, feedback) }
}

val Context.noteStore: NoteStore
    get() = (applicationContext as RingNotesApp).noteStore

val Context.pushToTalk: PushToTalk
    get() = (applicationContext as RingNotesApp).pushToTalk

val Context.phoneRecorder: PhoneRecorder
    get() = (applicationContext as RingNotesApp).phoneRecorder

val Context.transcriptions: TranscriptionManager
    get() = (applicationContext as RingNotesApp).transcriptions

val Context.aiSettings: AiSettings
    get() = (applicationContext as RingNotesApp).aiSettings

val Context.symconJobs: SymconJobs
    get() = (applicationContext as RingNotesApp).symconJobs

val Context.ringController: RingController
    get() = (applicationContext as RingNotesApp).controller
