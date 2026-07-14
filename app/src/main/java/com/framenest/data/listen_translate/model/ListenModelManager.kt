package com.framenest.data.listen_translate.model

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Install / verify / delete listen-translate model packs in app-private storage.
 */
class ListenModelManager(
    context: Context? = null,
    private val store: ListenModelStore = ListenModelStore(context),
    private val catalog: List<ListenModelSpec> = ListenModelCatalog.ALL,
    private val assetReader: (String) -> ByteArray = { path ->
        val ctx = requireNotNull(context) { "context required for asset install" }
        ctx.applicationContext.assets.open(path).use { it.readBytes() }
    },
    private val httpGet: suspend (String) -> ByteArray = { url -> httpDownload(url) },
) {
    private val installMutex = Mutex()
    private val progress = ConcurrentHashMap<String, Float>()

    private val _states = MutableStateFlow(snapshot())
    val states: StateFlow<List<ListenModelUiStatus>> = _states.asStateFlow()

    fun refresh() {
        _states.value = snapshot()
    }

    fun isReady(spec: ListenModelSpec): Boolean = store.isInstalled(spec)

    fun areCoreModelsReady(): Boolean {
        val asr = catalog.find { it.kind == ListenModelKind.Asr } ?: ListenModelCatalog.asr()
        val mt = catalog.find { it.kind == ListenModelKind.Mt } ?: ListenModelCatalog.mt()
        return store.isInstalled(asr) && store.isInstalled(mt)
    }

    fun approximateBytes(): Long = store.approximateBytes()

    fun loadMtPhraseTable(): Map<String, Map<String, String>> {
        val mt = catalog.find { it.kind == ListenModelKind.Mt } ?: ListenModelCatalog.mt()
        val bytes = store.readPackBytes(mt) ?: return emptyMap()
        return runCatching { parsePhraseTable(bytes) }.getOrDefault(emptyMap())
    }

    fun asrModelId(): String {
        val asr = catalog.find { it.kind == ListenModelKind.Asr } ?: ListenModelCatalog.asr()
        return if (isReady(asr)) asr.modelIdTag else "missing-asr"
    }

    fun mtModelId(): String {
        val mt = catalog.find { it.kind == ListenModelKind.Mt } ?: ListenModelCatalog.mt()
        return if (isReady(mt)) mt.modelIdTag else "missing-mt"
    }

    /**
     * Install from bundled asset (preferred) or optional [ListenModelSpec.remoteUrl].
     */
    suspend fun install(spec: ListenModelSpec, preferRemote: Boolean = false): Result<Unit> =
        withContext(Dispatchers.IO) {
            installMutex.withLock {
                try {
                    setProgress(spec.id, 0.05f)
                    if (store.isInstalled(spec)) {
                        setProgress(spec.id, 1f)
                        refresh()
                        return@withLock Result.success(Unit)
                    }
                    val bytes = when {
                        preferRemote && !spec.remoteUrl.isNullOrBlank() -> {
                            setProgress(spec.id, 0.2f)
                            httpGet(spec.remoteUrl.orEmpty())
                        }
                        else -> {
                            setProgress(spec.id, 0.3f)
                            assetReader(spec.assetPath)
                        }
                    }
                    setProgress(spec.id, 0.85f)
                    store.markReady(spec, bytes)
                    setProgress(spec.id, 1f)
                    refresh()
                    Result.success(Unit)
                } catch (t: Throwable) {
                    val msg = t.message?.take(200) ?: t.javaClass.simpleName
                    setProgress(spec.id, 0f)
                    refresh()
                    Result.failure(IOException("模型安装失败：${spec.displayName} — $msg", t))
                }
            }
        }

    suspend fun installCoreModels(): Result<Unit> {
        val asrSpec = catalog.find { it.kind == ListenModelKind.Asr } ?: ListenModelCatalog.asr()
        val mtSpec = catalog.find { it.kind == ListenModelKind.Mt } ?: ListenModelCatalog.mt()
        val asr = install(asrSpec)
        if (asr.isFailure) return asr
        return install(mtSpec)
    }

    suspend fun delete(spec: ListenModelSpec) = withContext(Dispatchers.IO) {
        store.deletePack(spec)
        progress.remove(spec.id)
        refresh()
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        store.deleteAll()
        progress.clear()
        refresh()
    }

    private fun snapshot(): List<ListenModelUiStatus> =
        catalog.map { spec ->
            ListenModelUiStatus(
                spec = spec,
                installed = store.isInstalled(spec),
                progress = progress[spec.id] ?: if (store.isInstalled(spec)) 1f else 0f,
                bytesOnDisk = if (store.isInstalled(spec)) {
                    store.packFile(spec).length()
                } else {
                    0L
                },
            )
        }

    private fun setProgress(id: String, value: Float) {
        progress[id] = value.coerceIn(0f, 1f)
        _states.update { snapshot() }
    }

    /**
     * Parses `phraseEntries` objects without org.json (reliable on JVM unit tests).
     * Expected fragments: `"pair":"…","src":"…","tgt":"…"` (order flexible per entry).
     */
    private fun parsePhraseTable(bytes: ByteArray): Map<String, Map<String, String>> {
        val text = String(bytes, Charsets.UTF_8)
        val out = linkedMapOf<String, MutableMap<String, String>>()
        val entryRegex = Regex(
            """\{[^{}]*"pair"\s*:\s*"([^"]+)"[^{}]*"src"\s*:\s*"([^"]+)"[^{}]*"tgt"\s*:\s*"([^"]+)"[^{}]*\}""",
        )
        val entryRegexAlt = Regex(
            """\{[^{}]*"pair"\s*:\s*"([^"]+)"[^{}]*"tgt"\s*:\s*"([^"]+)"[^{}]*"src"\s*:\s*"([^"]+)"[^{}]*\}""",
        )
        for (m in entryRegex.findAll(text)) {
            out.getOrPut(m.groupValues[1]) { linkedMapOf() }[m.groupValues[2]] = m.groupValues[3]
        }
        for (m in entryRegexAlt.findAll(text)) {
            out.getOrPut(m.groupValues[1]) { linkedMapOf() }[m.groupValues[3]] = m.groupValues[2]
        }
        return out
    }

    companion object {
        private suspend fun httpDownload(url: String): ByteArray =
            withContext(Dispatchers.IO) {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                }
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        error("HTTP $code")
                    }
                    conn.inputStream.use { it.readBytes() }
                } finally {
                    conn.disconnect()
                }
            }
    }
}

data class ListenModelUiStatus(
    val spec: ListenModelSpec,
    val installed: Boolean,
    val progress: Float,
    val bytesOnDisk: Long,
)
