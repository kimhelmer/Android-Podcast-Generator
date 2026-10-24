package com.podcaststudio.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.podcaststudio.app.data.local.AppDatabase
import com.podcaststudio.app.data.local.ALL_MIGRATIONS
import com.podcaststudio.app.data.local.RoomContentRepository
import com.podcaststudio.app.data.local.RoomEpisodeRepository
import com.podcaststudio.app.domain.Language
import com.podcaststudio.app.domain.PodcastEpisode
import com.podcaststudio.app.domain.PodcastScript
import com.podcaststudio.app.domain.ScriptSegment
import com.podcaststudio.app.domain.Speaker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationAndContentTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var db: AppDatabase? = null

    @After fun tearDown() {
        db?.close()
        ctx.deleteDatabase("migration-test.db")
    }

    @Test fun existingM1AInstallKeepsEpisodesAfterUpgrade() {
        // Build a database exactly as the M1A app (schema version 1) left it on the phone.
        ctx.deleteDatabase("migration-test.db")
        val raw = SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath("migration-test.db"), null)
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS `episodes` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `topic` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `languageCode` TEXT NOT NULL, `durationSeconds` INTEGER NOT NULL, " +
                "`audioUri` TEXT NOT NULL, `lastPositionMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        raw.execSQL("INSERT INTO episodes VALUES ('sample-episode','Sample','topic',1000,'en',24,'android.resource://x/1',7777)")
        raw.version = 1
        raw.close()

        val upgraded = Room.databaseBuilder(ctx, AppDatabase::class.java, "migration-test.db")
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        db = upgraded

        val ep = runBlocking { RoomEpisodeRepository(upgraded.episodeDao()).getEpisode("sample-episode") }
        assertEquals("Sample", ep!!.title)
        assertEquals(7777L, ep.lastPositionMs) // resume position survives
        assertNull(runBlocking { RoomContentRepository(upgraded.contentDao()).getScript("sample-episode") })
    }

    private fun script() = PodcastScript(
        title = "T", summary = "S", language = Language.DANISH,
        segments = listOf(ScriptSegment("s01", Speaker.HOST, "Hej", "warm", "Introduction")),
        sources = listOf(com.podcaststudio.app.domain.SourceRef("Kilde", "https://example.org/k")),
        researchedAt = 1, generatedAt = 2, researchModel = "rm", scriptModel = "sm",
        targetWords = 750, estimatedDurationSeconds = 300, warnings = listOf("w"),
    )

    @Test fun researchAndScriptRoundTripAndDoNotOverwriteEachOther() = runBlocking {
        val d = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        db = d
        val repo = RoomContentRepository(d.contentDao())

        repo.saveResearch("e1", sampleResearch())
        assertNull(repo.getScript("e1"))
        repo.saveScript("e1", script())

        assertEquals(sampleResearch(), repo.getResearch("e1")) // research still there after script saved
        assertEquals(script(), repo.getScript("e1"))
        assertNull(repo.getResearch("other"))
    }

    // ---------------------------------------------------------------- v2 -> v3 and the new content columns

    private fun createV2Database(name: String, withContent: Boolean = true): java.io.File {
        ctx.deleteDatabase(name)
        val file = ctx.getDatabasePath(name)
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS `episodes` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `topic` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `languageCode` TEXT NOT NULL, `durationSeconds` INTEGER NOT NULL, " +
                "`audioUri` TEXT NOT NULL, `lastPositionMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS `episode_content` (`episodeId` TEXT NOT NULL, `researchJson` TEXT, " +
                "`scriptJson` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`episodeId`))",
        )
        raw.execSQL("INSERT INTO episodes VALUES ('e1','M1C episode','topic',1000,'da',300,'file:///x.wav',4242)")
        if (withContent) raw.execSQL("INSERT INTO episode_content VALUES ('e1', NULL, '{\"keep\":true}', 5)")
        raw.version = 2
        raw.close()
        return file
    }

    @Test fun anM1CInstallUpgradesToV3KeepingEpisodesPositionsAndStoredScripts() {
        createV2Database("migration-v3.db")
        val upgraded = Room.databaseBuilder(ctx, AppDatabase::class.java, "migration-v3.db")
            .addMigrations(*ALL_MIGRATIONS).allowMainThreadQueries().build()
        db = upgraded

        val ep = runBlocking { RoomEpisodeRepository(upgraded.episodeDao()).getEpisode("e1") }!!
        assertEquals(4242L, ep.lastPositionMs)
        assertEquals(Language.DANISH, ep.language)

        val row = runBlocking { upgraded.contentDao().get("e1") }!!
        assertEquals("{\"keep\":true}", row.scriptJson)
        assertNull(row.requestJson) // old rows have no saved request, so they are never offered for resuming
        assertEquals(0, row.ttsCharacters)
        ctx.deleteDatabase("migration-v3.db")
    }

    @Test fun oldRowsWithoutARequestAreNeverOfferedAsUnfinishedDrafts() {
        createV2Database("migration-v3b.db")
        val upgraded = Room.databaseBuilder(ctx, AppDatabase::class.java, "migration-v3b.db")
            .addMigrations(*ALL_MIGRATIONS).allowMainThreadQueries().build()
        db = upgraded
        assertNull(runBlocking { RoomContentRepository(upgraded.contentDao()).latestUnfinished(0) })
        ctx.deleteDatabase("migration-v3b.db")
    }

    private fun script(title: String = "T") = PodcastScript(
        title = title, summary = "S", language = Language.ENGLISH,
        segments = listOf(ScriptSegment("s01", Speaker.HOST, "Hi")),
        sources = emptyList(), researchedAt = 1, generatedAt = 2, researchModel = "rm", scriptModel = "sm",
        targetWords = 750, estimatedDurationSeconds = 300,
    )

    private fun inMemory(): AppDatabase {
        val d = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        db = d
        return d
    }

    @Test fun requestRoundTripsIncludingBothVoices() = runBlocking {
        val repo = RoomContentRepository(inMemory().contentDao())
        repo.saveRequest("e1", enRequest)
        assertEquals(enRequest, repo.getRequest("e1"))
        assertNull(repo.getRequest("nope"))
    }

    @Test fun savingAnyPartNeverErasesTheOthers() = runBlocking {
        val repo = RoomContentRepository(inMemory().contentDao())
        repo.saveRequest("e1", enRequest)
        repo.saveResearch("e1", sampleResearch())
        repo.saveScript("e1", script())
        repo.addTtsCharacters("e1", 100)
        repo.saveResearch("e1", sampleResearch()) // saving again must not wipe script, request or usage
        assertEquals(enRequest, repo.getRequest("e1"))
        assertEquals(script(), repo.getScript("e1"))
        assertEquals(100, repo.getTtsCharacters("e1"))
    }

    @Test fun actualCharactersAccumulateAcrossRetries() = runBlocking {
        val repo = RoomContentRepository(inMemory().contentDao())
        assertEquals(0, repo.getTtsCharacters("e1"))
        repo.addTtsCharacters("e1", 250)
        repo.addTtsCharacters("e1", 0) // reused clips add nothing
        repo.addTtsCharacters("e1", 125)
        assertEquals(375, repo.getTtsCharacters("e1"))
    }

    @Test fun anUnfinishedDraftNeedsScriptAndRequestAndNoEpisode() = runBlocking {
        val d = inMemory()
        val repo = RoomContentRepository(d.contentDao(), clock = { 1_000 })
        assertNull(repo.latestUnfinished(0))

        repo.saveResearch("e1", sampleResearch())
        assertNull("research alone is not resumable", repo.latestUnfinished(0))
        repo.saveScript("e1", script("The Waggle Dance"))
        assertNull("a script without its request cannot be resumed", repo.latestUnfinished(0))
        repo.saveRequest("e1", enRequest)

        val draft = repo.latestUnfinished(0)!!
        assertEquals("e1", draft.id)
        assertEquals("The Waggle Dance", draft.title)
        assertEquals(enRequest, draft.request)
        assertEquals(1, draft.segmentCount)

        assertNull("too old to offer", repo.latestUnfinished(2_000))

        RoomEpisodeRepository(d.episodeDao()).save(
            PodcastEpisode("e1", "T", "t", 1, Language.ENGLISH, 60, "file:///x.wav"),
        )
        assertNull("finished episodes are no longer drafts", repo.latestUnfinished(0))
    }

    @Test fun theNewestDraftWins() = runBlocking {
        var t = 0L
        val repo = RoomContentRepository(inMemory().contentDao(), clock = { ++t })
        for (id in listOf("old", "new")) {
            repo.saveRequest(id, enRequest); repo.saveScript(id, script(id))
        }
        assertEquals("new", repo.latestUnfinished(0)!!.id)
    }

    @Test fun deletingContentRemovesTheDraft() = runBlocking {
        val repo = RoomContentRepository(inMemory().contentDao())
        repo.saveRequest("e1", enRequest); repo.saveScript("e1", script())
        repo.delete("e1")
        assertNull(repo.latestUnfinished(0))
        assertNull(repo.getScript("e1"))
    }

    @Test fun oldUnfinishedContentIsPurgedButFinishedAndRecentContentIsKept() = runBlocking {
        var t = 1_000L
        val d = inMemory()
        val repo = RoomContentRepository(d.contentDao(), clock = { t })
        RoomEpisodeRepository(d.episodeDao()).save(PodcastEpisode("done", "T", "t", 1, Language.ENGLISH, 60, "file:///x.wav"))
        repo.saveScript("done", script("done")); repo.saveRequest("done", enRequest)       // finished episode, old
        t = 5_000; repo.saveScript("stale", script("stale")); repo.saveRequest("stale", enRequest) // unfinished, old
        t = 90_000; repo.saveScript("recent", script("recent")); repo.saveRequest("recent", enRequest) // unfinished, new

        repo.purgeUnfinishedBefore(50_000)

        assertNull(repo.getScript("stale"))
        assertEquals("recent", repo.getScript("recent")!!.title)
        assertEquals("done", repo.getScript("done")!!.title) // a finished episode's script is never purged
    }
}
