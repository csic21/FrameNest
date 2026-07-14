package com.framenest.feature.listen_translate

import com.framenest.feature.listen_translate.asr.VoskAsrEngine
import com.framenest.feature.listen_translate.asr.VoskModelInstaller
import com.framenest.feature.listen_translate.audio.ListenAudioSource
import com.framenest.feature.listen_translate.mt.MlKitMtEngine
import java.io.File

/**
 * Product listen-translate engine (FN-14):
 * PCM window → Vosk offline ASR → ML Kit on-device MT.
 */
class RealListenTranslateEngine(
    private val audio: ListenAudioSource,
    private val vosk: VoskAsrEngine,
    private val mt: MlKitMtEngine,
    private val voskModels: VoskModelInstaller,
    private val asrModelLabel: () -> String = { "vosk-small" },
    private val mtModelLabel: () -> String = { "mlkit-translate" },
) : ListenTranslateEngine {

    override val asrModelId: String get() = asrModelLabel()
    override val mtModelId: String get() = mtModelLabel()

    override suspend fun processWindow(
        startMs: Long,
        endMs: Long,
        sourceLang: String,
        targetLang: String,
    ): ListenWindowResult {
        val srcLang = sourceLang.lowercase()
        val tgtLang = targetLang.lowercase()
        if (!VoskModelInstaller.supportedSourceLanguages().contains(srcLang)) {
            throw IllegalStateException("源语言「$srcLang」暂无离线 Vosk 小模型")
        }
        if (!MlKitMtEngine.isSupported(srcLang) || !MlKitMtEngine.isSupported(tgtLang)) {
            throw IllegalStateException("语言对 $srcLang→$tgtLang 不被 ML Kit 支持")
        }

        val modelDir: File = voskModels.modelDir(srcLang)
            ?: throw ModelsNotReadyException("请先下载源语言「$srcLang」的 Vosk 模型")
        vosk.ensureModel(modelDir, srcLang)
        mt.ensureModel(srcLang, tgtLang)

        val pcm = try {
            audio.pcmWindow(startMs, endMs)
        } catch (t: Throwable) {
            throw IllegalStateException(
                "音频解码失败: ${t.message?.take(120) ?: t.javaClass.simpleName}",
                t,
            )
        }
        if (pcm.isEmpty()) {
            return ListenWindowResult(textSrc = "", textTgt = "")
        }

        val textSrc = try {
            vosk.recognize(pcm)
        } catch (t: Throwable) {
            throw IllegalStateException(
                "ASR 失败: ${t.message?.take(120) ?: t.javaClass.simpleName}",
                t,
            )
        }
        if (textSrc.isBlank()) {
            return ListenWindowResult(textSrc = "", textTgt = "")
        }

        val textTgt = try {
            mt.translate(textSrc, srcLang, tgtLang)
        } catch (t: Throwable) {
            // Still keep ASR text if MT fails.
            return ListenWindowResult(
                textSrc = textSrc,
                textTgt = "（翻译失败：${t.message?.take(80) ?: "error"}）",
            )
        }
        return ListenWindowResult(textSrc = textSrc, textTgt = textTgt)
    }

    fun close() {
        vosk.close()
        mt.close()
    }
}
