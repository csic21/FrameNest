package com.framenest.data.listen_translate

import com.framenest.core.model.PlaybackIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Local-only listen-translate cache (Room). Never writes to NAS or public storage.
 *
 * Cleanup: [purgeMedia], [purgeServer], [purgeAll], [purgeOlderThan], [enforceMaxJobs].
 * Uninstall clears the DB with the rest of app private data (decision 0005).
 */
class ListenTranslateRepository(
    private val dao: ListenTranslateDao,
    private val timeSource: () -> Long = { System.currentTimeMillis() },
) {
    private val writeMutex = Mutex()
    private val leaseLock = Any()
    private val leases = mutableSetOf<CacheLease>()

    class CacheLease internal constructor(internal val key: List<String>) : AutoCloseable {
        @Volatile internal var active = true
        override fun close() { active = false }
    }

    private fun key(identity: PlaybackIdentity, languages: ListenLanguagePair): List<String> =
        languages.normalized().let { listOf(identity.serverId, identity.share, identity.normalizedPath(), it.sourceLang, it.targetLang) }

    fun acquireLease(identity: PlaybackIdentity, languages: ListenLanguagePair): CacheLease =
        synchronized(leaseLock) {
            leases.removeAll { !it.active }
            CacheLease(key(identity, languages)).also { leases += it }
        }

    private fun checkLease(lease: CacheLease?, identity: PlaybackIdentity, languages: ListenLanguagePair) {
        if (lease != null && (!lease.active || lease.key != key(identity, languages))) {
            throw CancellationException("listen cache lease retired")
        }
    }

    private fun revokeLeases(matches: (List<String>) -> Boolean) = synchronized(leaseLock) {
        leases.filter { matches(it.key) }.forEach { it.close() }
        leases.removeAll { !it.active }
    }

    /**
     * Load job for [identity] + [languages]. If [contentKey] is non-blank and differs
     * from the stored key, the job and cues are dropped and this returns null.
     */
    suspend fun getJob(
        identity: PlaybackIdentity,
        languages: ListenLanguagePair,
        contentKey: String = "",
    ): ListenTranslateJob? = writeMutex.withLock {
        val path = identity.normalizedPath()
        val lang = languages.normalized()
        val row = dao.getJob(
            identity.serverId,
            identity.share,
            path,
            lang.sourceLang,
            lang.targetLang,
        ) ?: return@withLock null
        if (shouldInvalidate(row.contentKey, contentKey)) {
            dao.deleteJob(
                identity.serverId,
                identity.share,
                path,
                lang.sourceLang,
                lang.targetLang,
            )
            return@withLock null
        }
        return@withLock row.toModel()
    }

    /**
     * Create or refresh job metadata. Invalidates cues when [contentKey] changes.
     */
    suspend fun ensureJob(
        identity: PlaybackIdentity,
        languages: ListenLanguagePair,
        contentKey: String = "",
        durationMs: Long = 0L,
        asrModel: String = "",
        mtModel: String = "",
        status: ListenTranslateJobStatus = ListenTranslateJobStatus.Idle,
        lease: CacheLease? = null,
    ): ListenTranslateJob = writeMutex.withLock {
        checkLease(lease, identity, languages)
        ensureJobLocked(identity, languages, contentKey, durationMs, asrModel, mtModel, status, lease)
    }

    private suspend fun ensureJobLocked(
        identity: PlaybackIdentity,
        languages: ListenLanguagePair,
        contentKey: String = "",
        durationMs: Long = 0L,
        asrModel: String = "",
        mtModel: String = "",
        status: ListenTranslateJobStatus = ListenTranslateJobStatus.Idle,
        lease: CacheLease? = null,
    ): ListenTranslateJob {
        val path = identity.normalizedPath()
        val lang = languages.normalized()
        val existing = dao.getJob(
            identity.serverId,
            identity.share,
            path,
            lang.sourceLang,
            lang.targetLang,
        )
        val invalidated = existing != null &&
            (shouldInvalidate(existing.contentKey, contentKey) ||
                shouldInvalidateModel(existing.asrModel, asrModel) ||
                shouldInvalidateModel(existing.mtModel, mtModel))
        if (invalidated) {
            synchronized(leaseLock) {
                leases.filter { it !== lease && it.key == key(identity, lang) }.forEach { it.close() }
                leases.removeAll { !it.active }
            }
            dao.deleteJob(
                identity.serverId,
                identity.share,
                path,
                lang.sourceLang,
                lang.targetLang,
            )
        }
        // Reuse the primary-key lookup above. A second identical query used to run
        // for every three-second listen window even when nothing was invalidated.
        val previous = existing.takeUnless { invalidated }
        val entity = ListenTranslateJobEntity(
            serverId = identity.serverId,
            share = identity.share,
            path = path,
            sourceLang = lang.sourceLang,
            targetLang = lang.targetLang,
            contentKey = contentKey.ifBlank { previous?.contentKey.orEmpty() },
            status = status.name,
            durationMs = durationMs.coerceAtLeast(previous?.durationMs ?: 0L),
            coveredUntilMs = previous?.coveredUntilMs ?: 0L,
            asrModel = asrModel.ifBlank { previous?.asrModel.orEmpty() },
            mtModel = mtModel.ifBlank { previous?.mtModel.orEmpty() },
            updatedAtEpochMs = timeSource(),
            lastError = previous?.lastError.orEmpty(),
        )
        dao.upsertJob(entity)
        maintainCacheLocked(protectedKey = key(identity, lang))
        return entity.toModel()
    }

    suspend fun updateProgress(
        identity: PlaybackIdentity,
        languages: ListenLanguagePair,
        coveredUntilMs: Long,
        status: ListenTranslateJobStatus? = null,
        lastError: String? = null,
        durationMs: Long? = null,
    ) = writeMutex.withLock {
        val path = identity.normalizedPath()
        val lang = languages.normalized()
        dao.updateJobProgress(
            serverId = identity.serverId,
            share = identity.share,
            path = path,
            sourceLang = lang.sourceLang,
            targetLang = lang.targetLang,
            coveredUntilMs = coveredUntilMs.coerceAtLeast(0L),
            status = status?.name,
            lastError = lastError,
            durationMs = durationMs?.coerceAtLeast(0L),
            updatedAtEpochMs = timeSource(),
        )
    }

    /**
     * Insert or replace a cue with the same [startMs]/[endMs] range (partial → final).
     * Ensures a parent job exists.
     */
    suspend fun upsertCue(
        identity: PlaybackIdentity,
        languages: ListenLanguagePair,
        startMs: Long,
        endMs: Long,
        textSrc: String,
        textTgt: String,
        rev: Int = 1,
        contentKey: String = "",
    ): ListenTranslateCue = writeMutex.withLock {
        require(endMs >= startMs) { "endMs >= startMs" }
        val path = identity.normalizedPath()
        val lang = languages.normalized()
        ensureJobLocked(identity, lang, contentKey = contentKey)
        val entity = cueEntity(identity, lang, path, startMs, endMs, textSrc, textTgt, rev)
        val id = dao.replaceCueAtRange(entity)
        // ensureJob already refreshes updatedAt immediately before the cue write;
        // querying and upserting the same parent again only added two Room round trips.
        return@withLock entity.copy(id = id).toModel()
    }

    /**
     * Session fast path after activation has validated content/model identity and created the job.
     * Missing/cleared parents stop stale work; only a fresh activation may recreate a job.
     */
    suspend fun upsertCueForExistingJob(
        identity: PlaybackIdentity,
        languages: ListenLanguagePair,
        startMs: Long,
        endMs: Long,
        textSrc: String,
        textTgt: String,
        rev: Int = 1,
        contentKey: String = "",
        asrModel: String = "",
        mtModel: String = "",
        lease: CacheLease? = null,
    ): ListenTranslateCue = writeMutex.withLock {
        checkLease(lease, identity, languages)
        require(endMs >= startMs) { "endMs >= startMs" }
        val path = identity.normalizedPath()
        val lang = languages.normalized()
        val entity = cueEntity(identity, lang, path, startMs, endMs, textSrc, textTgt, rev)
        var id = dao.replaceCueForExistingJob(entity, timeSource())
        return@withLock entity.copy(id = (id ?: throw CancellationException("listen cache was cleared"))).toModel()
    }

    /** Commit a whole-window coverage cue and its progress in one transaction. */
    suspend fun completeWindowCueForExistingJob(
        identity: PlaybackIdentity,
        languages: ListenLanguagePair,
        startMs: Long,
        endMs: Long,
        textSrc: String,
        textTgt: String,
        rev: Int,
        coveredUntilMs: Long,
        durationMs: Long?,
        contentKey: String = "",
        asrModel: String = "",
        mtModel: String = "",
        lease: CacheLease? = null,
    ): ListenTranslateCue = writeMutex.withLock {
        checkLease(lease, identity, languages)
        require(endMs >= startMs) { "endMs >= startMs" }
        val path = identity.normalizedPath()
        val lang = languages.normalized()
        val entity = cueEntity(identity, lang, path, startMs, endMs, textSrc, textTgt, rev)
        var id = dao.replaceCueAndUpdateProgressForExistingJob(
            entity = entity,
            coveredUntilMs = coveredUntilMs.coerceAtLeast(0L),
            status = ListenTranslateJobStatus.Partial.name,
            durationMs = durationMs?.coerceAtLeast(0L),
            updatedAtEpochMs = timeSource(),
        )
        return@withLock entity.copy(id = (id ?: throw CancellationException("listen cache was cleared"))).toModel()
    }

    fun observeCues(
        identity: PlaybackIdentity,
        languages: ListenLanguagePair,
    ): Flow<List<ListenTranslateCue>> {
        val path = identity.normalizedPath()
        val lang = languages.normalized()
        return dao.observeCues(
            identity.serverId,
            identity.share,
            path,
            lang.sourceLang,
            lang.targetLang,
        ).map { list -> list.map { it.toModel() } }
    }

    suspend fun listCues(
        identity: PlaybackIdentity,
        languages: ListenLanguagePair,
    ): List<ListenTranslateCue> {
        val path = identity.normalizedPath()
        val lang = languages.normalized()
        return dao.listCues(
            identity.serverId,
            identity.share,
            path,
            lang.sourceLang,
            lang.targetLang,
        ).map { it.toModel() }
    }

    suspend fun cueAt(
        identity: PlaybackIdentity,
        languages: ListenLanguagePair,
        timeMs: Long,
    ): ListenTranslateCue? {
        val path = identity.normalizedPath()
        val lang = languages.normalized()
        return dao.cueAt(
            identity.serverId,
            identity.share,
            path,
            lang.sourceLang,
            lang.targetLang,
            timeMs,
        )?.toModel()
    }

    /** Drop all language pairs for this media (open failed / file gone). */
    suspend fun purgeMedia(identity: PlaybackIdentity) = writeMutex.withLock {
        revokeLeases { it.take(3) == listOf(identity.serverId, identity.share, identity.normalizedPath()) }
        dao.deleteJobsForMedia(
            identity.serverId,
            identity.share,
            identity.normalizedPath(),
        )
    }

    /** Drop all jobs for a deleted server. */
    suspend fun purgeServer(serverId: String) = writeMutex.withLock {
        if (serverId.isBlank()) return@withLock
        revokeLeases { it[0] == serverId }
        dao.deleteJobsForServer(serverId)
    }

    suspend fun purgeAll() = writeMutex.withLock {
        revokeLeases { true }
        dao.deleteAllJobs()
    }

    suspend fun purgeOlderThan(epochMs: Long) = writeMutex.withLock {
        maintainCacheLocked(maxJobs = Int.MAX_VALUE, oldestAllowed = epochMs)
    }

    suspend fun enforceMaxJobs(maxJobs: Int = DEFAULT_MAX_JOBS): Int = writeMutex.withLock {
        require(maxJobs >= 0)
        maintainCacheLocked(maxJobs = maxJobs, oldestAllowed = Long.MIN_VALUE)
    }

    private suspend fun maintainCacheLocked(
        maxJobs: Int = DEFAULT_MAX_JOBS,
        oldestAllowed: Long = timeSource() - RETENTION_MS,
        protectedKey: List<String>? = null,
    ): Int {
        val jobs = dao.listOldestJobs(Int.MAX_VALUE)
        var remaining = jobs.size
        var removed = 0
        for (job in jobs) {
            val jobKey = listOf(job.serverId, job.share, job.path, job.sourceLang, job.targetLang)
            val protected = jobKey == protectedKey || synchronized(leaseLock) { leases.any { it.active && it.key == jobKey } }
            if (protected || (remaining <= maxJobs && job.updatedAtEpochMs >= oldestAllowed)) continue
            dao.deleteJob(job.serverId, job.share, job.path, job.sourceLang, job.targetLang)
            remaining--
            removed++
        }
        return removed
    }

    suspend fun countCues(): Int = dao.countCues()

    suspend fun countJobs(): Int = dao.countJobs()

    /**
     * Rough byte estimate for settings UI (UTF-16 char lengths + row overhead).
     */
    suspend fun approximateCacheBytes(): Long {
        val chars = dao.sumTextChars()
        val cues = dao.countCues().toLong()
        val jobs = dao.countJobs().toLong()
        // 2 bytes/char (Kotlin String) + fixed overhead per row.
        return chars * 2L + cues * 64L + jobs * 128L
    }

    private fun shouldInvalidate(storedKey: String, incomingKey: String): Boolean {
        if (incomingKey.isBlank()) return false
        return storedKey != incomingKey
    }

    private fun shouldInvalidateModel(storedModel: String, incomingModel: String): Boolean =
        incomingModel.isNotBlank() && storedModel != incomingModel

    private fun cueEntity(
        identity: PlaybackIdentity,
        languages: ListenLanguagePair,
        path: String,
        startMs: Long,
        endMs: Long,
        textSrc: String,
        textTgt: String,
        rev: Int,
    ): ListenTranslateCueEntity =
        ListenTranslateCueEntity(
            serverId = identity.serverId,
            share = identity.share,
            path = path,
            sourceLang = languages.sourceLang,
            targetLang = languages.targetLang,
            startMs = startMs.coerceAtLeast(0L),
            endMs = endMs.coerceAtLeast(0L),
            textSrc = textSrc,
            textTgt = textTgt,
            rev = rev.coerceAtLeast(0),
        )

    companion object {
        const val DEFAULT_MAX_JOBS: Int = 100
        const val RETENTION_MS: Long = 30L * 24L * 60L * 60L * 1_000L
    }
}

private fun ListenTranslateJobEntity.toModel(): ListenTranslateJob =
    ListenTranslateJob(
        identity = PlaybackIdentity(serverId, share, path),
        languages = ListenLanguagePair(sourceLang, targetLang),
        contentKey = contentKey,
        status = ListenTranslateJobStatus.fromStorage(status),
        durationMs = durationMs,
        coveredUntilMs = coveredUntilMs,
        asrModel = asrModel,
        mtModel = mtModel,
        updatedAtEpochMs = updatedAtEpochMs,
        lastError = lastError,
    )

private fun ListenTranslateCueEntity.toModel(): ListenTranslateCue =
    ListenTranslateCue(
        id = id,
        identity = PlaybackIdentity(serverId, share, path),
        languages = ListenLanguagePair(sourceLang, targetLang),
        startMs = startMs,
        endMs = endMs,
        textSrc = textSrc,
        textTgt = textTgt,
        rev = rev,
    )
