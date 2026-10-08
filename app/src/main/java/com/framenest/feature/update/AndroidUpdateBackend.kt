package com.framenest.feature.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal interface UpdateBackend {
    val installedVersionCode: Long
    val supportedAbis: List<String>
    val sdkInt: Int
    suspend fun check(): UpdateManifest
    suspend fun download(manifest: UpdateManifest, asset: UpdateAsset, progress: (Long) -> Unit): File
    suspend fun verify(file: File, manifest: UpdateManifest, asset: UpdateAsset)
    suspend fun discard(file: File)
}

internal interface UpdateCheckStore {
    var lastAttempt: Long
    var dismissedVersion: Long
}

internal class AndroidUpdateCheckStore(context: Context) : UpdateCheckStore {
    private val prefs = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
    override var lastAttempt: Long
        get() = prefs.getLong("last_attempt", 0)
        set(value) { prefs.edit().putLong("last_attempt", value).apply() }
    override var dismissedVersion: Long
        get() = prefs.getLong("dismissed_version", 0)
        set(value) { prefs.edit().putLong("dismissed_version", value).apply() }
}

internal class UpdateProblem(message: String) : IOException(message)

internal class AndroidUpdateBackend(context: Context) : UpdateBackend {
    private val app = context.applicationContext
    private val packages = app.packageManager
    private val cache = File(app.cacheDir, "app-updates")
    // A canceled blocking network read can take up to its timeout. Serialize its cleanup before retry.
    private val diskLock = Mutex()
    private val installed get() = packages.getPackageInfo(app.packageName, signingFlags())
    override val installedVersionCode: Long get() = installed.code()
    override val supportedAbis: List<String> get() = Build.SUPPORTED_ABIS.toList()
    override val sdkInt: Int get() = Build.VERSION.SDK_INT

