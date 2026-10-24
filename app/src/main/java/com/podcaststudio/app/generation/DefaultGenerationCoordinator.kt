package com.podcaststudio.app.generation

import com.podcaststudio.app.domain.AudioAssembler
import com.podcaststudio.app.domain.ContentRepository
import com.podcaststudio.app.domain.EpisodeRepository
import com.podcaststudio.app.domain.GenerationCoordinator
import com.podcaststudio.app.domain.GenerationJob
import com.podcaststudio.app.domain.GenerationStatus
import com.podcaststudio.app.domain.PodcastEpisode
import com.podcaststudio.app.domain.PodcastRequest
import com.podcaststudio.app.domain.PodcastScript
import com.podcaststudio.app.domain.ResearchProvider
import com.podcaststudio.app.domain.ScriptGenerator
import com.podcaststudio.app.domain.Speaker
import com.podcaststudio.app.domain.SpeechPlan
import com.podcaststudio.app.domain.UnfinishedDraft
import com.podcaststudio.app.domain.VoiceProvider
import com.podcaststudio.app.domain.validate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Runs the pipeline research -> script -> speech -> assembly against whatever providers it is given.
 *
 * Safety rules, all enforced here:
 * - One run at a time. A new run is refused until the previous coroutine has actually finished, not merely been
 *   marked cancelled, so an old run can never write into a newer job.
 * - Every state change goes through [Run.update], which ignores writes once the job is finished or replaced.
 * - Nothing here retries a paid call. Failures stop the run; clips and the script already produced are kept so the
 *   user can resume with [retryAudio], which only requests what is still missing.
 */
