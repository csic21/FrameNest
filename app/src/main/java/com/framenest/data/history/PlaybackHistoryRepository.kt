package com.framenest.data.history

import com.framenest.core.model.PlaybackIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Playback history API for player feature and Recent screen.
 *
 * Identity is always ([PlaybackIdentity.serverId], share, path).
 */
class PlaybackHistoryRepository(
    private val dao: PlaybackHistoryDao,
    private val timeSource: () -> Long = { System.currentTimeMillis() },
) {
    fun observeRecent(limit: Int = 50): Flow<List<PlaybackHistoryItem>> =
        dao.observeRecent(limit).map { list -> list.map { it.toItem() } }

    suspend fun listRecent(limit: Int = 50): List<PlaybackHistoryItem> =
        dao.listRecent(limit).map { it.toItem() }

    suspend fun get(identity: PlaybackIdentity): PlaybackHistoryItem? {
        val path = identity.normalizedPath()
        return dao.get(identity.serverId, identity.share, path)?.toItem()
    }

    /**
     * Persist progress. Applies completion rule and stores normalized path.
     */
    suspend fun saveProgress(
        identity: PlaybackIdentity,
        displayName: String,
        positionMs: Long,
        durationMs: Long,
    ): PlaybackHistoryItem {
        val path = identity.normalizedPath()
        val completed = PlaybackProgressRules.isCompleted(positionMs, durationMs)
        val storedPosition = if (completed) {
            durationMs.coerceAtLeast(positionMs)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        val entity = PlaybackHistoryEntity(
            serverId = identity.serverId,
            share = identity.share,
            path = path,
            displayName = displayName.ifBlank { path.substringAfterLast('/') },
            positionMs = storedPosition,
            durationMs = durationMs.coerceAtLeast(0L),
            completed = completed,
            updatedAtEpochMs = timeSource(),
        )
        dao.upsert(entity)
        return entity.toItem()
    }

    suspend fun delete(identity: PlaybackIdentity) {
        dao.delete(identity.serverId, identity.share, identity.normalizedPath())
    }

    /** Remove all recent/resume entries that can no longer open after server deletion. */
    suspend fun purgeServer(serverId: String) {
        require(serverId.isNotBlank()) { "serverId is blank" }
        dao.deleteByServerId(serverId)
    }
}

/**
 * UI / feature-facing history row (immutable).
 */
data class PlaybackHistoryItem(
    val identity: PlaybackIdentity,
    val displayName: String,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val updatedAtEpochMs: Long,
) {
    val progressFraction: Float
        get() = if (durationMs <= 0L) {
            0f
        } else {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        }

    /** Position to feed into prepare/seek for resume. */
    val resumePositionMs: Long
        get() = PlaybackProgressRules.resumePositionMs(positionMs, durationMs, completed)
}

private fun PlaybackHistoryEntity.toItem(): PlaybackHistoryItem =
    PlaybackHistoryItem(
        identity = PlaybackIdentity(serverId = serverId, share = share, path = path),
        displayName = displayName,
        positionMs = positionMs,
        durationMs = durationMs,
        completed = completed,
        updatedAtEpochMs = updatedAtEpochMs,
    )
