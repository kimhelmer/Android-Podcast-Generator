package com.podcaststudio.app.domain

import kotlinx.serialization.Serializable

@Serializable
enum class Language(val label: String, val code: String) {
    DANISH("Dansk", "da"),
    ENGLISH("English", "en"),
}

@Serializable
enum class Speaker { HOST, GUEST }

@Serializable
data class VoiceProfile(
    val id: String,
    val name: String,
    val description: String,
    val languages: Set<Language>,
    /** HTTPS sample clip supplied by the voice provider, if any. */
    val previewUrl: String? = null,
)

@Serializable
data class PodcastRequest(
    val topic: String,
    val language: Language,
    val durationMinutes: Int,
    val hostVoice: VoiceProfile?,
    val guestVoice: VoiceProfile?,
) {
    companion object {
        val ALLOWED_DURATIONS = listOf(5, 10, 15)
        const val MIN_TOPIC_LENGTH = 10
        const val MAX_TOPIC_LENGTH = 2000
    }
}

enum class ValidationError {
    TOPIC_EMPTY,
    TOPIC_TOO_SHORT,
    TOPIC_TOO_LONG,
    INVALID_DURATION,
    HOST_VOICE_MISSING,
    GUEST_VOICE_MISSING,
    SAME_VOICE,
    VOICE_LANGUAGE_MISMATCH,
}

/** Returns every problem found; an empty list means the request is valid. */
fun PodcastRequest.validate(): List<ValidationError> = buildList {
    val t = topic.trim()
    when {
        t.isEmpty() -> add(ValidationError.TOPIC_EMPTY)
        t.length < PodcastRequest.MIN_TOPIC_LENGTH -> add(ValidationError.TOPIC_TOO_SHORT)
        t.length > PodcastRequest.MAX_TOPIC_LENGTH -> add(ValidationError.TOPIC_TOO_LONG)
    }
    if (durationMinutes !in PodcastRequest.ALLOWED_DURATIONS) add(ValidationError.INVALID_DURATION)
    if (hostVoice == null) add(ValidationError.HOST_VOICE_MISSING)
    if (guestVoice == null) add(ValidationError.GUEST_VOICE_MISSING)
    if (hostVoice != null && guestVoice != null) {
        if (hostVoice.id == guestVoice.id) add(ValidationError.SAME_VOICE)
        if (language !in hostVoice.languages || language !in guestVoice.languages) {
            add(ValidationError.VOICE_LANGUAGE_MISMATCH)
        }
    }
}

/** One spoken turn. [delivery] is an optional performance hint and is never spoken. */
@Serializable
data class ScriptSegment(
    val id: String,
    val speaker: Speaker,
    val text: String,
    val delivery: String? = null,
    val section: String = "",
)

/** Dialogue must alternate host, guest, host, ... starting with the host. */
fun List<ScriptSegment>.alternatesHostGuest(): Boolean =
    isNotEmpty() && withIndex().all { (i, s) -> s.speaker == if (i % 2 == 0) Speaker.HOST else Speaker.GUEST }

data class PodcastEpisode(
    val id: String,
    val title: String,
    val topic: String,
    val createdAt: Long,
    val language: Language,
    val durationSeconds: Int,
    val audioUri: String,
    val lastPositionMs: Long = 0L,
)
