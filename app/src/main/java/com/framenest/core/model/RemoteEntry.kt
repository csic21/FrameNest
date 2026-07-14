package com.framenest.core.model

/**
 * A browseable share, directory, or file under a saved server.
 *
 * [path] is share-relative (no host), normalized without a leading slash.
 * When [isShare] is true, [path] is empty and [name]/[share] is the share name.
 */
data class RemoteEntry(
    val serverId: String,
    val share: String,
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long? = null,
    val modifiedTimeMs: Long? = null,
    /** True when this entry represents an SMB share (browser root level). */
    val isShare: Boolean = false,
) {
    val isFile: Boolean get() = !isDirectory && !isShare

    /** Stable key for lists / navigation (not a filesystem path alone). */
    fun stableKey(): String = if (isShare) {
        "share:$serverId:$share"
    } else {
        "entry:$serverId:$share:$path"
    }
}
