package io.github.hagbard235.ringnotes.core.symcon

import io.github.hagbard235.ringnotes.core.symcon.SymconResponse.Companion.Parsed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject

class SymconProtocolTest {
    @Test
    fun requestContainsContractFields() {
        val json = JSONObject(
            SymconRequest(
                requestId = "5bbeb216-9de0-4cac-a9f4-aea7622e09b9",
                transcript = "Schalte den Sternenhimmel für zwei Minuten ein",
                recording = "ring-20260928-144525",
                createdAt = "2026-09-28T12:45:25Z",
                durationMs = 11000,
                locale = "de-DE",
            ).toJson(),
        )
        assertEquals("1", json.getString("version"))
        assertEquals("5bbeb216-9de0-4cac-a9f4-aea7622e09b9", json.getString("requestId"))
        assertEquals("Schalte den Sternenhimmel für zwei Minuten ein", json.getString("transcript"))
        assertEquals("ring-20260928-144525", json.getString("recording"))
        assertEquals("2026-09-28T12:45:25Z", json.getString("createdAt"))
        assertEquals(11000, json.getLong("durationMs"))
        assertEquals("de-DE", json.getString("locale"))
        assertFalse(json.has("replyTo"))
    }

    @Test
    fun replyWithOptionAndFreeText() {
        val option = JSONObject(
            SymconRequest("new-id", "Den Sternenhimmel", replyTo = ReplyTo("old-id", "clarification-7d92", "sternenhimmel")).toJson(),
        ).getJSONObject("replyTo")
        assertEquals("old-id", option.getString("requestId"))
        assertEquals("clarification-7d92", option.getString("clarificationId"))
        assertEquals("sternenhimmel", option.getString("optionId"))

        val free = JSONObject(SymconRequest("id2", "das obere", replyTo = ReplyTo("old-id", "c", null)).toJson())
            .getJSONObject("replyTo")
        assertFalse(free.has("optionId"))
    }

    @Test
    fun parsesCompletedResponse() {
        val body = """
            {"version":"1","requestId":"5bbeb216","status":"completed",
             "message":"Der Sternenhimmel ist eingeschaltet. Er wird in zwei Minuten ausgeschaltet.",
             "actions":[{"deviceId":"sternenhimmel","deviceName":"Sternenhimmel","service":"light",
                         "operation":"setPower","parameters":{"on":true,"durationSeconds":120},"status":"confirmed"}],
             "error":null,"clarification":null,"poll":null,"futureField":42}
        """.trimIndent()
        val r = assertIs<Parsed.Ok>(SymconResponse.parse(body)).response
        assertEquals(SymconProtocol.COMPLETED, r.status)
        assertEquals("5bbeb216", r.requestId)
        assertEquals("Sternenhimmel", r.actions.single().deviceName)
        assertEquals("confirmed", r.actions.single().status)
        assertNull(r.error)
        assertNull(r.clarification)
        assertNull(r.poll)
        assertTrue(SymconProtocol.isFinal(r.status))
    }

    @Test
    fun parsesPendingWithPoll() {
        val body = """{"version":"1","requestId":"x","status":"pending","message":"Der Auftrag wird verarbeitet.",
            "actions":[],"error":null,"clarification":null,"poll":{"url":"https://h.example/status/x","afterMs":1500}}"""
        val r = assertIs<Parsed.Ok>(SymconResponse.parse(body)).response
        assertEquals(Poll("https://h.example/status/x", 1500), r.poll)
        assertFalse(SymconProtocol.isFinal(r.status))
    }

    @Test
    fun parsesClarificationAndError() {
        val body = """{"version":"1","requestId":"x","status":"clarification_required","message":"Welches Licht meinst du?",
            "actions":[],"error":{"code":"DEVICE_UNAVAILABLE","retryable":false},
            "clarification":{"id":"clarification-7d92","expiresAt":"2026-09-28T12:50:25Z",
              "options":[{"id":"sternenhimmel","label":"Sternenhimmel"},{"id":"treppenhaus","label":"Treppenhaus"}],
              "allowFreeText":true},"poll":null}"""
        val r = assertIs<Parsed.Ok>(SymconResponse.parse(body)).response
        val c = r.clarification!!
        assertEquals("clarification-7d92", c.id)
        assertEquals(2, c.options.size)
        assertTrue(c.allowFreeText)
        assertEquals(SymconError("DEVICE_UNAVAILABLE", false), r.error)
    }

    @Test
    fun rejectsNonJsonAndOtherMajorVersion() {
        assertIs<Parsed.Invalid>(SymconResponse.parse("<html>Bad Gateway</html>"))
        assertIs<Parsed.Invalid>(SymconResponse.parse(""))
        assertIs<Parsed.Incompatible>(SymconResponse.parse("""{"version":"2","status":"completed"}"""))
    }

    @Test
    fun nullRequestIdIsAllowed() {
        val r = assertIs<Parsed.Ok>(
            SymconResponse.parse("""{"version":"1","requestId":null,"status":"failed","message":"Ungültig","actions":[]}"""),
        ).response
        assertNull(r.requestId)
    }

    @Test
    fun sameOriginRequiresHttpsHostAndPort() {
        val hook = "https://symcon.example:8443/hook/ai"
        assertTrue(SymconProtocol.isSameHttpsOrigin(hook, "https://SYMCON.example:8443/hook/status/1"))
        assertFalse(SymconProtocol.isSameHttpsOrigin(hook, "https://symcon.example/hook/status/1"))
        assertFalse(SymconProtocol.isSameHttpsOrigin(hook, "http://symcon.example:8443/x"))
        assertFalse(SymconProtocol.isSameHttpsOrigin(hook, "https://evil.example:8443/x"))
        assertTrue(SymconProtocol.isSameHttpsOrigin("https://a.example/x", "https://a.example:443/y"))
    }

    @Test
    fun matchesSpokenAnswerToOption() {
        val options = listOf(ClarificationOption("sternenhimmel", "Sternenhimmel"), ClarificationOption("treppenhaus", "Treppenhaus"))
        assertEquals("sternenhimmel", matchOption("Den Sternenhimmel bitte", options)?.id)
        assertEquals("treppenhaus", matchOption("treppenhaus", options)?.id)
        assertNull(matchOption("das Licht im Bad", options))
    }
}
