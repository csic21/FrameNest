package com.framenest.data.server

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.framenest.data.history.PlaybackHistoryDao
import com.framenest.data.history.PlaybackHistoryEntity
import com.framenest.data.listen_translate.ListenTranslateCueEntity
import com.framenest.data.listen_translate.ListenTranslateDao
import com.framenest.data.listen_translate.ListenTranslateJobEntity

/**
 * App-wide Room database.
 *
 * Entities:
 * - [ServerEntity] (FN-04)
 * - [PlaybackHistoryEntity] (FN-05)
 * - [ListenTranslateJobEntity] / [ListenTranslateCueEntity] (FN-11)
 *
 * DB name stays [NAME] so features share one process singleton. Every released
 * schema version must have an explicit, tested migration path so server metadata,
 * playback history, and local listen-translate cache survive upgrades.
 */
@Database(
    entities = [
        ServerEntity::class,
        PlaybackHistoryEntity::class,
        ListenTranslateJobEntity::class,
        ListenTranslateCueEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun playbackHistoryDao(): PlaybackHistoryDao
    abstract fun listenTranslateDao(): ListenTranslateDao

    companion object {
        const val NAME: String = "framenest.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    NAME,
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
        }

        /** In-memory DB for tests; does not touch the process singleton. */
        fun createInMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
            ).allowMainThreadQueries().build()

        /**
         * FN-11 added listen-translate jobs/cues to the original server/history DB.
         * Existing v1 rows stay untouched; the two new tables start empty.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `servers` ADD COLUMN `requireEncryption` INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `listen_translate_job` (
                        `server_id` TEXT NOT NULL,
                        `share` TEXT NOT NULL,
                        `path` TEXT NOT NULL,
                        `source_lang` TEXT NOT NULL,
                        `target_lang` TEXT NOT NULL,
                        `content_key` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `duration_ms` INTEGER NOT NULL,
                        `covered_until_ms` INTEGER NOT NULL,
                        `asr_model` TEXT NOT NULL,
                        `mt_model` TEXT NOT NULL,
                        `updated_at_epoch_ms` INTEGER NOT NULL,
                        `last_error` TEXT NOT NULL,
                        PRIMARY KEY(`server_id`, `share`, `path`, `source_lang`, `target_lang`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_listen_translate_job_server_id` " +
                        "ON `listen_translate_job` (`server_id`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_listen_translate_job_updated_at_epoch_ms` " +
                        "ON `listen_translate_job` (`updated_at_epoch_ms`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `listen_translate_cue` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `server_id` TEXT NOT NULL,
                        `share` TEXT NOT NULL,
                        `path` TEXT NOT NULL,
                        `source_lang` TEXT NOT NULL,
                        `target_lang` TEXT NOT NULL,
                        `start_ms` INTEGER NOT NULL,
                        `end_ms` INTEGER NOT NULL,
                        `text_src` TEXT NOT NULL,
                        `text_tgt` TEXT NOT NULL,
                        `rev` INTEGER NOT NULL,
                        FOREIGN KEY(`server_id`, `share`, `path`, `source_lang`, `target_lang`)
                            REFERENCES `listen_translate_job`(
                                `server_id`, `share`, `path`, `source_lang`, `target_lang`
                            ) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_listen_translate_cue_server_id_share_path_source_lang_target_lang_start_ms` " +
                        "ON `listen_translate_cue` " +
                        "(`server_id`, `share`, `path`, `source_lang`, `target_lang`, `start_ms`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_listen_translate_cue_server_id` " +
                        "ON `listen_translate_cue` (`server_id`)",
                )
            }
        }
    }
}
