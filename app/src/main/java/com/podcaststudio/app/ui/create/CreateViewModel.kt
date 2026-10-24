package com.podcaststudio.app.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podcaststudio.app.domain.AiSettingsStore
import com.podcaststudio.app.domain.GenerationCoordinator
import com.podcaststudio.app.domain.Language
import com.podcaststudio.app.domain.PodcastRequest
import com.podcaststudio.app.domain.SpeechPlan
import com.podcaststudio.app.domain.UnfinishedDraft
import com.podcaststudio.app.domain.ValidationError
import com.podcaststudio.app.domain.VoiceProfile
import com.podcaststudio.app.domain.VoiceProvider
import com.podcaststudio.app.domain.validate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Plays a short sample of a voice. Implemented with Media3 in the app and faked in tests. */
interface VoicePreview {
    var onFinished: (() -> Unit)?
    fun play(url: String)
    fun stop()
    fun release()
}

/** What the user is asked to approve before paid generation starts. Estimates only: exact counts need the script. */
data class ApprovalInfo(
    val liveOpenAi: Boolean,
    val liveAudio: Boolean,
    val estimatedChars: Int,
    val maxChars: Int,
)

/** An unfinished episode plus what resuming it would send. */
data class RetryApproval(val draft: UnfinishedDraft, val plan: SpeechPlan)

enum class GenerateResult { STARTED, NEEDS_APPROVAL, INVALID, BUSY }

data class CreateUiState(
    val topic: String = "",
    val language: Language = Language.ENGLISH,
    val durationMinutes: Int = 5,
    val allVoices: List<VoiceProfile> = emptyList(),
    val host: VoiceProfile? = null,
    val guest: VoiceProfile? = null,
    val errors: List<ValidationError> = emptyList(),
    val liveOpenAi: Boolean = false,
    val liveAudio: Boolean = false,
    val estimatedChars: Int = 0,
    val loadingVoices: Boolean = false,
    val voiceError: String? = null,
    val previewingUrl: String? = null,
    val approval: ApprovalInfo? = null,
    val message: String? = null,
    /** Shown as a banner when the last episode's script exists but its audio is unfinished. */
    val draft: UnfinishedDraft? = null,
    val retryApproval: RetryApproval? = null,
) {
    val voicesForLanguage: List<VoiceProfile> get() = allVoices.filter { language in it.languages }
}

