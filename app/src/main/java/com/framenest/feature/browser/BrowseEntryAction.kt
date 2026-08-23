package com.framenest.feature.browser

import com.framenest.core.model.MediaExtensions
import com.framenest.core.model.RemoteEntry

/** The complete set of actions a browser row/card may expose. */
internal enum class BrowseEntryAction {
    OPEN_SHARE,
    OPEN_DIRECTORY,
    OPEN_VIDEO,
    NONE,
}

/**
 * Keeps browse behavior identical in list and grid layouts.
 *
 * Subtitle files remain visible for recognition and player-side matching, but intentionally do
 * not expose an action here. Unknown files are equally inert as a defensive fallback.
 */
internal fun browseEntryAction(entry: RemoteEntry): BrowseEntryAction = when {
    entry.isShare -> BrowseEntryAction.OPEN_SHARE
    entry.isDirectory -> BrowseEntryAction.OPEN_DIRECTORY
    entry.isFile && MediaExtensions.isVideo(entry.name) -> BrowseEntryAction.OPEN_VIDEO
    else -> BrowseEntryAction.NONE
}

internal fun isBrowseEntryActionable(entry: RemoteEntry): Boolean =
    browseEntryAction(entry) != BrowseEntryAction.NONE
