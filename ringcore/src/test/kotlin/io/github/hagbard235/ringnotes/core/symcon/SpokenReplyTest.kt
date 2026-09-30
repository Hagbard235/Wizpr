package io.github.hagbard235.ringnotes.core.symcon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpokenReplyTest {
    private fun response(
        message: String,
        status: String = SymconProtocol.CLARIFICATION_REQUIRED,
        labels: List<String> = emptyList(),
        actions: List<SymconAction> = emptyList(),
    ) = SymconResponse(
        version = "1", requestId = "r", status = status, message = message, actions = actions, error = null,
        clarification = if (labels.isEmpty()) null else Clarification("c", null, labels.map { ClarificationOption(it.lowercase(), it) }, true),
        poll = null,
    )

    @Test
    fun readsUpToThreeOptions() {
        assertEquals(
            "Welches Licht meinst du? Zur Auswahl: Sternenhimmel, Treppenhaus oder Flurlicht.",
            SpokenReply.text(response("Welches Licht meinst du?", labels = listOf("Sternenhimmel", "Treppenhaus", "Flurlicht"))),
        )
        assertEquals(
            "Welche Lampe? Zur Auswahl: Wandlampe links oder Wandlampe rechts.",
            SpokenReply.text(response("Welche Lampe?", labels = listOf("Wandlampe links", "Wandlampe rechts"))),
        )
    }

    @Test
    fun skipsLongListsAndOptionsAlreadyInTheMessage() {
        val four = listOf("A-Licht", "B-Licht", "C-Licht", "D-Licht")
        assertEquals("Welches Licht?", SpokenReply.text(response("Welches Licht?", labels = four)))
        assertEquals(
            "Meinst du den Sternenhimmel oder das Treppenhaus?",
            SpokenReply.text(response("Meinst du den Sternenhimmel oder das Treppenhaus?", labels = listOf("Sternenhimmel", "Treppenhaus"))),
        )
    }

    @Test
    fun addsSeparatorWhenMessageLacksPunctuation() {
        assertEquals("Bitte wählen. Zur Auswahl: Bad.", SpokenReply.text(response("Bitte wählen", labels = listOf("Bad"))))
    }

    @Test
    fun completedWithoutActionsIsInformation() {
        assertTrue(SpokenReply.isInformation(response("Im Wohnzimmer sind es 21,5 Grad.", status = SymconProtocol.COMPLETED)))
        val switched = listOf(SymconAction("l", "Licht", "light", "setPower", "confirmed"))
        assertFalse(SpokenReply.isInformation(response("Das Licht ist an.", status = SymconProtocol.COMPLETED, actions = switched)))
        assertFalse(SpokenReply.isInformation(response("Nicht erlaubt.", status = SymconProtocol.REJECTED)))
    }
}
