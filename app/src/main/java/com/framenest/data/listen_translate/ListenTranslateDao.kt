package com.framenest.data.listen_translate

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Dao
interface ListenTranslateDao {

    // --- jobs ---

    /**
     * Room's UPDATE-or-INSERT upsert preserves the existing parent row. Using
     * INSERT OR REPLACE here would delete it first and cascade-delete every cue.
     */
    @Upsert
    suspend fun upsertJob(entity: ListenTranslateJobEntity)

    @Query(
        """
        SELECT * FROM listen_translate_job
        WHERE server_id = :serverId AND share = :share AND path = :path
          AND source_lang = :sourceLang AND target_lang = :targetLang
        LIMIT 1
        """,
    )
    suspend fun getJob(
        serverId: String,
        share: String,
        path: String,
        sourceLang: String,
        targetLang: String,
    ): ListenTranslateJobEntity?

    @Query(
        """
        UPDATE listen_translate_job
        SET covered_until_ms = MAX(covered_until_ms, :coveredUntilMs),
            status = COALESCE(:status, status),
            last_error = COALESCE(:lastError, last_error),
            duration_ms = COALESCE(:durationMs, duration_ms),
            updated_at_epoch_ms = :updatedAtEpochMs
        WHERE server_id = :serverId AND share = :share AND path = :path
          AND source_lang = :sourceLang AND target_lang = :targetLang
        """,
    )
    suspend fun updateJobProgress(
        serverId: String,
        share: String,
        path: String,
        sourceLang: String,
        targetLang: String,
        coveredUntilMs: Long,
        status: String?,
        lastError: String?,
        durationMs: Long?,
        updatedAtEpochMs: Long,
    ): Int

    @Query(
        """
        UPDATE listen_translate_job
        SET updated_at_epoch_ms = :updatedAtEpochMs
        WHERE server_id = :serverId AND share = :share AND path = :path
          AND source_lang = :sourceLang AND target_lang = :targetLang
        """,
    )
    suspend fun touchJob(
        serverId: String,
        share: String,
        path: String,
        sourceLang: String,
        targetLang: String,
        updatedAtEpochMs: Long,
    ): Int

    @Query(
        """
        SELECT * FROM listen_translate_job
        WHERE server_id = :serverId AND share = :share AND path = :path
        """,
    )
    suspend fun listJobsForMedia(
        serverId: String,
        share: String,
        path: String,
    ): List<ListenTranslateJobEntity>

    @Query(
        """
        SELECT * FROM listen_translate_job
        ORDER BY updated_at_epoch_ms ASC
        LIMIT :limit
        """,
    )
    suspend fun listOldestJobs(limit: Int): List<ListenTranslateJobEntity>

    @Query("SELECT COUNT(*) FROM listen_translate_job")
    suspend fun countJobs(): Int

    @Query(
        """
        DELETE FROM listen_translate_job
        WHERE server_id = :serverId AND share = :share AND path = :path
          AND source_lang = :sourceLang AND target_lang = :targetLang
        """,
    )
    suspend fun deleteJob(
        serverId: String,
        share: String,
        path: String,
        sourceLang: String,
        targetLang: String,
    )

    @Query(
        """
        DELETE FROM listen_translate_job
        WHERE server_id = :serverId AND share = :share AND path = :path
        """,
    )
    suspend fun deleteJobsForMedia(serverId: String, share: String, path: String)

    @Query("DELETE FROM listen_translate_job WHERE server_id = :serverId")
    suspend fun deleteJobsForServer(serverId: String)

    @Query("DELETE FROM listen_translate_job WHERE updated_at_epoch_ms < :epochMs")
    suspend fun deleteJobsOlderThan(epochMs: Long)

    @Query("DELETE FROM listen_translate_job")
    suspend fun deleteAllJobs()

    // --- cues ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCue(entity: ListenTranslateCueEntity): Long

