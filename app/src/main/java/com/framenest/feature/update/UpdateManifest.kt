package com.framenest.feature.update

import java.net.URI

/** Contract shared with the release publisher. Version codes, not labels, determine upgrades. */
data class UpdateManifest(
    val schemaVersion: Int,
    val applicationId: String,
    val versionName: String,
    val versionCode: Long,
    val tag: String,
    val notes: String,
    val minSdk: Int,
    val assets: List<UpdateAsset>,
)

data class UpdateAsset(val abi: String, val size: Long, val sha256: String, val url: String)

internal object UpdatePolicy {
    const val APPLICATION_ID = "com.framenest"
    const val MANIFEST_URL = "https://github.com/csic21/FrameNest/releases/latest/download/update.json"
    const val MAX_MANIFEST_BYTES = 128 * 1024
    const val MAX_APK_BYTES = 512L * 1024 * 1024
    const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val REPOSITORY_PATH = "/csic21/FrameNest/releases/"
    private val tagPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
    private val assetPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}\\.apk")
    private val hashPattern = Regex("[a-f0-9]{64}")

    fun validate(manifest: UpdateManifest): UpdateManifest = manifest.apply {
        require(schemaVersion == 1) { "不支持的更新清单版本" }
        require(applicationId == APPLICATION_ID) { "更新清单包名不匹配" }
        require(versionCode in 1..Int.MAX_VALUE.toLong()) { "更新版本号无效" }
        require(versionName.isNotBlank() && versionName.length <= 100) { "更新版本名称无效" }
        require(tagPattern.matches(tag)) { "更新标签无效" }
        require(notes.length <= 24_000) { "更新说明过长" }
        require(minSdk in 26..100) { "更新系统版本无效" }
        require(assets.size in 1..3 && assets.map { it.abi }.distinct().size == assets.size) {
            "更新安装包列表无效"
        }
        assets.forEach { asset ->
            require(asset.abi in setOf("arm64-v8a", "x86_64", "universal")) { "安装包架构无效" }
            require(asset.size in 1..MAX_APK_BYTES) { "安装包大小无效" }
            require(hashPattern.matches(asset.sha256)) { "安装包摘要无效" }
            val uri = trustedUri(asset.url)
            val prefix = "${REPOSITORY_PATH}download/$tag/"
            require(uri.host == "github.com" && uri.rawQuery == null &&
                uri.rawPath.startsWith(prefix) && assetPattern.matches(uri.rawPath.removePrefix(prefix))) {
                "安装包必须来自 FrameNest 对应版本的 GitHub Release"
            }
        }
    }

    fun selectAsset(manifest: UpdateManifest, supportedAbis: List<String>): UpdateAsset? =
        supportedAbis.firstNotNullOfOrNull { abi -> manifest.assets.firstOrNull { it.abi == abi } }
            ?: manifest.assets.firstOrNull { it.abi == "universal" && supportedAbis.any { a -> a in setOf("arm64-v8a", "x86_64") } }

    fun isNewer(candidate: Long, installed: Long): Boolean = candidate > installed

    fun autoCheckDue(now: Long, lastAttempt: Long): Boolean =
        lastAttempt <= 0 || now < lastAttempt || now - lastAttempt >= AUTO_CHECK_INTERVAL_MS

    /** GitHub's release CDN uses signed queries; only exact HTTPS hosts are accepted. */
    fun trustedRedirect(url: String): URI = trustedUri(url).also {
        require(it.host == "release-assets.githubusercontent.com" ||
            it.host == "objects.githubusercontent.com" ||
            (it.host == "github.com" && it.rawPath.startsWith(REPOSITORY_PATH))) {
            "更新下载跳转来源不受信任"
        }
    }

    private fun trustedUri(url: String): URI = URI(url).also {
        require(it.scheme == "https" && it.port in setOf(-1, 443) && it.userInfo == null &&
            it.fragment == null && it.host != null && !it.rawPath.contains('%') &&
            !it.rawPath.split('/').any { segment -> segment == "." || segment == ".." }) {
            "更新地址必须是可信的 HTTPS 地址"
        }
    }
}

internal data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val currentSignerHashes: Set<String>,
)

internal fun validateApkIdentity(candidate: ApkIdentity, installed: ApkIdentity, manifest: UpdateManifest) {
    require(candidate.packageName == installed.packageName && candidate.packageName == manifest.applicationId) {
        "安装包包名不匹配，已拒绝安装"
    }
    require(candidate.versionCode == manifest.versionCode && UpdatePolicy.isNewer(candidate.versionCode, installed.versionCode)) {
        "安装包版本不匹配或不是更新版本"
    }
    // Intentionally conservative: certificate rotation needs a separately reviewed migration.
    require(candidate.currentSignerHashes.isNotEmpty() &&
        candidate.currentSignerHashes == installed.currentSignerHashes) {
        "安装包签名与当前应用不兼容，无法覆盖更新。请保留当前应用和数据，不要卸载。"
    }
}
