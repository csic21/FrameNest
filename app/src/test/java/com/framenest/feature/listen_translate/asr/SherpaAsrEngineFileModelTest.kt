package com.framenest.feature.listen_translate.asr

import android.content.res.AssetManager
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * sherpa-onnx 1.12.32 selects native `newFromAsset` whenever an AssetManager
 * is passed; for absolute private-storage model paths that route logs
 * "Load ... failed" and calls exit(255), killing the process before any Kotlin
 * catch can run. SenseVoice models are always installed as files, so no
 * [SherpaAsrEngine] constructor may take an AssetManager again.
 */
class SherpaAsrEngineFileModelTest {

    @Test
    fun noConstructorAcceptsAnAssetManager() {
        val assetParameters = SherpaAsrEngine::class.java.declaredConstructors.flatMap { constructor ->
            constructor.parameterTypes.filter { type ->
                AssetManager::class.java.isAssignableFrom(type)
            }
        }

        assertTrue(
            "SherpaAsrEngine must load models from files only; an AssetManager " +
                "makes sherpa-onnx exit the process for absolute paths",
            assetParameters.isEmpty(),
        )
    }
}
