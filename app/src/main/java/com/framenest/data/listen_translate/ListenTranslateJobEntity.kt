package com.framenest.data.listen_translate

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * One listen-translate session per media identity + language pair.
 *
 * PK: ([serverId], [share], [path], [sourceLang], [targetLang])
 * Storage is app-private Room only (decision 0005 — uninstall clears).
 */
@Entity(
    tableName = "listen_translate_job",
    primaryKeys = ["server_id", "share", "path", "source_lang", "target_lang"],
    indices = [
        Index(value = ["server_id"]),
        Index(value = ["updated_at_epoch_ms"]),
    ],
)
data class ListenTranslateJobEntity(
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "share") val share: String,
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "source_lang") val sourceLang: String,
    @ColumnInfo(name = "target_lang") val targetLang: String,
    @ColumnInfo(name = "content_key") val contentKey: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "covered_until_ms") val coveredUntilMs: Long,
    @ColumnInfo(name = "asr_model") val asrModel: String,
    @ColumnInfo(name = "mt_model") val mtModel: String,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
    @ColumnInfo(name = "last_error") val lastError: String,
)
