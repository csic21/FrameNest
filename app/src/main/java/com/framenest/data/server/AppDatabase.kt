package com.framenest.data.server

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
 * DB name stays [NAME] so features share one process singleton.
 * Pre-release: destructive migration on version bump is acceptable.
 */
@Database(
    entities = [
        ServerEntity::class,
        PlaybackHistoryEntity::class,
        ListenTranslateJobEntity::class,
        ListenTranslateCueEntity::class,
    ],
    version = 2,
    exportSchema = false,
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
                    // Pre-release: no production users yet.
                    .fallbackToDestructiveMigration(dropAllTables = true)
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
    }
}
