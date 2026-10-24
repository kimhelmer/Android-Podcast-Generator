package com.podcaststudio.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.podcaststudio.app.domain.AppJson
import com.podcaststudio.app.domain.ContentRepository
import com.podcaststudio.app.domain.EpisodeRepository
import com.podcaststudio.app.domain.Language
import com.podcaststudio.app.domain.PodcastEpisode
import com.podcaststudio.app.domain.PodcastRequest
import com.podcaststudio.app.domain.PodcastScript
import com.podcaststudio.app.domain.ResearchResult
import com.podcaststudio.app.domain.UnfinishedDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Entity(tableName = "episodes")
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val title: String,
    val topic: String,
    val createdAt: Long,
    val languageCode: String,
    val durationSeconds: Int,
    val audioUri: String,
    val lastPositionMs: Long,
)

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun get(id: String): EpisodeEntity?

    @Upsert
    suspend fun upsert(entity: EpisodeEntity)

    @Query("UPDATE episodes SET lastPositionMs = :positionMs WHERE id = :id")
    suspend fun updatePosition(id: String, positionMs: Long)

    @Query("DELETE FROM episodes WHERE id = :id")
    suspend fun delete(id: String)
}

@Entity(tableName = "episode_content")
data class ContentEntity(
    @PrimaryKey val episodeId: String,
    val researchJson: String?,
    val scriptJson: String?,
    val updatedAt: Long,
    /** The request that produced this draft; lets an interrupted audio stage be resumed after a restart. */
    val requestJson: String? = null,
    /** Characters actually sent to text-to-speech, cumulative across retries. */
    @ColumnInfo(defaultValue = "0") val ttsCharacters: Int = 0,
)

@Dao
interface ContentDao {
    @Query("SELECT * FROM episode_content WHERE episodeId = :id")
    suspend fun get(id: String): ContentEntity?

