package com.framenest.data.listen_translate

import com.framenest.core.model.PlaybackIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repository logic with in-memory fake DAO (no Android Room runtime).
 */
class ListenTranslateRepositoryTest {

    private val identity = PlaybackIdentity("srv-1", "media", "films/a.mkv")
    private val langs = ListenLanguagePair("ja", "zh")

    @Test
    fun upsertCue_storesSrcAndTgt_andIsolatesIdentity() = runBlocking {
        val dao = FakeListenTranslateDao()
        val repo = ListenTranslateRepository(dao, timeSource = { 1_000L })

        repo.upsertCue(
            identity,
            langs,
            startMs = 0L,
            endMs = 2_000L,
            textSrc = "こんにちは",
            textTgt = "你好",
        )
        repo.upsertCue(
            PlaybackIdentity("srv-2", "media", "films/a.mkv"),
            langs,
            startMs = 0L,
            endMs = 2_000L,
            textSrc = "other",
            textTgt = "别的",
        )

        val cues = repo.listCues(identity, langs)
        assertEquals(1, cues.size)
        assertEquals("こんにちは", cues[0].textSrc)
        assertEquals("你好", cues[0].textTgt)
        assertEquals(2, dao.cueRows.size)
    }

    @Test
    fun contentKey_mismatch_invalidatesJobAndCues() = runBlocking {
        val dao = FakeListenTranslateDao()
        val repo = ListenTranslateRepository(dao, timeSource = { 2_000L })
        val key1 = ListenContentKey.of(sizeBytes = 100L, modifiedTimeMs = 10L)
        val key2 = ListenContentKey.of(sizeBytes = 200L, modifiedTimeMs = 10L)

        repo.ensureJob(identity, langs, contentKey = key1)
        repo.upsertCue(identity, langs, 0L, 1_000L, "a", "A", contentKey = key1)
        assertEquals(1, repo.listCues(identity, langs).size)

        val after = repo.getJob(identity, langs, contentKey = key2)
        assertNull(after)
        assertTrue(repo.listCues(identity, langs).isEmpty())
    }

    @Test
    fun newlyKnownContentKey_andModelUpgrade_invalidateLegacyCues() = runBlocking {
        val dao = FakeListenTranslateDao()
        val repo = ListenTranslateRepository(dao, timeSource = { 2_500L })

        repo.ensureJob(identity, langs, asrModel = "vosk-old", mtModel = "mt-1")
        repo.upsertCue(identity, langs, 0L, 1_000L, "old", "旧")
        repo.ensureJob(
            identity,
            langs,
            contentKey = ListenContentKey.of(100L, 20L),
            asrModel = "vosk-new",
            mtModel = "mt-1",
        )

        assertTrue(repo.listCues(identity, langs).isEmpty())
        assertEquals("vosk-new", repo.getJob(identity, langs)?.asrModel)
    }

    @Test
    fun languagePair_isolation() = runBlocking {
        val dao = FakeListenTranslateDao()
        val repo = ListenTranslateRepository(dao, timeSource = { 3_000L })
        val enZh = ListenLanguagePair("en", "zh")
        repo.upsertCue(identity, langs, 0L, 1_000L, "ja", "中")
        repo.upsertCue(identity, enZh, 0L, 1_000L, "en", "中")
        assertEquals(1, repo.listCues(identity, langs).size)
        assertEquals("en", repo.listCues(identity, enZh).single().textSrc)
    }

    @Test
    fun purgeMedia_and_purgeServer() = runBlocking {
        val dao = FakeListenTranslateDao()
        val repo = ListenTranslateRepository(dao, timeSource = { 4_000L })
        repo.upsertCue(identity, langs, 0L, 1_000L, "a", "A")
        repo.upsertCue(
            PlaybackIdentity("srv-1", "media", "other.mkv"),
            langs,
            0L,
            1_000L,
            "b",
            "B",
        )
        repo.upsertCue(
            PlaybackIdentity("srv-2", "media", "x.mkv"),
            langs,
            0L,
            1_000L,
            "c",
            "C",
        )

        repo.purgeMedia(identity)
        assertTrue(repo.listCues(identity, langs).isEmpty())
        assertEquals(1, repo.listCues(PlaybackIdentity("srv-1", "media", "other.mkv"), langs).size)

        repo.purgeServer("srv-1")
        assertEquals(1, repo.countJobs())
        // srv-2 remains
        assertEquals(1, repo.listCues(PlaybackIdentity("srv-2", "media", "x.mkv"), langs).size)
    }

    @Test
    fun purgeAll_clearsEverything() = runBlocking {
        val dao = FakeListenTranslateDao()
        val repo = ListenTranslateRepository(dao, timeSource = { 5_000L })
        repo.upsertCue(identity, langs, 0L, 1_000L, "a", "A")
        repo.purgeAll()
        assertEquals(0, repo.countCues())
        assertEquals(0, repo.countJobs())
    }

