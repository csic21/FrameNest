package com.framenest.data.listen_translate

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ListenTranslateDao {

    // --- jobs ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
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
        ORDER BY start_ms ASC
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
        ORDER BY start_ms ASC
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
        ORDER BY start_ms DESC
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
}
