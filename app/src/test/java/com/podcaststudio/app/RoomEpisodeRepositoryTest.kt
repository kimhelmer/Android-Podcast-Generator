package com.podcaststudio.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.podcaststudio.app.data.local.AppDatabase
import com.podcaststudio.app.data.local.RoomEpisodeRepository
import com.podcaststudio.app.domain.Language
import com.podcaststudio.app.domain.PodcastEpisode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomEpisodeRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: RoomEpisodeRepository

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = RoomEpisodeRepository(db.episodeDao())
    }

    @After fun tearDown() = db.close()

    private fun ep(id: String, at: Long, lang: Language = Language.DANISH) =
        PodcastEpisode(id, "Title $id", "topic $id", at, lang, 600, "android.resource://x/1")

    @Test fun savedEpisodeRoundTripsAllFields() = runBlocking {
        val e = ep("a", 1000).copy(lastPositionMs = 5500)
        repo.save(e)
        assertEquals(e, repo.getEpisode("a"))
    }

    @Test fun listIsNewestFirst() = runBlocking {
        repo.save(ep("old", 1)); repo.save(ep("new", 3)); repo.save(ep("mid", 2))
        assertEquals(listOf("new", "mid", "old"), repo.observeEpisodes().first().map { it.id })
    }

    @Test fun savingSameIdUpdatesRow() = runBlocking {
        repo.save(ep("a", 1)); repo.save(ep("a", 1).copy(title = "Renamed"))
        val all = repo.observeEpisodes().first()
        assertEquals(1, all.size)
        assertEquals("Renamed", all[0].title)
    }

    @Test fun positionIsPersistedForResume() = runBlocking {
        repo.save(ep("a", 1))
        repo.updatePosition("a", 12_345)
        assertEquals(12_345L, repo.getEpisode("a")!!.lastPositionMs)
    }

    @Test fun languageIsPreserved() = runBlocking {
        repo.save(ep("da", 1, Language.DANISH)); repo.save(ep("en", 2, Language.ENGLISH))
        assertEquals(Language.DANISH, repo.getEpisode("da")!!.language)
        assertEquals(Language.ENGLISH, repo.getEpisode("en")!!.language)
    }

    @Test fun deleteRemovesEpisode() = runBlocking {
        repo.save(ep("a", 1)); repo.delete("a")
        assertNull(repo.getEpisode("a"))
    }
}
