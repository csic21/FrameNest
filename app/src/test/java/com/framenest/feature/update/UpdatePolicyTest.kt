package com.framenest.feature.update

import org.junit.Assert.*
import org.junit.Test

internal fun testManifest(code: Long = 11) = UpdateManifest(
    1, "com.framenest", "0.6.0-internal", code, "v0.6.0-internal", "修复与更新", 26,
    listOf(UpdateAsset("arm64-v8a", 100, "a".repeat(64), "https://github.com/csic21/FrameNest/releases/download/v0.6.0-internal/FrameNest-0.6.0-internal-arm64-v8a.apk")),
)

class UpdatePolicyTest {
    @Test fun `version code alone decides upgrades`() {
        assertTrue(UpdatePolicy.isNewer(11, 10))
        assertFalse(UpdatePolicy.isNewer(10, 10))
        assertFalse(UpdatePolicy.isNewer(9, 10))
    }

    @Test fun `select native architecture before universal and reject unsupported devices`() {
        val base = testManifest()
        val arm = base.assets.single()
        val universal = arm.copy(abi = "universal")
        val x86 = arm.copy(abi = "x86_64")
        val manifest = base.copy(assets = listOf(universal, arm, x86))
        assertEquals(x86, UpdatePolicy.selectAsset(manifest, listOf("x86_64", "x86")))
        assertEquals(arm, UpdatePolicy.selectAsset(manifest, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(universal, UpdatePolicy.selectAsset(base.copy(assets = listOf(universal)), listOf("arm64-v8a")))
        assertNull(UpdatePolicy.selectAsset(manifest, listOf("armeabi-v7a")))
    }

    @Test fun `automatic check throttles failures too and handles a backwards clock`() {
        assertTrue(UpdatePolicy.autoCheckDue(100, 0))
        assertFalse(UpdatePolicy.autoCheckDue(101, 100))
        assertTrue(UpdatePolicy.autoCheckDue(100 + UpdatePolicy.AUTO_CHECK_INTERVAL_MS, 100))
        assertTrue(UpdatePolicy.autoCheckDue(50, 100))
    }

    @Test fun `malformed or unsupported metadata is rejected`() {
        val base = testManifest()
        val asset = base.assets.single()
        listOf(
            base.copy(schemaVersion = 2), base.copy(applicationId = "com.other"),
            base.copy(versionCode = 0), base.copy(versionCode = Long.MAX_VALUE),
            base.copy(versionName = ""), base.copy(tag = "../x"), base.copy(minSdk = 0),
            base.copy(notes = "x".repeat(24_001)), base.copy(assets = emptyList()),
            base.copy(assets = listOf(asset, asset)), base.copy(assets = listOf(asset.copy(abi = "mips"))),
            base.copy(assets = listOf(asset.copy(size = 0))),
            base.copy(assets = listOf(asset.copy(size = UpdatePolicy.MAX_APK_BYTES + 1))),
            base.copy(assets = listOf(asset.copy(sha256 = "a"))),
            base.copy(assets = listOf(asset.copy(sha256 = "A".repeat(64)))),
        ).forEach { invalid -> assertThrows(Exception::class.java) { UpdatePolicy.validate(invalid) } }
        assertEquals(base, UpdatePolicy.validate(base))
    }

    @Test fun `APK must be fixed version from exact repository without credentials queries or traversal`() {
        val base = testManifest()
        val asset = base.assets.single()
        listOf(
            asset.url.replace("https:", "http:"), asset.url.replace("csic21", "another"),
            asset.url.replace("FrameNest/releases", "Other/releases"),
            asset.url.replace("github.com", "github.com.evil.invalid"),
            asset.url.replace("github.com", "user@github.com"),
            asset.url.replace("github.com", "github.com:8443"),
            asset.url.replace("/download/v0.6.0-internal/", "/latest/download/"),
            asset.url.replace("/download/v0.6.0-internal/", "/download/v0.5.2/"),
            asset.url.replace("/FrameNest-", "/../FrameNest-"),
            asset.url.replace("/FrameNest-", "/%2e%2e/FrameNest-"),
            asset.url + "?ignored=yes", asset.url + "#fragment", asset.url.replace(".apk", ".zip"),
        ).forEach { url -> assertThrows(Exception::class.java) { UpdatePolicy.validate(base.copy(assets = listOf(asset.copy(url = url)))) } }
    }

    @Test fun `redirects allow only exact HTTPS release sources`() {
        assertEquals("github.com", UpdatePolicy.trustedRedirect(UpdatePolicy.MANIFEST_URL).host)
        assertEquals("release-assets.githubusercontent.com", UpdatePolicy.trustedRedirect("https://release-assets.githubusercontent.com/github-production-release-asset/id/file?sig=test").host)
        listOf("http://github.com/csic21/FrameNest/releases/", "https://github.com/evil/repo/releases/",
            "https://example.org/update.apk", "https://objects.githubusercontent.com.evil.org/x",
            "https://user@objects.githubusercontent.com/x", "https://127.0.0.1/x", "file:///tmp/x",
        ).forEach { assertThrows(Exception::class.java) { UpdatePolicy.trustedRedirect(it) } }
    }

    @Test fun `APK identity requires package version and identical nonempty current signer set`() {
        val installed = ApkIdentity("com.framenest", 10, setOf("original"))
        val valid = installed.copy(versionCode = 11)
        validateApkIdentity(valid, installed, testManifest())
        listOf(valid.copy(packageName = "com.evil"), valid.copy(versionCode = 12), valid.copy(versionCode = 10),
            valid.copy(currentSignerHashes = emptySet()), valid.copy(currentSignerHashes = setOf("other")),
            valid.copy(currentSignerHashes = setOf("original", "extra")),
        ).forEach { assertThrows(IllegalArgumentException::class.java) { validateApkIdentity(it, installed, testManifest()) } }
    }
}
