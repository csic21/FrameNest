package com.framenest.feature.listen_translate.asr

import com.framenest.data.settings.AsrEngineChoice
import java.io.File

/**
 * One offline ASR backend: language coverage, model-pack lifecycle, and the
 * user-facing copy for each stage. [RealListenTranslateEngine] and
 * [PlayerViewModel][com.framenest.feature.player.PlayerViewModel] talk only to
 * this seam, so Vosk and SenseVoice stay interchangeable without either side
 * knowing the other's storage layout.
 */
interface AsrModelSupport {
    fun engineChoice(): AsrEngineChoice

    fun supportedSourceLanguages(): Set<String>

    fun isInstalled(langTag: String): Boolean

    suspend fun ensureInstalled(
        langTag: String,
        allowMeteredDownloads: Boolean,
        onProgress: (Float) -> Unit,
    ): File

    fun modelDir(langTag: String): File?

    fun unsupportedLanguageMessage(langTag: String): String

    fun incompleteModelMessage(langTag: String): String

    fun downloadingMessage(langTag: String): String

    fun readyMessage(sourceLang: String): String

    fun modelLabel(sourceLang: String): String
}

/** Vosk small packs (existing behavior; every string below is verbatim). */
class VoskAsrModelSupport(
    private val installer: VoskModelInstaller,
) : AsrModelSupport {
    override fun engineChoice(): AsrEngineChoice = AsrEngineChoice.VOSK

    override fun supportedSourceLanguages(): Set<String> =
        VoskModelInstaller.supportedSourceLanguages()

    override fun isInstalled(langTag: String): Boolean = installer.isInstalled(langTag)

    override suspend fun ensureInstalled(
        langTag: String,
        allowMeteredDownloads: Boolean,
        onProgress: (Float) -> Unit,
    ): File = installer.ensureInstalled(
        langTag = langTag,
        allowMeteredDownloads = allowMeteredDownloads,
        onProgress = onProgress,
    )

    override fun modelDir(langTag: String): File? = installer.modelDir(langTag)

    override fun unsupportedLanguageMessage(langTag: String): String =
        "源语言「$langTag」暂无离线 Vosk 小模型"

    override fun incompleteModelMessage(langTag: String): String =
        "Vosk($langTag) 模型安装不完整"

    override fun downloadingMessage(langTag: String): String =
        "正在下载 Vosk($langTag)…\n" +
            "也可先到「设置 → 听译模型」安装并查看状态"

    override fun readyMessage(sourceLang: String): String =
        "本机听译已就绪：Vosk small($sourceLang) + ML Kit Translate"

    override fun modelLabel(sourceLang: String): String =
        "vosk-${VoskModelInstaller.modelVersionTag(sourceLang) ?: sourceLang}"
}

/** SenseVoice-Small int8 via sherpa-onnx: one pack, five languages, ITN 标点. */
class SherpaAsrModelSupport(
    private val installer: SherpaModelInstaller,
) : AsrModelSupport {
    override fun engineChoice(): AsrEngineChoice = AsrEngineChoice.SHERPA

    override fun supportedSourceLanguages(): Set<String> =
        SherpaAsrEngine.supportedSourceLanguages()

    override fun isInstalled(langTag: String): Boolean = installer.isInstalled(langTag)

    override suspend fun ensureInstalled(
        langTag: String,
        allowMeteredDownloads: Boolean,
        onProgress: (Float) -> Unit,
    ): File = installer.ensureInstalled(
        langTag = langTag,
        allowMeteredDownloads = allowMeteredDownloads,
        onProgress = onProgress,
    )

    override fun modelDir(langTag: String): File? = installer.modelDir(langTag)

    override fun unsupportedLanguageMessage(langTag: String): String =
        "源语言「$langTag」暂无离线 SenseVoice 支持（仅中/粤/英/日/韩）"

    override fun incompleteModelMessage(langTag: String): String =
        "SenseVoice 模型安装不完整，请重新下载"

    override fun downloadingMessage(langTag: String): String =
        "正在下载 SenseVoice 模型（约 240MB，一次覆盖中/粤/英/日/韩）…\n" +
            "也可先到「设置 → 听译模型」安装并查看状态"

    override fun readyMessage(sourceLang: String): String =
        "本机听译已就绪：SenseVoice($sourceLang，含标点） + ML Kit Translate"

    override fun modelLabel(sourceLang: String): String = SherpaModelInstaller.MODEL_VERSION
}
