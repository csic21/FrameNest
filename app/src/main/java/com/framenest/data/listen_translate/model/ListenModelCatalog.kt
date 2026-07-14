package com.framenest.data.listen_translate.model

/**
 * On-device model packs for listen-translate (FN-13).
 *
 * Files land only under app-private storage ([ListenModelStore]); never MediaStore /
 * public Download. Bundled assets act as the first install source; optional
 * [remoteUrl] enables HTTP refresh later without changing the store layout.
 */
enum class ListenModelKind {
    Asr,
    Mt,
}

data class ListenModelSpec(
    val id: String,
    val version: String,
    val kind: ListenModelKind,
    val displayName: String,
    /** Asset path under `assets/` when installing offline. */
    val assetPath: String,
    /** Expected SHA-256 of the installed file bytes (hex lowercase). */
    val sha256: String,
    /** Approximate size for UI (bytes). */
    val approxBytes: Long,
    /**
     * Optional HTTPS URL for network install. Null = asset-only install
     * (no public network required for first enable).
     */
    val remoteUrl: String? = null,
) {
    val packFileName: String get() = "$id-v$version.json"

    val modelIdTag: String get() = "$id-$version"
}

object ListenModelCatalog {
    val ASR_TINY: ListenModelSpec = ListenModelSpec(
        id = "asr-tiny",
        version = "1",
        kind = ListenModelKind.Asr,
        displayName = "ASR Tiny",
        assetPath = "listen_models/asr-tiny-v1.json",
        sha256 = "ab35db3183957aafcceff8257a7fc58b80ecf31b96408ea7e5aea7de5ebce220",
        approxBytes = 223L,
    )

    val MT_BASE: ListenModelSpec = ListenModelSpec(
        id = "mt-base",
        version = "1",
        kind = ListenModelKind.Mt,
        displayName = "MT Base",
        assetPath = "listen_models/mt-base-v1.json",
        // Keep in sync with assets/listen_models/mt-base-v1.json (sha256 of file bytes).
        sha256 = "ed71bfe0900ad56fec30d982a5f33fa3234ea34fd4e44c694cef89c109d631ac",
        approxBytes = 858L,
    )

    val ALL: List<ListenModelSpec> = listOf(ASR_TINY, MT_BASE)

    fun asr(): ListenModelSpec = ASR_TINY
    fun mt(): ListenModelSpec = MT_BASE

    fun byId(id: String): ListenModelSpec? = ALL.find { it.id == id }
}
