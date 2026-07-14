package com.framenest.core.model

import com.framenest.smb.SmbPathUtils

/**
 * Browser / player location under a saved server.
 *
 * - [share] blank → share list (browser root)
 * - [share] set, [path] blank → share root directory
 * - both set → directory or file within the share
 */
data class RemoteLocation(
    val share: String = "",
    val path: String = "",
) {
    val isShareList: Boolean get() = share.isBlank()

    val normalizedPath: String get() = SmbPathUtils.normalizeRelative(path)

    fun childDirectory(name: String): RemoteLocation =
        RemoteLocation(share = share, path = SmbPathUtils.join(normalizedPath, name))

    fun parent(): RemoteLocation {
        if (isShareList) return ROOT
        val parentPath = SmbPathUtils.parentOf(normalizedPath)
        return if (normalizedPath.isEmpty()) {
            ROOT
        } else {
            RemoteLocation(share = share, path = parentPath)
        }
    }

    companion object {
        val ROOT: RemoteLocation = RemoteLocation(share = "", path = "")

        fun shareRoot(share: String): RemoteLocation =
            RemoteLocation(share = share.trim(), path = "")

        fun of(share: String, path: String): RemoteLocation =
            RemoteLocation(
                share = share.trim(),
                path = SmbPathUtils.normalizeRelative(path),
            )
    }
}
