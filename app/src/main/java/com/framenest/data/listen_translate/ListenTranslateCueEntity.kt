package com.framenest.data.listen_translate

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Timed ASR + translation segment for a [ListenTranslateJobEntity].
 * Cascade-deleted when the parent job row is removed.
 */
@Entity(
    tableName = "listen_translate_cue",
    foreignKeys = [
        ForeignKey(
            entity = ListenTranslateJobEntity::class,
            parentColumns = ["server_id", "share", "path", "source_lang", "target_lang"],
            childColumns = ["server_id", "share", "path", "source_lang", "target_lang"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["server_id", "share", "path", "source_lang", "target_lang", "start_ms"]),
        Index(value = ["server_id"]),
    ],
)
data class ListenTranslateCueEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "share") val share: String,
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "source_lang") val sourceLang: String,
    @ColumnInfo(name = "target_lang") val targetLang: String,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
    @ColumnInfo(name = "text_src") val textSrc: String,
    @ColumnInfo(name = "text_tgt") val textTgt: String,
    @ColumnInfo(name = "rev") val rev: Int,
)