class DefaultGenerationCoordinator(
    private val scope: CoroutineScope,
    private val research: ResearchProvider,
    private val scripts: ScriptGenerator,
    private val voices: VoiceProvider,
    private val assembler: AudioAssembler,
    private val episodes: EpisodeRepository,
    private val content: ContentRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : GenerationCoordinator {

    private val _job = MutableStateFlow<GenerationJob?>(null)
    override val job: StateFlow<GenerationJob?> = _job.asStateFlow()
    private var running: Job? = null

    /** A run's handle on the shared job state. Writes are ignored unless this run still owns an unfinished job. */
    private inner class Run(val id: String) {
        fun update(change: (GenerationJob) -> GenerationJob) {
            _job.update { cur -> if (cur != null && cur.id == id && !cur.status.isTerminal) change(cur) else cur }
        }

        fun advance(next: GenerationStatus) = update { it.transitionTo(next) }
        fun fail(message: String) = update { it.transitionTo(GenerationStatus.FAILED, message) }
        fun complete(episodeId: String) = update { it.transitionTo(GenerationStatus.COMPLETED, episodeId = episodeId) }
    }

    // isCompleted, not isActive: a cancelled run reports !isActive at once but may still be finishing an in-flight step.
    private fun busy() = _job.value?.status?.isTerminal == false || running?.isCompleted == false

    override fun start(request: PodcastRequest): Boolean {
        if (busy()) return false
        val initial = GenerationJob(UUID.randomUUID().toString(), request)
        val errors = request.validate()
        if (errors.isNotEmpty()) {
            _job.value = initial.transitionTo(GenerationStatus.FAILED, "Invalid request: $errors")
            return true
        }
        _job.value = initial
        launchRun(initial.id) { run ->
            // refuse over-budget episodes before any paid call is made
            voices.checkBudget(request, null)
            content.saveRequest(initial.id, request) // so audio can be resumed even after a restart
            currentCoroutineContext().ensureActive()
            run.advance(GenerationStatus.RESEARCHING)
            val notes = research.research(request)
            content.saveResearch(initial.id, notes) // kept even if a later stage fails
            currentCoroutineContext().ensureActive()
            run.advance(GenerationStatus.SCRIPTING)
            val script = scripts.generate(request, notes)
            content.saveScript(initial.id, script)
            run.update { it.copy(hasScript = true) }
            speakAndAssemble(run, request, script, initial.id)
        }
        return true
    }

    override suspend fun retryAudio(draftId: String): Boolean {
        if (busy()) return false
        val script = content.getScript(draftId) ?: return false
        val request = content.getRequest(draftId) ?: return false
        if (episodes.getEpisode(draftId) != null) return false // already finished
        if (busy()) return false // something started while we were reading the draft

        // The job begins at the stage just before audio: research and script are not repeated.
        _job.value = GenerationJob(draftId, request, status = GenerationStatus.SCRIPTING, hasScript = true)
        launchRun(draftId) { run -> speakAndAssemble(run, request, script, draftId) }
        return true
    }

    override suspend fun planRetry(draftId: String): SpeechPlan? {
        val script = content.getScript(draftId) ?: return null
        val request = content.getRequest(draftId) ?: return null
        if (episodes.getEpisode(draftId) != null) return null
        return voices.plan(request, script.segments, draftId)
    }

    override suspend fun unfinishedDraft(): UnfinishedDraft? = content.latestUnfinished(clock() - DRAFT_MAX_AGE_MS)

    override suspend fun discardDraft(draftId: String) {
        if (_job.value?.id == draftId && busy()) return // never pull the rug from under a running job
        voices.discardDraft(draftId)
        content.delete(draftId)
        _job.update { if (it?.id == draftId && it.status.isTerminal) null else it }
    }

    private fun launchRun(id: String, body: suspend (Run) -> Unit) {
        val run = Run(id)
        running = scope.launch {
            try {
                body(run)
            } catch (e: CancellationException) {
                throw e // cancel() already published CANCELLED; saved clips and script are kept
            } catch (e: Exception) {
                run.fail(e.message ?: "Generation failed")
            }
        }
    }

    /** Speech for every segment (reusing saved clips), then assembly and saving the episode. */
    private suspend fun speakAndAssemble(run: Run, request: PodcastRequest, script: PodcastScript, draftId: String) {
        // only characters that still need sending count against the cap
        voices.checkBudget(request, script.segments, draftId)
        currentCoroutineContext().ensureActive()
        run.advance(GenerationStatus.GENERATING_AUDIO)
        run.update { it.copy(audioTotal = script.segments.size, audioDone = 0, audioReused = 0) }

        val clips = mutableListOf<String>()
        // sequential on purpose: keeps dialogue order and stays inside provider concurrency limits
        script.segments.forEachIndexed { i, seg ->
            currentCoroutineContext().ensureActive()
            val voice = if (seg.speaker == Speaker.HOST) request.hostVoice!! else request.guestVoice!!
            val clip = voices.synthesize(seg, voice, request.language, draftId)
            // record actual usage as it happens, so it survives a later failure
            if (clip.characters > 0) content.addTtsCharacters(draftId, clip.characters)
            clips += clip.ref
            run.update { it.copy(audioDone = i + 1, audioReused = it.audioReused + if (clip.reused) 1 else 0) }
        }

        currentCoroutineContext().ensureActive()
        run.advance(GenerationStatus.ASSEMBLING)
        val audio = assembler.assemble(clips)
        val episode = PodcastEpisode(
            id = draftId,
            title = script.title.ifBlank { titleFor(request.topic) },
            topic = request.topic.trim(),
            createdAt = clock(),
            language = request.language,
            durationSeconds = audio.durationMs?.let { ((it + 500) / 1000).toInt() } ?: script.estimatedDurationSeconds,
            audioUri = audio.uri,
        )
        episodes.save(episode)
        // the episode is safely saved, so the temporary clips are no longer needed
        withContext(NonCancellable) { runCatching { voices.discardDraft(draftId) } }
        run.complete(episode.id)
    }

    override fun cancel() {
        val current = _job.value ?: return
        if (current.status.isTerminal) return
        _job.value = current.transitionTo(GenerationStatus.CANCELLED)
        running?.cancel()
    }

    override fun reset() {
        if (_job.value?.status?.isTerminal == true) _job.value = null
    }

    private fun titleFor(topic: String): String {
        val t = topic.trim().lines().first()
        return if (t.length <= 60) t else t.take(57) + "..."
    }

    private companion object {
        /** Drafts older than this are not offered for resuming (their saved clips are purged on the same schedule). */
        const val DRAFT_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L
    }
}
