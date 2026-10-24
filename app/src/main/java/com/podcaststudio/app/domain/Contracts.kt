package com.podcaststudio.app.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Gathers sourced background material for a topic (OpenAI web search in live mode). */
interface ResearchProvider {
    suspend fun research(request: PodcastRequest): ResearchResult
}

/** Turns research into a validated, alternating host/guest script (OpenAI in live mode). */
interface ScriptGenerator {
    suspend fun generate(request: PodcastRequest, research: ResearchResult): PodcastScript
}

/**
 * One spoken segment. [reused] is true when an earlier valid clip was found and no paid request was made;
 * [characters] is what was actually sent to the provider for this clip (0 when reused).
 */
data class SpeechClip(val ref: String, val characters: Int = 0, val reused: Boolean = false)

/** What speech generation would do for a script right now. Counts are exact once a script exists. */
data class SpeechPlan(val totalSegments: Int, val reusableSegments: Int, val charactersToSend: Int) {
    val remainingSegments: Int get() = totalSegments - reusableSegments
}

/** Lists voices and synthesizes speech for one segment (ElevenLabs in live mode). */
interface VoiceProvider {
    suspend fun availableVoices(): List<VoiceProfile>

    /**
     * Speaks [segment]. When [draftId] is given, a valid clip from an earlier run of the same draft with the same
     * voice, model and text is reused instead of making a new paid request. Never retries on its own.
     */
    suspend fun synthesize(segment: ScriptSegment, voice: VoiceProfile, language: Language, draftId: String?): SpeechClip

    /**
     * Throws if the characters still to be sent would exceed the provider's spending cap. Called with
     * [segments] = null before any paid work starts (estimated from the request), and again with the real script.
     */
    suspend fun checkBudget(request: PodcastRequest, segments: List<ScriptSegment>?, draftId: String? = null) {}

    /** Exact counts for [segments], taking reusable clips of [draftId] into account. */
    suspend fun plan(request: PodcastRequest, segments: List<ScriptSegment>, draftId: String?): SpeechPlan =
        SpeechPlan(segments.size, 0, 0)

    /** Deletes saved clips of an abandoned or finished draft. */
    suspend fun discardDraft(draftId: String) {}
}

/** The finished episode audio. [durationMs] is null when the assembler cannot know it. */
data class AssembledAudio(val uri: String, val durationMs: Long?)

/** Joins per-segment clips, in order, into a single episode audio file. */
interface AudioAssembler {
    suspend fun assemble(clips: List<String>): AssembledAudio
}

interface EpisodeRepository {
    fun observeEpisodes(): Flow<List<PodcastEpisode>>
    suspend fun getEpisode(id: String): PodcastEpisode?
    suspend fun save(episode: PodcastEpisode)
    suspend fun updatePosition(id: String, positionMs: Long)
    suspend fun delete(id: String)
}

/** An episode whose script exists but whose audio is not finished, so audio generation can be resumed. */
data class UnfinishedDraft(val id: String, val title: String, val request: PodcastRequest, val segmentCount: Int)

/**
 * Runs one generation job at a time and exposes its state. Call from the main thread: the one-run-at-a-time rule is
 * enforced by single-threaded bookkeeping, not locks.
 */
interface GenerationCoordinator {
    val job: StateFlow<GenerationJob?>

    /** Returns false if nothing was started: another job is active or still stopping. */
    fun start(request: PodcastRequest): Boolean

    /**
     * Resumes audio for a saved draft without repeating research or the script. Returns false if a job is active,
     * or the draft is missing or incomplete.
     */
    suspend fun retryAudio(draftId: String): Boolean

    /** What a retry would send. Null if the draft cannot be resumed. */
    suspend fun planRetry(draftId: String): SpeechPlan?

    /** The most recent draft that can be resumed, if any. */
    suspend fun unfinishedDraft(): UnfinishedDraft?

    /** Removes a draft's saved script, research and audio clips. */
    suspend fun discardDraft(draftId: String)

    fun cancel()

    /** Clears a finished job so the progress screen starts clean next time. */
    fun reset()
}
