package io.github.hagbard235.ringnotes.core

/**
 * Local "note to self" command: a transcript that *starts* with one of the
 * trigger phrases is kept as a note on the device instead of being forwarded.
 */
object NoteCommand {
    val DEFAULT_TRIGGERS = listOf(
        "Notiz an mich selbst",
        "Notiz an mich",
        "Notiz für mich",
        "Notiz",
        "Merk dir",
        "Merke dir",
        "Merke",
    )

    /**
     * Returns the note text without the trigger, or null when [transcript] is not a
     * note. Only the beginning counts, and the trigger must end at a word boundary
     * ("Notizbuch kaufen" is no note). Longer triggers win over shorter ones.
     */
    fun parse(transcript: String, triggers: List<String> = DEFAULT_TRIGGERS): String? {
        val text = transcript.trimStart { it.isWhitespace() || it in LEADING_NOISE }
        val lower = text.lowercase()
        for (trigger in triggers.map { normalize(it) }.filter { it.isNotEmpty() }.sortedByDescending { it.length }) {
            val end = matchPrefix(lower, trigger) ?: continue
            if (end < lower.length && lower[end].isLetterOrDigit()) continue
            val body = text.substring(end).trimStart { it.isWhitespace() || it in SEPARATORS }.trimEnd()
            return body.takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() }
        }
        return null
    }

    /** Parses a comma- or newline-separated trigger list as typed in the settings. */
    fun parseTriggerList(input: String): List<String> =
        input.split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Matches [trigger] at the start of [text], treating any run of spaces and
     * punctuation in the text as one space ("Notiz, an mich" = "Notiz an mich").
     * Returns the end index in [text], or null.
     */
    private fun matchPrefix(text: String, trigger: String): Int? {
        var i = 0
        for (c in trigger) {
            if (c == ' ') {
                if (i >= text.length || !(text[i].isWhitespace() || text[i] in SEPARATORS)) return null
                while (i < text.length && (text[i].isWhitespace() || text[i] in SEPARATORS)) i++
            } else {
                if (i >= text.length || text[i] != c) return null
                i++
            }
        }
        return i
    }

    private fun normalize(trigger: String) =
        trigger.lowercase().trim().replace(Regex("[\\s,.:;!?-]+"), " ")

    private const val SEPARATORS = ",.:;!?-–—"
    private const val LEADING_NOISE = "\"'„“»«"
}
