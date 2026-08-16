package com.framenest.data.listen_translate

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

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
        ORDER BY start_ms ASC, rev ASC
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
        ORDER BY start_ms ASC, rev ASC
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
        ORDER BY start_ms DESC, rev DESC
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
        return replaceCueAtRange(entity)
    }
}
