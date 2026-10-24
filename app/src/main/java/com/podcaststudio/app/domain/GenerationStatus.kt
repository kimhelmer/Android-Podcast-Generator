package com.podcaststudio.app.domain

enum class GenerationStatus(val label: String) {
    QUEUED("Queued"),
    RESEARCHING("Researching topic"),
    SCRIPTING("Writing script"),
    GENERATING_AUDIO("Generating voices"),
    ASSEMBLING("Assembling episode"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled");

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED

    /** Legal next states. Active stages advance in order; any active stage may fail or be cancelled. */
    fun canTransitionTo(next: GenerationStatus): Boolean {
        if (isTerminal) return false
        if (next == FAILED || next == CANCELLED) return true
        return when (this) {
            QUEUED -> next == RESEARCHING
            RESEARCHING -> next == SCRIPTING
            SCRIPTING -> next == GENERATING_AUDIO
            GENERATING_AUDIO -> next == ASSEMBLING
            ASSEMBLING -> next == COMPLETED
            else -> false
        }
    }
}

data class GenerationJob(
    val id: String,
    val request: PodcastRequest,
    val status: GenerationStatus = GenerationStatus.QUEUED,
    val episodeId: String? = null,
    val error: String? = null,
    /** Segments of speech finished / total, meaningful during GENERATING_AUDIO. */
    val audioDone: Int = 0,
    val audioTotal: Int = 0,
    /** Of [audioDone], how many were reused from an earlier attempt (no new paid request). */
    val audioReused: Int = 0,
    /** A script is saved for this job, so a failed or cancelled audio stage can be retried. */
    val hasScript: Boolean = false,
) {
    /** 0f..1f across the active stages. */
    val progress: Float
        get() = when (status) {
            GenerationStatus.QUEUED -> 0f
            GenerationStatus.RESEARCHING -> 0.1f
            GenerationStatus.SCRIPTING -> 0.35f
            GenerationStatus.GENERATING_AUDIO ->
                if (audioTotal > 0) 0.6f + 0.25f * audioDone / audioTotal else 0.6f
            GenerationStatus.ASSEMBLING -> 0.85f
            GenerationStatus.COMPLETED -> 1f
            GenerationStatus.FAILED, GenerationStatus.CANCELLED -> 0f
        }

    /** "Generating voices (3/14)" while speech is being produced, otherwise the plain stage label. */
    val stageLabel: String
        get() = if (status == GenerationStatus.GENERATING_AUDIO && audioTotal > 0) {
            "${status.label} ($audioDone/$audioTotal" + (if (audioReused > 0) ", $audioReused reused" else "") + ")"
        } else {
            status.label
        }

    fun transitionTo(next: GenerationStatus, error: String? = null, episodeId: String? = this.episodeId): GenerationJob {
        require(status.canTransitionTo(next)) { "Illegal transition $status -> $next" }
        return copy(status = next, error = error, episodeId = episodeId)
    }
}