    override suspend fun check(): UpdateManifest = withContext(Dispatchers.IO) {
        try {
            withDownload(UpdatePolicy.MANIFEST_URL) { connection ->
                if (connection.contentLengthLong > UpdatePolicy.MAX_MANIFEST_BYTES) {
                    throw UpdateProblem("更新清单过大，已拒绝")
                }
                val bytes = connection.inputStream.use { input ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(chunk)
                        if (count < 0) break
                        if (buffer.size() + count > UpdatePolicy.MAX_MANIFEST_BYTES) {
                            throw UpdateProblem("更新清单过大，已拒绝")
                        }
                        buffer.write(chunk, 0, count)
                    }
                    buffer.toByteArray()
                }
                parseUpdateManifest(bytes.toString(Charsets.UTF_8))
            }
        } catch (error: org.json.JSONException) {
            throw UpdateProblem("更新清单格式无效，请稍后重试")
        } catch (error: IllegalArgumentException) {
            throw UpdateProblem("更新清单校验失败，请稍后重试")
        }
    }

    override suspend fun download(
        manifest: UpdateManifest,
        asset: UpdateAsset,
        progress: (Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        diskLock.withLock {
            UpdatePolicy.validate(manifest)
            require(asset in manifest.assets)
            if (!cache.exists() && !cache.mkdirs()) throw UpdateProblem("无法创建更新缓存")
            // This directory is dedicated to this feature; no history, diagnostics or media are touched.
            if (cache.listFiles()?.any { !it.delete() } == true) throw UpdateProblem("无法清理旧更新缓存，请稍后重试")
            if (cache.usableSpace < asset.size + 32L * 1024 * 1024) throw UpdateProblem("空间不足，请释放存储后重试")
            val file = File(cache, "update-${UUID.randomUUID()}.apk")
            try {
                withDownload(asset.url) { connection ->
                    val length = connection.contentLengthLong
                    if (length != -1L && length != asset.size) throw UpdateProblem("安装包长度与更新清单不符")
                    var total = 0L
                    connection.inputStream.use { input ->
                        file.outputStream().use { output ->
                            val chunk = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(chunk)
                                if (count < 0) break
                                total += count
                                if (total > asset.size || total > UpdatePolicy.MAX_APK_BYTES) throw UpdateProblem("安装包超出声明大小")
                                output.write(chunk, 0, count)
                                progress(total)
                            }
                        }
                    }
                    if (total != asset.size) throw UpdateProblem("安装包下载不完整，请重试")
                }
                currentCoroutineContext().ensureActive()
                file
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
        }
    }

    override suspend fun verify(file: File, manifest: UpdateManifest, asset: UpdateAsset) = withContext(Dispatchers.IO) {
        if (file.parentFile?.canonicalFile != cache.canonicalFile || !file.isFile || file.length() != asset.size) {
            throw UpdateProblem("安装包缓存无效，请重新下载")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val chunk = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(chunk)
                if (count < 0) break
                digest.update(chunk, 0, count)
            }
        }
        if (!MessageDigest.isEqual(digest.digest(), asset.sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray())) {
            throw UpdateProblem("安装包摘要不匹配，已拒绝安装，请重新下载")
        }
        val archive = packages.getPackageArchiveInfo(file.path, signingFlags())
            ?: throw UpdateProblem("安装包无法识别，已拒绝安装")
        try {
            validateApkIdentity(archive.identity(), installed.identity(), manifest)
        } catch (error: IllegalArgumentException) {
            throw UpdateProblem(error.message ?: "安装包安全校验失败")
        }
        if (archive.applicationInfo?.minSdkVersion != manifest.minSdk || manifest.minSdk > sdkInt) {
            throw UpdateProblem("安装包系统要求与更新清单不匹配")
        }
    }

    override suspend fun discard(file: File) = withContext(Dispatchers.IO) {
        if (file.parentFile?.canonicalFile == cache.canonicalFile) file.delete()
        Unit
    }

    private suspend fun <T> withDownload(url: String, block: suspend (HttpURLConnection) -> T): T {
        var target = url
        repeat(5) { redirects ->
            currentCoroutineContext().ensureActive()
            UpdatePolicy.trustedRedirect(target)
            val connection = URI(target).toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", "FrameNest-Android-Updater/1")
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    if (redirects == 4) throw UpdateProblem("更新下载跳转次数过多")
                    val location = connection.getHeaderField("Location") ?: throw UpdateProblem("更新下载地址无效")
                    target = URI(target).resolve(location).toString()
                    UpdatePolicy.trustedRedirect(target)
                } else {
                    if (status != 200) throw UpdateProblem(if (status == 404) "暂未找到可用更新，请稍后重试" else "更新服务器暂时不可用，请稍后重试")
                    val encoding = connection.contentEncoding
                    if (encoding != null && !encoding.equals("identity", ignoreCase = true)) throw UpdateProblem("更新服务器返回不支持的压缩格式")
                    return block(connection)
                }
            } finally {
                connection.disconnect()
            }
        }
        throw UpdateProblem("更新下载地址无效")
    }
}

internal fun parseUpdateManifest(json: String): UpdateManifest {
    require(json.toByteArray(Charsets.UTF_8).size <= UpdatePolicy.MAX_MANIFEST_BYTES)
    val value = JSONObject(json)
    val assets = value.getJSONArray("assets")
    require(assets.length() in 1..3)
    return UpdatePolicy.validate(UpdateManifest(
        schemaVersion = value.strictLong("schemaVersion").also { require(it == 1L) }.toInt(),
        applicationId = value.strictString("applicationId"),
        versionName = value.strictString("versionName"),
        versionCode = value.strictLong("versionCode"),
        tag = value.strictString("tag"),
        notes = value.strictString("notes"),
        minSdk = value.strictLong("minSdk").also { require(it in 26..100) }.toInt(),
        assets = List(assets.length()) { index ->
            val asset = assets.getJSONObject(index)
            UpdateAsset(asset.strictString("abi"), asset.strictLong("size"), asset.strictString("sha256"), asset.strictString("url"))
        },
    ))
}

private fun JSONObject.strictString(key: String): String = get(key).let { require(it is String); it }
private fun JSONObject.strictLong(key: String): Long = get(key).let { require(it is Int || it is Long); (it as Number).toLong() }
@Suppress("DEPRECATION")
private fun signingFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
@Suppress("DEPRECATION")
private fun PackageInfo.code(): Long = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()
@Suppress("DEPRECATION")
private fun PackageInfo.identity(): ApkIdentity {
    val current = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures
    return ApkIdentity(packageName, code(), current.orEmpty().map {
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
    }.toSet())
}
