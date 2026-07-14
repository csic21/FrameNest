package com.framenest.data.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaybackHistoryEntity)

    @Query(
        """
        SELECT * FROM playback_history
        WHERE server_id = :serverId AND share = :share AND path = :path
        LIMIT 1
        """,
    )
    suspend fun get(serverId: String, share: String, path: String): PlaybackHistoryEntity?

    @Query(
        """
        SELECT * FROM playback_history
        ORDER BY updated_at_epoch_ms DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int = 50): Flow<List<PlaybackHistoryEntity>>

    @Query(
        """
        SELECT * FROM playback_history
        ORDER BY updated_at_epoch_ms DESC
        LIMIT :limit
        """,
    )
    suspend fun listRecent(limit: Int = 50): List<PlaybackHistoryEntity>

    @Query(
        """
        DELETE FROM playback_history
        WHERE server_id = :serverId AND share = :share AND path = :path
        """,
    )
    suspend fun delete(serverId: String, share: String, path: String)

    @Query("DELETE FROM playback_history")
    suspend fun clearAll()
}
