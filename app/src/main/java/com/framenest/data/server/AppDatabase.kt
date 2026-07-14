package com.framenest.data.server

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * App-wide Room database.
 *
 * FN-04 registers **server** entities only. FN-05 should add history entities
 * here (or via migration) — see handoff for the integration patch.
 */
@Database(
    entities = [ServerEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao

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
                ).build().also { instance = it }
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
