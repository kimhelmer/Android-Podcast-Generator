package com.podcaststudio.app

import com.podcaststudio.app.audio.MOCK_VOICES
import com.podcaststudio.app.domain.Language
import com.podcaststudio.app.domain.PodcastRequest
import com.podcaststudio.app.domain.ScriptSegment
import com.podcaststudio.app.domain.Speaker
import com.podcaststudio.app.domain.ValidationError
import com.podcaststudio.app.domain.alternatesHostGuest
import com.podcaststudio.app.domain.validate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastRequestTest {
    private val anna = MOCK_VOICES.first { it.id == "mock-anna" }
    private val mads = MOCK_VOICES.first { it.id == "mock-mads" }
    private val sofie = MOCK_VOICES.first { it.id == "mock-sofie" } // Danish only
    private val james = MOCK_VOICES.first { it.id == "mock-james" } // English only

    private fun valid() = PodcastRequest("The history of the bicycle", Language.ENGLISH, 10, anna, mads)

    @Test fun validRequestHasNoErrors() = assertTrue(valid().validate().isEmpty())

    @Test fun emptyTopicRejected() =
        assertEquals(listOf(ValidationError.TOPIC_EMPTY), valid().copy(topic = "   ").validate())

    @Test fun shortTopicRejected() =
        assertEquals(listOf(ValidationError.TOPIC_TOO_SHORT), valid().copy(topic = "bikes").validate())

    @Test fun longTopicRejected() =
        assertEquals(listOf(ValidationError.TOPIC_TOO_LONG), valid().copy(topic = "x".repeat(2001)).validate())

    @Test fun onlyFiveTenFifteenMinutesAllowed() {
        listOf(5, 10, 15).forEach { assertTrue(valid().copy(durationMinutes = it).validate().isEmpty()) }
        listOf(0, 1, 7, 20, -5).forEach {
            assertEquals(listOf(ValidationError.INVALID_DURATION), valid().copy(durationMinutes = it).validate())
        }
    }

    @Test fun missingVoicesReported() {
        val errors = valid().copy(hostVoice = null, guestVoice = null).validate()
        assertEquals(listOf(ValidationError.HOST_VOICE_MISSING, ValidationError.GUEST_VOICE_MISSING), errors)
    }

    @Test fun sameVoiceRejected() =
        assertEquals(listOf(ValidationError.SAME_VOICE), valid().copy(guestVoice = anna).validate())

    @Test fun voiceMustSupportLanguage() {
        assertTrue(ValidationError.VOICE_LANGUAGE_MISMATCH in valid().copy(hostVoice = sofie).validate())
        val danish = valid().copy(language = Language.DANISH, hostVoice = sofie, guestVoice = anna)
        assertTrue(danish.validate().isEmpty())
        assertTrue(ValidationError.VOICE_LANGUAGE_MISMATCH in danish.copy(guestVoice = james).validate())
    }

    @Test fun multipleErrorsCollected() {
        val errors = PodcastRequest("", Language.ENGLISH, 3, null, null).validate()
        assertEquals(4, errors.size)
    }

    @Test fun scriptsMustAlternateStartingWithHost() {
        fun seg(s: Speaker) = ScriptSegment("id", s, "text")
        assertTrue(listOf(seg(Speaker.HOST), seg(Speaker.GUEST), seg(Speaker.HOST)).alternatesHostGuest())
        assertFalse(listOf(seg(Speaker.GUEST), seg(Speaker.HOST)).alternatesHostGuest())
        assertFalse(listOf(seg(Speaker.HOST), seg(Speaker.HOST)).alternatesHostGuest())
        assertFalse(emptyList<ScriptSegment>().alternatesHostGuest())
    }
}
