package com.framenest.data.history

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Room row for playback history.
 *
 * Unique identity: ([serverId], [share], [path]) — see architecture doc.
 * FN-04 will add server entities to the same [AppDatabase]; keep this table stable.
 */
@Entity(
    tableName = "playback_history",
    primaryKeys = ["server_id", "share", "path"],
    indices = [
        Index(value = ["updated_at_epoch_ms"]),
    ],
)
data class PlaybackHistoryEntity(
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "share") val share: String,
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "completed") val completed: Boolean,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)
