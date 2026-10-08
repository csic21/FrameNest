package com.framenest

import android.app.Application
import com.framenest.feature.update.UpdateController
import com.framenest.app.AppContainer
import com.framenest.data.history.PlaybackHistoryRepository

class FrameNestApplication : Application() {
    lateinit var container: AppContainer
        private set

    val updateController by lazy { UpdateController(this) }

    /** Convenience for player / Recent without digging into the container. */
    val historyRepository: PlaybackHistoryRepository
        get() = container.historyRepository

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

fun ContextAppContainer(context: android.content.Context): AppContainer {
    val app = context.applicationContext as? FrameNestApplication
        ?: error("Application is not FrameNestApplication")
    return app.container
}
