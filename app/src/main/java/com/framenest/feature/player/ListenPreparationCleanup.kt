package com.framenest.feature.player

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal suspend fun closeListenPreparationResources(
    closeActions: List<() -> Unit>,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) = withContext(NonCancellable + dispatcher) {
    closeActions.forEach { close ->
        runCatching { close() }
    }
}