    /** Creates an empty row if there is none, so the targeted updates below always have a row to change. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensure(entity: ContentEntity)

    @Query("UPDATE episode_content SET researchJson = :json, updatedAt = :now WHERE episodeId = :id")
    suspend fun setResearch(id: String, json: String, now: Long)

    @Query("UPDATE episode_content SET scriptJson = :json, updatedAt = :now WHERE episodeId = :id")
    suspend fun setScript(id: String, json: String, now: Long)

    @Query("UPDATE episode_content SET requestJson = :json, updatedAt = :now WHERE episodeId = :id")
    suspend fun setRequest(id: String, json: String, now: Long)

    @Query("UPDATE episode_content SET ttsCharacters = ttsCharacters + :n, updatedAt = :now WHERE episodeId = :id")
    suspend fun addTtsCharacters(id: String, n: Int, now: Long)

    @Query("DELETE FROM episode_content WHERE episodeId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM episode_content WHERE episodeId NOT IN (SELECT id FROM episodes) AND updatedAt < :before")
    suspend fun deleteUnfinishedBefore(before: Long)

    @Query(
        "SELECT c.* FROM episode_content c LEFT JOIN episodes e ON e.id = c.episodeId " +
            "WHERE e.id IS NULL AND c.scriptJson IS NOT NULL AND c.requestJson IS NOT NULL AND c.updatedAt >= :since " +
            "ORDER BY c.updatedAt DESC LIMIT 1",
    )
    suspend fun latestUnfinished(since: Long): ContentEntity?
}

@Database(entities = [EpisodeEntity::class, ContentEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun episodeDao(): EpisodeDao
    abstract fun contentDao(): ContentDao
}

/** v1 -> v2: adds research/script storage; existing episodes are untouched. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `episode_content` (`episodeId` TEXT NOT NULL, `researchJson` TEXT, " +
                "`scriptJson` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`episodeId`))",
        )
    }
}

/** v2 -> v3: adds the saved request (for resuming audio) and the running count of speech characters sent. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `episode_content` ADD COLUMN `requestJson` TEXT")
        db.execSQL("ALTER TABLE `episode_content` ADD COLUMN `ttsCharacters` INTEGER NOT NULL DEFAULT 0")
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

class RoomContentRepository(
    private val dao: ContentDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : ContentRepository {

    private suspend fun ensure(id: String) = dao.ensure(ContentEntity(id, null, null, clock()))

    override suspend fun saveResearch(episodeId: String, research: ResearchResult) {
        ensure(episodeId)
        dao.setResearch(episodeId, AppJson.encodeToString(research), clock())
    }

    override suspend fun saveScript(episodeId: String, script: PodcastScript) {
        ensure(episodeId)
        dao.setScript(episodeId, AppJson.encodeToString(script), clock())
    }

    override suspend fun saveRequest(episodeId: String, request: PodcastRequest) {
        ensure(episodeId)
        dao.setRequest(episodeId, AppJson.encodeToString(request), clock())
    }

    override suspend fun addTtsCharacters(episodeId: String, characters: Int) {
        if (characters <= 0) return
        ensure(episodeId)
        dao.addTtsCharacters(episodeId, characters, clock())
    }

    override suspend fun delete(episodeId: String) = dao.delete(episodeId)

    override suspend fun purgeUnfinishedBefore(beforeMs: Long) = dao.deleteUnfinishedBefore(beforeMs)

    override suspend fun getResearch(episodeId: String): ResearchResult? =
        dao.get(episodeId)?.researchJson?.let { runCatching { AppJson.decodeFromString<ResearchResult>(it) }.getOrNull() }

    override suspend fun getScript(episodeId: String): PodcastScript? =
        dao.get(episodeId)?.scriptJson?.let { runCatching { AppJson.decodeFromString<PodcastScript>(it) }.getOrNull() }

    override suspend fun getRequest(episodeId: String): PodcastRequest? =
        dao.get(episodeId)?.requestJson?.let { runCatching { AppJson.decodeFromString<PodcastRequest>(it) }.getOrNull() }

    override suspend fun getTtsCharacters(episodeId: String): Int = dao.get(episodeId)?.ttsCharacters ?: 0

    override suspend fun latestUnfinished(sinceMs: Long): UnfinishedDraft? {
        val row = dao.latestUnfinished(sinceMs) ?: return null
        val script = row.scriptJson?.let { runCatching { AppJson.decodeFromString<PodcastScript>(it) }.getOrNull() } ?: return null
        val request = row.requestJson?.let { runCatching { AppJson.decodeFromString<PodcastRequest>(it) }.getOrNull() } ?: return null
        return UnfinishedDraft(row.episodeId, script.title, request, script.segments.size)
    }
}

class RoomEpisodeRepository(private val dao: EpisodeDao) : EpisodeRepository {
    override fun observeEpisodes(): Flow<List<PodcastEpisode>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getEpisode(id: String): PodcastEpisode? = dao.get(id)?.toDomain()
    override suspend fun save(episode: PodcastEpisode) = dao.upsert(episode.toEntity())
    override suspend fun updatePosition(id: String, positionMs: Long) = dao.updatePosition(id, positionMs)
    override suspend fun delete(id: String) = dao.delete(id)
}

private fun EpisodeEntity.toDomain() = PodcastEpisode(
    id = id,
    title = title,
    topic = topic,
    createdAt = createdAt,
    language = Language.entries.firstOrNull { it.code == languageCode } ?: Language.ENGLISH,
    durationSeconds = durationSeconds,
    audioUri = audioUri,
    lastPositionMs = lastPositionMs,
)

private fun PodcastEpisode.toEntity() = EpisodeEntity(
    id = id,
    title = title,
    topic = topic,
    createdAt = createdAt,
    languageCode = language.code,
    durationSeconds = durationSeconds,
    audioUri = audioUri,
    lastPositionMs = lastPositionMs,
)