    @Query(
        """
        SELECT * FROM listen_translate_cue
        WHERE server_id = :serverId AND share = :share AND path = :path
          AND source_lang = :sourceLang AND target_lang = :targetLang
        ORDER BY start_ms ASC, rev ASC, end_ms ASC, id ASC
        """,
    )
    fun observeCues(
        serverId: String,
        share: String,
        path: String,
        sourceLang: String,
        targetLang: String,
    ): Flow<List<ListenTranslateCueEntity>>

    @Query(
        """
        SELECT * FROM listen_translate_cue
        WHERE server_id = :serverId AND share = :share AND path = :path
          AND source_lang = :sourceLang AND target_lang = :targetLang
        ORDER BY start_ms ASC, rev ASC, end_ms ASC, id ASC
        """,
    )
    suspend fun listCues(
        serverId: String,
        share: String,
        path: String,
        sourceLang: String,
        targetLang: String,
    ): List<ListenTranslateCueEntity>

    @Query(
        """
        SELECT * FROM listen_translate_cue
        WHERE server_id = :serverId AND share = :share AND path = :path
          AND source_lang = :sourceLang AND target_lang = :targetLang
          AND start_ms <= :timeMs AND end_ms > :timeMs
        ORDER BY CASE WHEN trim(text_src) != '' OR trim(text_tgt) != '' THEN 1 ELSE 0 END DESC,
                 CASE WHEN rev = $LISTEN_PROVISIONAL_SOURCE_REV THEN 0 ELSE 1 END DESC,
                 start_ms DESC, rev DESC, end_ms DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun cueAt(
        serverId: String,
        share: String,
        path: String,
        sourceLang: String,
        targetLang: String,
        timeMs: Long,
    ): ListenTranslateCueEntity?

    @Query(
        """
        DELETE FROM listen_translate_cue
        WHERE server_id = :serverId AND share = :share AND path = :path
          AND source_lang = :sourceLang AND target_lang = :targetLang
          AND start_ms = :startMs AND end_ms = :endMs
        """,
    )
    suspend fun deleteCuesAtRange(
        serverId: String,
        share: String,
        path: String,
        sourceLang: String,
        targetLang: String,
        startMs: Long,
        endMs: Long,
    )

    /** Delete progress only, never final speech or completed coverage. */
    @Query(
        """
        DELETE FROM listen_translate_cue
        WHERE server_id = :serverId AND share = :share AND path = :path
          AND source_lang = :sourceLang AND target_lang = :targetLang
          AND rev = $LISTEN_PROVISIONAL_SOURCE_REV
          AND start_ms >= :ownedStartMs AND start_ms < :processedThroughMs
        """,
    )
    suspend fun deleteSupersededProvisionalCues(
        serverId: String,
        share: String,
        path: String,
        sourceLang: String,
        targetLang: String,
        ownedStartMs: Long,
        processedThroughMs: Long,
    )

    @Query("SELECT COUNT(*) FROM listen_translate_cue")
    suspend fun countCues(): Int

    @Query(
        """
        SELECT COALESCE(SUM(LENGTH(text_src) + LENGTH(text_tgt)), 0)
        FROM listen_translate_cue
        """,
    )
    suspend fun sumTextChars(): Long

    @Transaction
    suspend fun replaceCueAtRange(entity: ListenTranslateCueEntity): Long {
        deleteCuesAtRange(
            serverId = entity.serverId,
            share = entity.share,
            path = entity.path,
            sourceLang = entity.sourceLang,
            targetLang = entity.targetLang,
            startMs = entity.startMs,
            endMs = entity.endMs,
        )
        return insertCue(entity.copy(id = 0L))
    }

    /** Fast session path: parent existence check/touch and cue replacement share one transaction. */
    @Transaction
    suspend fun replaceCueForExistingJob(
        entity: ListenTranslateCueEntity,
        updatedAtEpochMs: Long,
        provisionalCleanup: ListenProvisionalCleanup? = null,
    ): Long? {
        val touched = touchJob(
            serverId = entity.serverId,
            share = entity.share,
            path = entity.path,
            sourceLang = entity.sourceLang,
            targetLang = entity.targetLang,
            updatedAtEpochMs = updatedAtEpochMs,
        )
        if (touched != 1) return null
        deleteSupersededProvisionalCues(entity, provisionalCleanup)
        return replaceCueAtRange(entity)
    }

    /** Fast coverage path: progress and the whole-window cue commit atomically. */
    @Transaction
    suspend fun replaceCueAndUpdateProgressForExistingJob(
        entity: ListenTranslateCueEntity,
        coveredUntilMs: Long,
        status: String,
        durationMs: Long?,
        updatedAtEpochMs: Long,
        provisionalCleanup: ListenProvisionalCleanup? = null,
    ): Long? {
        val updated = updateJobProgress(
            serverId = entity.serverId,
            share = entity.share,
            path = entity.path,
            sourceLang = entity.sourceLang,
            targetLang = entity.targetLang,
            coveredUntilMs = coveredUntilMs,
            status = status,
            lastError = null,
            durationMs = durationMs,
            updatedAtEpochMs = updatedAtEpochMs,
        )
        if (updated != 1) return null
        deleteSupersededProvisionalCues(entity, provisionalCleanup)
        return replaceCueAtRange(entity)
    }

    private suspend fun deleteSupersededProvisionalCues(
        entity: ListenTranslateCueEntity,
        cleanup: ListenProvisionalCleanup?,
    ) {
        if (cleanup == null) return
        deleteSupersededProvisionalCues(entity.serverId, entity.share, entity.path,
            entity.sourceLang, entity.targetLang, cleanup.ownedStartMs, cleanup.processedThroughMs)
    }

    /**
     * Failure-recovery only: original-PTS speech, exact two-window coverage and
     * progress become durable together. Recheck concurrent cache changes inside
     * this transaction before replacing source-only rows, including legacy rev=1.
     */
    @Transaction
    suspend fun replaceEmptyTranslationRecovery(
        speech: ListenTranslateCueEntity,
        coverage: ListenTranslateCueEntity,
        seamMs: Long,
        durationMs: Long?,
        updatedAtEpochMs: Long,
    ): List<ListenTranslateCueEntity>? {
        require(coverage.startMs >= 0L && coverage.startMs % 3_000L == 0L)
        require(seamMs - coverage.startMs == 3_000L && coverage.endMs > seamMs && coverage.endMs - seamMs <= 3_000L)
        require(speech.startMs >= coverage.startMs && speech.startMs < seamMs &&
            speech.endMs > seamMs && speech.endMs <= coverage.endMs)
        require(speech.textSrc.isNotBlank() && speech.textTgt.isNotBlank() && speech.rev == 1)
        require(coverage.textSrc.isEmpty() && coverage.textTgt.isEmpty() && coverage.rev == 1)
        require(listOf(speech.serverId, speech.share, speech.path, speech.sourceLang, speech.targetLang) ==
            listOf(coverage.serverId, coverage.share, coverage.path, coverage.sourceLang, coverage.targetLang))
        val overlapping = listCues(speech.serverId, speech.share, speech.path, speech.sourceLang, speech.targetLang)
            .filter { it.startMs < coverage.endMs && it.endMs > coverage.startMs }
        if (overlapping.any { it.textTgt.isNotBlank() || it.textSrc.isBlank() ||
                it.startMs < coverage.startMs || it.endMs > coverage.endMs }) return null
        currentCoroutineContext().ensureActive()
        if (updateJobProgress(speech.serverId, speech.share, speech.path, speech.sourceLang, speech.targetLang,
                coverage.endMs, "Partial", null, durationMs, updatedAtEpochMs) != 1) return null
        for (old in overlapping) {
            deleteCuesAtRange(old.serverId, old.share, old.path, old.sourceLang, old.targetLang, old.startMs, old.endMs)
        }
        currentCoroutineContext().ensureActive()
        val persistedSpeech = speech.copy(id = insertCue(speech.copy(id = 0L)))
        val rows = if (speech.startMs == coverage.startMs && speech.endMs == coverage.endMs) {
            listOf(persistedSpeech)
        } else {
            currentCoroutineContext().ensureActive()
            listOf(persistedSpeech, coverage.copy(id = insertCue(coverage.copy(id = 0L))))
        }
        currentCoroutineContext().ensureActive()
        return rows
    }
}
