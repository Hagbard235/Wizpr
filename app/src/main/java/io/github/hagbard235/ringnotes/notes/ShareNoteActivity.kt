package io.github.hagbard235.ringnotes.notes

import android.app.Activity
import android.os.Bundle
import androidx.core.app.NotificationManagerCompat
import io.github.hagbard235.ringnotes.noteStore

/**
 * Invisible trampoline for the "An Keep" notification button: Android does not let
 * a notification action open another app directly from a broadcast, so this
 * activity hands the note to Keep and finishes immediately.
 */
class ShareNoteActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_NOTE_ID)
        val note = id?.let(noteStore::find)
        if (note != null && NoteStore.shareToKeep(this, listOf(note))) noteStore.markShared(listOf(note.id))
        NotificationManagerCompat.from(this).cancel(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
        finish()
    }

    companion object {
        const val EXTRA_NOTE_ID = "noteId"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
    }
}
