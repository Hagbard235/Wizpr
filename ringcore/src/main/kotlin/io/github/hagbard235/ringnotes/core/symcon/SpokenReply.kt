package io.github.hagbard235.ringnotes.core.symcon

/** Builds the text read aloud for a smart-home reply. */
object SpokenReply {
    /** More options than this are not read out; they are too many to take in by ear. */
    const val MAX_SPOKEN_OPTIONS = 3

    /**
     * The reply's message, plus up to [MAX_SPOKEN_OPTIONS] clarification options
     * ("Zur Auswahl: Sternenhimmel, Treppenhaus oder Flurlicht."), unless the message
     * already names all of them.
     */
    fun text(response: SymconResponse): String {
        val message = response.message.trim()
        val options = response.clarification?.options.orEmpty()
        if (options.isEmpty() || options.size > MAX_SPOKEN_OPTIONS) return message
        val lower = message.lowercase()
        if (options.all { lower.contains(it.label.lowercase()) }) return message
        val labels = options.map { it.label }
        val list = if (labels.size == 1) labels.single() else labels.dropLast(1).joinToString(", ") + " oder " + labels.last()
        val separator = if (message.isEmpty() || message.endsWith('.') || message.endsWith('?') || message.endsWith('!')) " " else ". "
        return (message + separator + "Zur Auswahl: $list.").trim()
    }

    /**
     * An answer to a status question ("Wie warm ist es im Wohnzimmer?"): completed but
     * without switching actions. The contract has no field for this; for status queries
     * `actions` is empty, and the answer is in `message`.
     */
    fun isInformation(response: SymconResponse): Boolean =
        response.status == SymconProtocol.COMPLETED && response.actions.isEmpty() && response.message.isNotBlank()
}
