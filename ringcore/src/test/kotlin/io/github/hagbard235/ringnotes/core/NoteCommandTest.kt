package io.github.hagbard235.ringnotes.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NoteCommandTest {
    @Test
    fun recognizesTriggerAtStart() {
        assertEquals("Milch kaufen", NoteCommand.parse("Notiz an mich selbst: Milch kaufen"))
        assertEquals("Milch kaufen", NoteCommand.parse("notiz an mich selbst milch kaufen"))
        // The note keeps its own punctuation.
        assertEquals("Reifen wechseln.", NoteCommand.parse("Merk dir, Reifen wechseln."))
        assertEquals("Anruf bei Oma", NoteCommand.parse("Notiz: Anruf bei Oma"))
    }

    @Test
    fun longestTriggerWins() {
        // Must not leave "an mich selbst" in the note.
        assertEquals("Paket abholen", NoteCommand.parse("Notiz an mich selbst Paket abholen"))
        assertEquals("Paket abholen", NoteCommand.parse("Notiz an mich, Paket abholen"))
    }

    @Test
    fun toleratesPunctuationInsideTrigger() {
        assertEquals("Zahnarzt anrufen", NoteCommand.parse("Notiz, an mich selbst. Zahnarzt anrufen"))
    }

    @Test
    fun requiresWordBoundaryAndStartPosition() {
        assertNull(NoteCommand.parse("Notizbuch kaufen"))
        assertNull(NoteCommand.parse("Schalte das Licht aus, Notiz an mich selbst"))
        assertNull(NoteCommand.parse("Merkel hat gesagt"))
    }

    @Test
    fun emptyBodyIsNoNote() {
        assertNull(NoteCommand.parse("Notiz an mich selbst."))
        assertNull(NoteCommand.parse(""))
    }

    @Test
    fun customTriggers() {
        val triggers = NoteCommand.parseTriggerList("Erinnerung, Gedanke;\nTodo")
        assertEquals(listOf("Erinnerung", "Gedanke", "Todo"), triggers)
        assertEquals("Steuer machen", NoteCommand.parse("todo steuer machen", triggers))
        assertNull(NoteCommand.parse("Notiz Milch", triggers))
    }
}
