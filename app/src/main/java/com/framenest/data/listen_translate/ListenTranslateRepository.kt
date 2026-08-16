package com.framenest.data.listen_translate

import com.framenest.core.model.PlaybackIdentity
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
    /**
     * Load job for [identity] + [languages]. If [contentKey] is non-blank and differs
     * from the stored key, the job and cues are dropped and this returns null.
     */
    suspend fun getJob(
        identity: PlaybackIdentity,
        languages: ListenLanguagePair,
        contentKey: String = "",
    ): ListenTranslateJob? {
        val path = identity.normalizedPath()
        val lang = languages.normalized()
        val row = dao.getJob(
            identity.serverId,
            identity.share,
            path,
            lang.sourceLang,
            lang.targetLang,
        ) ?: return null
        if (shouldInvalidate(row.contentKey, contentKey)) {
            dao.deleteJob(
                identity.serverId,
                identity.share,
                path,
                lang.sourceLang,
                lang.targetLang,
            )
            return null
        }
        return row.toModel()
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
        return entity.toModel()
    }

    suspend fun updateProgress(
        identity: PlaybackIdentity,
        languages: ListenLanguagePair,
        coveredUntilMs: Long,
        status: ListenTranslateJobStatus? = null,
        lastError: String? = null,
        durationMs: Long? = null,
    ) {
        val path = identity.normalizedPath()
        val lang = languages.normalized()
        val existing = dao.getJob(
            identity.serverId,
            identity.share,
            path,
            lang.sourceLang,
            lang.targetLang,
        ) ?: return
        dao.upsertJob(
            existing.copy(
                coveredUntilMs = coveredUntilMs.coerceAtLeast(existing.coveredUntilMs),
                status = (status ?: ListenTranslateJobStatus.fromStorage(existing.status)).name,
                lastError = lastError ?: existing.lastError,
                durationMs = durationMs?.coerceAtLeast(0L) ?: existing.durationMs,
                updatedAtEpochMs = timeSource(),
            ),
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
    ): ListenTranslateCue {
        require(endMs >= startMs) { "endMs >= startMs" }
        val path = identity.normalizedPath()
        val lang = languages.normalized()
        ensureJob(identity, lang, contentKey = contentKey)
        val id = dao.replaceCueAtRange(
            ListenTranslateCueEntity(
                serverId = identity.serverId,
                share = identity.share,
                path = path,
                sourceLang = lang.sourceLang,
                targetLang = lang.targetLang,
                startMs = startMs.coerceAtLeast(0L),
                endMs = endMs.coerceAtLeast(0L),
                textSrc = textSrc,
                textTgt = textTgt,
                rev = rev.coerceAtLeast(0),
            ),
        )
        // ensureJob already refreshes updatedAt immediately before the cue write;
        // querying and upserting the same parent again only added two Room round trips.
        return ListenTranslateCue(
            id = id,
            identity = PlaybackIdentity(identity.serverId, identity.share, path),
            languages = lang,
            startMs = startMs.coerceAtLeast(0L),
            endMs = endMs.coerceAtLeast(0L),
            textSrc = textSrc,
            textTgt = textTgt,
            rev = rev.coerceAtLeast(0),
        )
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
    suspend fun purgeMedia(identity: PlaybackIdentity) {
        dao.deleteJobsForMedia(
            identity.serverId,
            identity.share,
            identity.normalizedPath(),
        )
    }

    /** Drop all jobs for a deleted server. */
    suspend fun purgeServer(serverId: String) {
        if (serverId.isBlank()) return
        dao.deleteJobsForServer(serverId)
    }

    suspend fun purgeAll() {
        dao.deleteAllJobs()
    }

    suspend fun purgeOlderThan(epochMs: Long) {
        dao.deleteJobsOlderThan(epochMs)
    }

    /**
     * Keep at most [maxJobs] jobs (LRU by [ListenTranslateJobEntity.updatedAtEpochMs]).
     * @return number of jobs removed
     */
    suspend fun enforceMaxJobs(maxJobs: Int = DEFAULT_MAX_JOBS): Int {
        require(maxJobs >= 0)
        val count = dao.countJobs()
        if (count <= maxJobs) return 0
        val toRemove = count - maxJobs
        val oldest = dao.listOldestJobs(toRemove)
        for (job in oldest) {
            dao.deleteJob(
                job.serverId,
                job.share,
                job.path,
                job.sourceLang,
                job.targetLang,
            )
        }
        return oldest.size
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

    companion object {
        const val DEFAULT_MAX_JOBS: Int = 100
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
