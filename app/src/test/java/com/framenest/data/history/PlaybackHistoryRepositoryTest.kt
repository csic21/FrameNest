package com.framenest.data.history

import com.framenest.core.model.PlaybackIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repository logic tests using an in-memory fake DAO (no Android Room runtime).
 */
class PlaybackHistoryRepositoryTest {

    @Test
    fun saveProgress_upsertsByIdentityAndMarksCompleted() = runBlocking {
        val dao = FakePlaybackHistoryDao()
        val repo = PlaybackHistoryRepository(dao, timeSource = { 1_000L })
        val identity = PlaybackIdentity("srv-1", "media", "films/a.mkv")

        val mid = repo.saveProgress(identity, "A.mkv", positionMs = 40_000L, durationMs = 100_000L)
        assertFalse(mid.completed)
        assertEquals(40_000L, mid.positionMs)

        val nearEnd = repo.saveProgress(identity, "A.mkv", positionMs = 95_000L, durationMs = 100_000L)
        assertTrue(nearEnd.completed)

        val loaded = repo.get(identity)
        assertNotNull(loaded)
        assertTrue(loaded!!.completed)
        assertEquals(2, dao.upsertCount)
        assertEquals(1, dao.rows.size)
        assertEquals(0L, loaded.resumePositionMs)
    }

    @Test
    fun identity_normalizesPath() = runBlocking {
        val dao = FakePlaybackHistoryDao()
        val repo = PlaybackHistoryRepository(dao, timeSource = { 2_000L })
        val a = PlaybackIdentity("s", "media", "/foo/bar.mp4")
        val b = PlaybackIdentity("s", "media", "foo/bar.mp4")
        repo.saveProgress(a, "bar.mp4", 10_000L, 60_000L)
        val loaded = repo.get(b)
        assertNotNull(loaded)
        assertEquals("foo/bar.mp4", loaded!!.identity.path)
    }

    @Test
    fun uniqueKey_isServerSharePath() = runBlocking {
        val dao = FakePlaybackHistoryDao()
        val repo = PlaybackHistoryRepository(dao, timeSource = { 3_000L })
        repo.saveProgress(PlaybackIdentity("s1", "media", "a.mp4"), "a", 1L, 10L)
        repo.saveProgress(PlaybackIdentity("s2", "media", "a.mp4"), "a", 2L, 10L)
        repo.saveProgress(PlaybackIdentity("s1", "other", "a.mp4"), "a", 3L, 10L)
        assertEquals(3, dao.rows.size)
    }
}

private class FakePlaybackHistoryDao : PlaybackHistoryDao {
    val rows = linkedMapOf<String, PlaybackHistoryEntity>()
    var upsertCount: Int = 0

    private fun key(serverId: String, share: String, path: String) = "$serverId|$share|$path"

    override suspend fun upsert(entity: PlaybackHistoryEntity) {
        upsertCount++
        rows[key(entity.serverId, entity.share, entity.path)] = entity
    }

    override suspend fun get(serverId: String, share: String, path: String): PlaybackHistoryEntity? =
        rows[key(serverId, share, path)]

    override fun observeRecent(limit: Int) = throw UnsupportedOperationException("not needed")

    override suspend fun listRecent(limit: Int): List<PlaybackHistoryEntity> =
        rows.values.sortedByDescending { it.updatedAtEpochMs }.take(limit)

    override suspend fun delete(serverId: String, share: String, path: String) {
        rows.remove(key(serverId, share, path))
    }

    override suspend fun clearAll() {
        rows.clear()
    }
}