    @Test
    fun enforceMaxJobs_dropsOldest() = runBlocking {
        val dao = FakeListenTranslateDao()
        var t = 10_000L
        val repo = ListenTranslateRepository(dao, timeSource = { t })
        for (i in 0 until 5) {
            t = 10_000L + i
            repo.ensureJob(
                PlaybackIdentity("s", "media", "v$i.mkv"),
                langs,
                contentKey = "k$i",
            )
        }
        assertEquals(5, repo.countJobs())
        val removed = repo.enforceMaxJobs(maxJobs = 2)
        assertEquals(3, removed)
        assertEquals(2, repo.countJobs())
        // Oldest v0..v2 gone; v3,v4 remain
        assertNull(repo.getJob(PlaybackIdentity("s", "media", "v0.mkv"), langs))
        assertNotNull(repo.getJob(PlaybackIdentity("s", "media", "v4.mkv"), langs))
    }

    @Test
    fun upsertCue_sameRange_replaces_increasesRev() = runBlocking {
        val dao = FakeListenTranslateDao()
        val repo = ListenTranslateRepository(dao, timeSource = { 6_000L })
        repo.upsertCue(identity, langs, 0L, 2_000L, "partial", "部", rev = 1)
        repo.upsertCue(identity, langs, 0L, 2_000L, "final", "终", rev = 2)
        val cues = repo.listCues(identity, langs)
        assertEquals(1, cues.size)
        assertEquals("final", cues[0].textSrc)
        assertEquals(2, cues[0].rev)
    }

    @Test
    fun upsertCue_reusesSingleJobLookup_andSingleParentWrite() = runBlocking {
        val dao = FakeListenTranslateDao()
        val repo = ListenTranslateRepository(dao, timeSource = { 6_500L })

        repo.upsertCue(identity, langs, 0L, 2_000L, "first", "一")
        assertEquals(1, dao.getJobCount)
        assertEquals(1, dao.upsertJobCount)

        dao.getJobCount = 0
        dao.upsertJobCount = 0
        repo.upsertCue(identity, langs, 2_000L, 4_000L, "second", "二")

        assertEquals(1, dao.getJobCount)
        assertEquals(1, dao.upsertJobCount)
    }

    @Test
    fun path_normalized_forStorage() = runBlocking {
        val dao = FakeListenTranslateDao()
        val repo = ListenTranslateRepository(dao, timeSource = { 7_000L })
        repo.upsertCue(
            PlaybackIdentity("s", "media", "/foo/bar.mp4"),
            langs,
            0L,
            1_000L,
            "x",
            "y",
        )
        val loaded = repo.listCues(PlaybackIdentity("s", "media", "foo/bar.mp4"), langs)
        assertEquals(1, loaded.size)
        assertEquals("foo/bar.mp4", loaded[0].identity.path)
    }

    @Test
    fun listenContentKey_stable() {
        assertEquals("s10_m20", ListenContentKey.of(10L, 20L))
        assertEquals("", ListenContentKey.of(null, null))
    }
}

/** In-memory [ListenTranslateDao] for unit tests. */
internal class FakeListenTranslateDao : ListenTranslateDao {
    val jobRows = linkedMapOf<String, ListenTranslateJobEntity>()
    val cueRows = mutableListOf<ListenTranslateCueEntity>()
    private var nextCueId = 1L
    var getJobCount: Int = 0
    var upsertJobCount: Int = 0
    private val cueFlows =
        mutableMapOf<String, MutableStateFlow<List<ListenTranslateCueEntity>>>()

    private fun jobKey(
        serverId: String,
        share: String,
        path: String,
        sourceLang: String,
        targetLang: String,
    ) = "$serverId|$share|$path|$sourceLang|$targetLang"

    private fun mediaKey(serverId: String, share: String, path: String) =
        "$serverId|$share|$path"

    private fun refreshFlow(
        serverId: String,
        share: String,
        path: String,
        sourceLang: String,
        targetLang: String,
    ) {
        val k = jobKey(serverId, share, path, sourceLang, targetLang)
        val list = cueRows.filter {
            it.serverId == serverId && it.share == share && it.path == path &&
                it.sourceLang == sourceLang && it.targetLang == targetLang
        }.sortedBy { it.startMs }
        cueFlows.getOrPut(k) { MutableStateFlow(list) }.value = list
    }

    override suspend fun upsertJob(entity: ListenTranslateJobEntity) {
        upsertJobCount++
        jobRows[
            jobKey(
                entity.serverId,
                entity.share,
                entity.path,
                entity.sourceLang,
                entity.targetLang,
            ),
        ] = entity
    }

