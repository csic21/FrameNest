package com.framenest

import android.app.Application
import com.framenest.app.AppContainer

class FrameNestApplication : Application() {
    lateinit var container: AppContainer
        private set

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
