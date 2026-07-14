package com.framenest.data.server

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.framenest.data.history.PlaybackHistoryDao
import com.framenest.data.history.PlaybackHistoryEntity

/**
 * App-wide Room database (Wave 2).
 *
 * Entities:
 * - [ServerEntity] (FN-04)
 * - [PlaybackHistoryEntity] (FN-05)
 *
 * DB name stays [NAME] so both features share one process singleton.
 */
@Database(
    entities = [
        ServerEntity::class,
        PlaybackHistoryEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun playbackHistoryDao(): PlaybackHistoryDao

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
                    // Pre-release: no production users yet; safe while integrating Wave 2.
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
