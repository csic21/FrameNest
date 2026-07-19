package com.framenest.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ListenTranslatePreparationErrorTest {

    @Test
    fun voskClassLoadingFailure_isPresentedAsActionableMessage() {
        val message = listenTranslatePreparationError(NoClassDefFoundError("org.vosk.LibVosk"))

        assertEquals("本机语音识别组件加载失败，请更新应用后重试", message)
    }

    @Test
    fun regularFailure_preservesUsefulDetail() {
        val message = listenTranslatePreparationError(IllegalStateException("模型安装不完整"))

        assertEquals("模型安装不完整", message)
    }

    @Test
    fun optimizedMlKitFailure_doesNotExposeObfuscatedInternals() {
        val message = listenTranslatePreparationError(
            NullPointerException("Attempt to read from field 'w74 d23.c' on a null object reference"),
        )

        assertEquals("本机翻译组件初始化失败，请更新应用后重试", message)
    }
}
