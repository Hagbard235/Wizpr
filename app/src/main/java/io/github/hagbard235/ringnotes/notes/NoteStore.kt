package io.github.hagbard235.ringnotes.notes

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.hagbard235.ringnotes.core.NoteCommand
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class Note(
    val id: String,
    val createdAt: Long,
    val text: String,
    /** Name of the recording it came from, if any. */
    val recording: String?,
    /** Handed to Google Keep (or another app) at least once. */
    val shared: Boolean,
)

/** Notes to self, kept in `notes.json` in the app's private storage. Thread-safe. */
class NoteStore(context: Context) {
    private val file = File(context.filesDir, "notes.json")
    private val prefs = context.getSharedPreferences("notes", Context.MODE_PRIVATE)
    private val lock = Any()

    private val _notes = MutableStateFlow(load())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    private val _triggers = MutableStateFlow(
        prefs.getString(KEY_TRIGGERS, null)?.let(NoteCommand::parseTriggerList)?.takeIf { it.isNotEmpty() }
            ?: NoteCommand.DEFAULT_TRIGGERS,
    )
    val triggers: StateFlow<List<String>> = _triggers.asStateFlow()

    /** Returns the note text if [transcript] is a note-to-self command. */
    fun match(transcript: String): String? = NoteCommand.parse(transcript, _triggers.value)

    fun setTriggers(input: String) {
        val list = NoteCommand.parseTriggerList(input)
        prefs.edit().putString(KEY_TRIGGERS, list.joinToString(", ")).apply()
        _triggers.value = list.ifEmpty { NoteCommand.DEFAULT_TRIGGERS }
    }

    fun add(text: String, recording: String?): Note {
        val note = Note(UUID.randomUUID().toString(), System.currentTimeMillis(), text, recording, shared = false)
        update { listOf(note) + it }
        return note
    }

    fun delete(id: String) = update { list -> list.filterNot { it.id == id } }

    fun markShared(ids: Collection<String>) = update { list -> list.map { if (it.id in ids) it.copy(shared = true) else it } }

    fun find(id: String): Note? = _notes.value.firstOrNull { it.id == id }

    private fun update(transform: (List<Note>) -> List<Note>) {
        synchronized(lock) {
            val next = transform(_notes.value)
            save(next)
            _notes.value = next
        }
    }

    private fun load(): List<Note> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Note(
                    id = o.getString("id"),
                    createdAt = o.getLong("createdAt"),
                    text = o.getString("text"),
                    recording = o.optString("recording").ifEmpty { null },
                    shared = o.optBoolean("shared", false),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot read notes", e)
            emptyList()
        }
    }

    private fun save(notes: List<Note>) {
        val array = JSONArray()
        notes.forEach { n ->
            array.put(
                JSONObject()
                    .put("id", n.id)
                    .put("createdAt", n.createdAt)
                    .put("text", n.text)
                    .put("recording", n.recording ?: "")
                    .put("shared", n.shared),
            )
        }
        // Write-then-rename so a crash never leaves a half-written notes file.
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(file)
    }

    companion object {
        private const val TAG = "NoteStore"
        private const val KEY_TRIGGERS = "triggers"
        const val KEEP_PACKAGE = "com.google.android.keep"

        /**
         * Hand notes to Google Keep; falls back to the share sheet when Keep is not
         * installed. Several notes become one text with their times.
         */
        fun shareToKeep(context: Context, notes: List<Note>): Boolean {
            if (notes.isEmpty()) return false
            val text = if (notes.size == 1) {
                notes.single().text
            } else {
                val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                notes.sortedBy { it.createdAt }.joinToString("\n\n") { "${format.format(Date(it.createdAt))}\n${it.text}" }
            }
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
                .putExtra(Intent.EXTRA_SUBJECT, if (notes.size == 1) "Notiz" else "Notizen (${notes.size})")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(Intent(send).setPackage(KEEP_PACKAGE))
                true
            } catch (e: ActivityNotFoundException) {
                context.startActivity(Intent.createChooser(send, "Notiz teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }
        }
    }
}
