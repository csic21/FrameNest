package com.framenest

import android.app.Application
import com.framenest.data.history.AppDatabase
import com.framenest.data.history.PlaybackHistoryRepository

class FrameNestApplication : Application() {
    lateinit var database: AppDatabase
        private set

    lateinit var historyRepository: PlaybackHistoryRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        historyRepository = PlaybackHistoryRepository(database.playbackHistoryDao())
    }
}