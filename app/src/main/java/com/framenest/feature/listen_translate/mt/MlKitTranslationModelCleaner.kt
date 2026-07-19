package com.framenest.feature.listen_translate.mt

import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.tasks.await

/** Removes every downloaded ML Kit translation pack from app-private storage. */
class MlKitTranslationModelCleaner(
    private val remoteModelManagerProvider: () -> RemoteModelManager = {
        RemoteModelManager.getInstance()
    },
) {
    suspend fun deleteAll(): Int {
        val remoteModelManager = remoteModelManagerProvider()
        val models = remoteModelManager
            .getDownloadedModels(TranslateRemoteModel::class.java)
            .await()
        return deleteEachModel(models) { model ->
            remoteModelManager.deleteDownloadedModel(model).await()
        }
    }
}

internal suspend fun <T> deleteEachModel(
    models: Set<T>,
    delete: suspend (T) -> Unit,
): Int {
    models.forEach { delete(it) }
    return models.size
}