class CreateViewModel(
    private val voiceProvider: VoiceProvider,
    private val coordinator: GenerationCoordinator,
    private val aiSettings: AiSettingsStore,
    private val preview: VoicePreview,
) : ViewModel() {
    private val _state = MutableStateFlow(CreateUiState())
    val state: StateFlow<CreateUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var loadedFor: Pair<Boolean, String>? = null
    private var loadingKey: Pair<Boolean, String>? = null

    init {
        preview.onFinished = { _state.update { it.copy(previewingUrl = null) } }
        refreshMode()
        // the unfinished-episode banner depends on whether a job is running and on how the last one ended
        viewModelScope.launch { coordinator.job.collect { loadDraft() } }
    }

    /** Settings can change while this screen is in the back stack; reload voices when that matters. */
    fun refreshMode() {
        val s = aiSettings.get()
        _state.update {
            it.copy(
                liveOpenAi = s.useLiveOpenAi,
                liveAudio = s.useLiveElevenLabs,
                estimatedChars = s.estimatedCharacters(it.durationMinutes),
            )
        }
        val key = s.useLiveElevenLabs to s.ttsModel
        // reload when the voice source changed, or the last attempt failed (e.g. the key was just added)
        val needsLoad = loadedFor != key || _state.value.voiceError != null
        val alreadyLoadingThis = loadJob?.isActive == true && loadingKey == key
        if (needsLoad && !alreadyLoadingThis) loadVoices(key)
        viewModelScope.launch { loadDraft() }
    }

    private suspend fun loadDraft() {
        val active = coordinator.job.value?.let { !it.status.isTerminal } ?: false
        val draft = if (active) null else coordinator.unfinishedDraft()
        _state.update { it.copy(draft = draft) }
    }

    private fun loadVoices(key: Pair<Boolean, String>) {
        loadJob?.cancel()
        loadingKey = key
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loadingVoices = true, voiceError = null) }
            try {
                val voices = voiceProvider.availableVoices()
                loadedFor = key
                _state.update { s ->
                    s.copy(
                        allVoices = voices,
                        loadingVoices = false,
                        // keep a selection only if that voice still exists
                        host = s.host?.let { h -> voices.firstOrNull { it.id == h.id } },
                        guest = s.guest?.let { g -> voices.firstOrNull { it.id == g.id } },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        allVoices = emptyList(),
                        host = null,
                        guest = null,
                        loadingVoices = false,
                        voiceError = e.message ?: "Could not load voices.",
                    )
                }
            }
        }
    }

    fun setTopic(v: String) = _state.update { it.copy(topic = v, errors = emptyList(), message = null) }

    fun setLanguage(lang: Language) = _state.update { s ->
        s.copy(
            language = lang,
            // drop selections the new language cannot use
            host = s.host?.takeIf { lang in it.languages },
            guest = s.guest?.takeIf { lang in it.languages },
            errors = emptyList(),
        )
    }

    fun setDuration(minutes: Int) = _state.update {
        it.copy(durationMinutes = minutes, estimatedChars = aiSettings.get().estimatedCharacters(minutes), errors = emptyList())
    }

    fun setHost(v: VoiceProfile) = _state.update { it.copy(host = v, errors = emptyList()) }
    fun setGuest(v: VoiceProfile) = _state.update { it.copy(guest = v, errors = emptyList()) }

    fun togglePreview(voice: VoiceProfile) {
        val url = voice.previewUrl ?: return
        if (_state.value.previewingUrl == url) {
            preview.stop()
            _state.update { it.copy(previewingUrl = null) }
        } else {
            preview.play(url)
            _state.update { it.copy(previewingUrl = url) }
        }
    }

    fun stopPreview() {
        preview.stop()
        _state.update { it.copy(previewingUrl = null) }
    }

    private fun currentRequest(): PodcastRequest {
        val s = _state.value
        return PodcastRequest(s.topic, s.language, s.durationMinutes, s.host, s.guest)
    }

    /**
     * Validates the form. A simulated run starts straight away; anything that uses a paid service first needs the
     * user's explicit approval (see [approveAndStart]).
     */
    fun generate(): GenerateResult {
        val errors = currentRequest().validate()
        _state.update { it.copy(errors = errors, message = null) }
        if (errors.isNotEmpty()) return GenerateResult.INVALID
        val settings = aiSettings.get()
        if (settings.useLiveOpenAi || settings.useLiveElevenLabs) {
            _state.update {
                it.copy(
                    approval = ApprovalInfo(
                        liveOpenAi = settings.useLiveOpenAi,
                        liveAudio = settings.useLiveElevenLabs,
                        estimatedChars = settings.estimatedCharacters(it.durationMinutes),
                        maxChars = settings.maxTtsCharacters,
                    ),
                )
            }
            return GenerateResult.NEEDS_APPROVAL
        }
        return begin()
    }

    fun dismissApproval() = _state.update { it.copy(approval = null) }

    /** The user approved the paid run shown in the dialog. */
    fun approveAndStart(): GenerateResult {
        _state.update { it.copy(approval = null) }
        return begin()
    }

    private fun begin(): GenerateResult {
        preview.stop()
        coordinator.reset()
        val started = coordinator.start(currentRequest())
        if (!started) {
            _state.update { it.copy(message = "Another episode is still running or stopping. Try again in a moment.") }
            return GenerateResult.BUSY
        }
        return GenerateResult.STARTED
    }

    // ---- resuming an unfinished episode ----

    /** Works out what a retry would send and asks the user to approve it. */
    fun askRetry() {
        val draft = _state.value.draft ?: return
        viewModelScope.launch {
            val plan = coordinator.planRetry(draft.id)
            if (plan == null) {
                _state.update { it.copy(draft = null, message = "That episode can no longer be resumed.") }
            } else {
                _state.update { it.copy(retryApproval = RetryApproval(draft, plan)) }
            }
        }
    }

    fun dismissRetry() = _state.update { it.copy(retryApproval = null) }

    /**
     * Resumes audio for the approved draft. Runs in the view model's main-thread scope and calls [onResumed] there, so
     * the caller can navigate safely.
     */
    fun confirmRetry(onResumed: () -> Unit) {
        val approval = _state.value.retryApproval ?: return
        _state.update { it.copy(retryApproval = null) }
        viewModelScope.launch {
            coordinator.reset()
            if (coordinator.retryAudio(approval.draft.id)) {
                onResumed()
            } else {
                _state.update { it.copy(message = "Could not resume: another episode is running, or the draft is gone.") }
            }
        }
    }

    fun discardDraft() {
        val draft = _state.value.draft ?: return
        viewModelScope.launch {
            coordinator.discardDraft(draft.id)
            _state.update { it.copy(draft = null) }
        }
    }

    override fun onCleared() {
        preview.onFinished = null
        preview.release()
    }
}
