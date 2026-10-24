package com.podcaststudio.app

import android.content.Context
import androidx.room.Room
import androidx.media3.common.Player
import com.podcaststudio.app.audio.PcmWavAssembler
import com.podcaststudio.app.audio.connectToPlaybackService
import com.podcaststudio.app.audio.MockAudioAssembler
import com.podcaststudio.app.data.remote.ELEVENLABS_BASE_URL
import com.podcaststudio.app.data.remote.ElevenLabsClientFactory
import com.podcaststudio.app.data.remote.ElevenLabsVoiceProvider
import com.podcaststudio.app.data.remote.TTS_SAMPLE_RATE
import com.podcaststudio.app.generation.SwitchingAudioAssembler
import com.podcaststudio.app.generation.SwitchingVoiceProvider
import java.io.File
import com.podcaststudio.app.audio.MockResearchProvider
import com.podcaststudio.app.audio.MockScriptGenerator
import com.podcaststudio.app.audio.MockVoiceProvider
import com.podcaststudio.app.data.local.AppDatabase
import com.podcaststudio.app.data.local.ALL_MIGRATIONS
import com.podcaststudio.app.data.local.RoomContentRepository
import com.podcaststudio.app.data.local.RoomEpisodeRepository
import com.podcaststudio.app.data.local.SharedPrefsAiSettingsStore
import com.podcaststudio.app.data.remote.OPENAI_BASE_URL
import com.podcaststudio.app.data.remote.OpenAiClientFactory
import com.podcaststudio.app.data.remote.OpenAiResearchProvider
import com.podcaststudio.app.data.remote.OpenAiScriptGenerator
import com.podcaststudio.app.domain.AiSettingsStore
import com.podcaststudio.app.domain.ContentRepository
import com.podcaststudio.app.domain.EpisodeRepository
import com.podcaststudio.app.domain.GenerationCoordinator
import com.podcaststudio.app.domain.Language
import com.podcaststudio.app.domain.PodcastEpisode
import com.podcaststudio.app.domain.VoiceProvider
import com.podcaststudio.app.generation.DefaultGenerationCoordinator
import com.podcaststudio.app.generation.SwitchingResearchProvider
import com.podcaststudio.app.generation.SwitchingScriptGenerator
import com.podcaststudio.app.security.KeystoreSecretStore
import com.podcaststudio.app.security.SecretKeyName
import com.podcaststudio.app.security.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Hand-rolled dependency container; M1A is small enough that DI frameworks would be overhead. */
class AppContainer(
    val context: Context,
    database: AppDatabase,
    val secrets: SecretStore,
    val aiSettings: AiSettingsStore,
    stepDelayMs: Long = 1200,
    openAiBaseUrl: String = OPENAI_BASE_URL,
    requireHttps: Boolean = true,
    httpBackoff: suspend (Long) -> Unit = { delay(it) },
    elevenLabsBaseUrl: String = ELEVENLABS_BASE_URL,
    val episodeAudioDir: File = File(context.filesDir, "episodes"),
    /** Where speech clips of unfinished episodes are kept so audio can be resumed. App-private, not cache. */
    draftsDir: File = File(context.filesDir, "drafts"),
    pauseBetweenTurnsMs: Int = 180,
    /** How the player screen reaches the player; tests substitute a plain player for the background service. */
    val playerConnector: suspend () -> Player = { connectToPlaybackService(context) },
) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val sampleAudioUri: String = "android.resource://${context.packageName}/${R.raw.sample_episode}"

    val episodes: EpisodeRepository = RoomEpisodeRepository(database.episodeDao())
    val content: ContentRepository = RoomContentRepository(database.contentDao())

    // The clients read keys from the Keystore-backed store per request; nothing is cached here.
    private val openAi = OpenAiClientFactory.create(
        keyProvider = { secrets.get(SecretKeyName.OPENAI) },
        baseUrl = openAiBaseUrl,
        requireHttps = requireHttps,
        backoff = httpBackoff,
    )
    private val elevenLabs = ElevenLabsClientFactory.create(
        keyProvider = { secrets.get(SecretKeyName.ELEVENLABS) },
        baseUrl = elevenLabsBaseUrl,
        requireHttps = requireHttps,
        backoff = httpBackoff,
    )
    private val liveVoices = ElevenLabsVoiceProvider(elevenLabs, aiSettings, draftsDir)

    /** Chooses ElevenLabs or the offline mock per call, so the Create screen and the pipeline agree. */
    val voices: VoiceProvider = SwitchingVoiceProvider(aiSettings, secrets, MockVoiceProvider(), liveVoices)

    val coordinator: GenerationCoordinator = DefaultGenerationCoordinator(
        scope = appScope,
        research = SwitchingResearchProvider(
            aiSettings, secrets,
            mock = MockResearchProvider(stepDelayMs),
            live = OpenAiResearchProvider(openAi, aiSettings),
        ),
        scripts = SwitchingScriptGenerator(
            aiSettings, secrets,
            mock = MockScriptGenerator(stepDelayMs),
            live = OpenAiScriptGenerator(openAi, aiSettings),
        ),
        voices = voices,
        assembler = SwitchingAudioAssembler(
            mock = MockAudioAssembler(sampleAudioUri, stepDelayMs),
            live = PcmWavAssembler(episodeAudioDir, TTS_SAMPLE_RATE, pauseBetweenTurnsMs),
        ),
        episodes = episodes,
        content = content,
    )

    /** Removes an episode with its stored script/research and its generated audio file. The sample is kept. */
    suspend fun deleteEpisode(episode: PodcastEpisode) {
        if (episode.id == SAMPLE_EPISODE_ID) return
        episodes.delete(episode.id)
        content.delete(episode.id)
        liveVoices.discardDraft(episode.id)
        if (episode.audioUri.startsWith("file:")) {
            runCatching { File(java.net.URI(episode.audioUri)).takeIf { it.parentFile == episodeAudioDir }?.delete() }
        }
    }

    /** Removes saved clips and stored text of unfinished episodes untouched for a week. */
    fun purgeOldDrafts() {
        liveVoices.purgeOldDrafts()
        appScope.launch { content.purgeUnfinishedBefore(System.currentTimeMillis() - DRAFT_MAX_AGE_MS) }
    }

    /** Makes sure the bundled sample episode exists in the library. */
    fun seedSampleEpisode() {
        appScope.launch {
            if (episodes.getEpisode(SAMPLE_EPISODE_ID) == null) {
                episodes.save(
                    PodcastEpisode(
                        id = SAMPLE_EPISODE_ID,
                        title = "Sample episode (bundled tones)",
                        topic = "Bundled audio used to verify playback",
                        createdAt = System.currentTimeMillis(),
                        language = Language.ENGLISH,
                        durationSeconds = SAMPLE_DURATION_SECONDS,
                        audioUri = sampleAudioUri,
                    ),
                )
            }
        }
    }

    companion object {
        const val SAMPLE_EPISODE_ID = "sample-episode"
        const val SAMPLE_DURATION_SECONDS = 24
        const val DRAFT_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L

        fun create(context: Context): AppContainer {
            val app = context.applicationContext
            val db = Room.databaseBuilder(app, AppDatabase::class.java, "podcast_studio.db")
                .addMigrations(*ALL_MIGRATIONS)
                .build()
            return AppContainer(app, db, KeystoreSecretStore(app), SharedPrefsAiSettingsStore(app))
        }
    }
}
