package com.framenest.smb

/**
 * Share-name candidates when MS-SRVS full enumeration is unavailable.
 *
 * SMB2/3 has no first-class "list shares" op without RPC. We probe common home-NAS
 * names plus any user-supplied defaults; tree-connect success means "visible".
 */
object SmbShareCandidates {
    /**
     * Common Synology / TrueNAS / Windows / router share names.
     * Order is preference for display when multiple succeed.
     */
    val COMMON: List<String> = listOf(
        "media",
        "video",
        "videos",
        "movies",
        "tv",
        "shows",
        "anime",
        "music",
        "photos",
        "photo",
        "pictures",
        "download",
        "downloads",
        "public",
        "share",
        "shared",
        "homes",
        "home",
        "backup",
        "backups",
        "data",
        "files",
        "usbshare",
        "volume1",
    )

    /**
     * Merge user-known names (highest priority) with [COMMON], de-duplicated case-insensitively.
     * Never includes IPC$ or administrative hidden shares.
     */
    fun merge(known: List<String>): List<String> {
        val out = linkedMapOf<String, String>() // lower -> original
        fun add(name: String) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return
            if (trimmed.endsWith("$", ignoreCase = true)) return // skip admin shares
            val key = trimmed.lowercase()
            if (!out.containsKey(key)) {
                out[key] = trimmed
            }
        }
        known.forEach(::add)
        COMMON.forEach(::add)
        return out.values.toList()
    }
}