    override suspend fun getJob(
        serverId: String,
        share: String,
        path: String,
        sourceLang: String,
        targetLang: String,
    ): ListenTranslateJobEntity? {
        getJobCount++
        return jobRows[jobKey(serverId, share, path, sourceLang, targetLang)]
    }

    override suspend fun listJobsForMedia(
        serverId: String,
        share: String,
        path: String,
    ): List<ListenTranslateJobEntity> =
        jobRows.values.filter {
            it.serverId == serverId && it.share == share && it.path == path
        }

    override suspend fun listOldestJobs(limit: Int): List<ListenTranslateJobEntity> =
        jobRows.values.sortedBy { it.updatedAtEpochMs }.take(limit)

    override suspend fun countJobs(): Int = jobRows.size

    override suspend fun deleteJob(
        serverId: String,
        share: String,
        path: String,
        sourceLang: String,
        targetLang: String,
    ) {
        jobRows.remove(jobKey(serverId, share, path, sourceLang, targetLang))
        cueRows.removeAll {
            it.serverId == serverId && it.share == share && it.path == path &&
                it.sourceLang == sourceLang && it.targetLang == targetLang
        }
        refreshFlow(serverId, share, path, sourceLang, targetLang)
    }

    override suspend fun deleteJobsForMedia(serverId: String, share: String, path: String) {
        val keys = jobRows.keys.filter { it.startsWith(mediaKey(serverId, share, path) + "|") }
        for (k in keys) {
            val parts = k.split("|")
            deleteJob(parts[0], parts[1], parts[2], parts[3], parts[4])
        }
    }

    override suspend fun deleteJobsForServer(serverId: String) {
        val snapshot = jobRows.values.filter { it.serverId == serverId }.toList()
        for (j in snapshot) {
            deleteJob(j.serverId, j.share, j.path, j.sourceLang, j.targetLang)
        }
    }

    override suspend fun deleteJobsOlderThan(epochMs: Long) {
        val snapshot = jobRows.values.filter { it.updatedAtEpochMs < epochMs }.toList()
        for (j in snapshot) {
            deleteJob(j.serverId, j.share, j.path, j.sourceLang, j.targetLang)
        }
    }

    override suspend fun deleteAllJobs() {
        jobRows.clear()
        cueRows.clear()
        cueFlows.values.forEach { it.value = emptyList() }
    }

    override suspend fun insertCue(entity: ListenTranslateCueEntity): Long {
        val id = if (entity.id == 0L) nextCueId++ else entity.id
        val stored = entity.copy(id = id)
        cueRows.removeAll { it.id == id }
        cueRows.add(stored)
        refreshFlow(
            entity.serverId,
            entity.share,
            entity.path,
            entity.sourceLang,
            entity.targetLang,
        )
        return id
    }

    override fun observeCues(
        serverId: String,
        share: String,
        path: String,
        sourceLang: String,
        targetLang: String,
    ): Flow<List<ListenTranslateCueEntity>> {
        val k = jobKey(serverId, share, path, sourceLang, targetLang)
        refreshFlow(serverId, share, path, sourceLang, targetLang)
        return cueFlows.getOrPut(k) { MutableStateFlow(emptyList()) }
    }

    override suspend fun listCues(
        serverId: String,
        share: String,
        path: String,
        sourceLang: String,
        targetLang: String,
    ): List<ListenTranslateCueEntity> =
        cueRows.filter {
            it.serverId == serverId && it.share == share && it.path == path &&
                it.sourceLang == sourceLang && it.targetLang == targetLang
        }.sortedBy { it.startMs }

    override suspend fun cueAt(
        serverId: String,
        share: String,
        path: String,
        sourceLang: String,
        targetLang: String,
        timeMs: Long,
    ): ListenTranslateCueEntity? =
        listCues(serverId, share, path, sourceLang, targetLang)
            .lastOrNull { it.startMs <= timeMs && it.endMs > timeMs }

    override suspend fun deleteCuesAtRange(
        serverId: String,
        share: String,
        path: String,
        sourceLang: String,
        targetLang: String,
        startMs: Long,
        endMs: Long,
    ) {
        cueRows.removeAll {
            it.serverId == serverId && it.share == share && it.path == path &&
                it.sourceLang == sourceLang && it.targetLang == targetLang &&
                it.startMs == startMs && it.endMs == endMs
        }
        refreshFlow(serverId, share, path, sourceLang, targetLang)
    }

    override suspend fun countCues(): Int = cueRows.size

    override suspend fun sumTextChars(): Long =
        cueRows.sumOf { it.textSrc.length.toLong() + it.textTgt.length.toLong() }
}
