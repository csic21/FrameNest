package com.framenest.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Shared presentation constants for adaptive / accessibility polish (FN-08).
 * Prefer Material [minimumInteractiveComponentSize] for interactive targets.
 */
object FrameNestDimens {
    /** Material accessibility minimum for touch targets. */
    val MinTouchTarget = 48.dp

    /** Comfortable list row vertical padding so cards/rows clear 48dp height. */
    val ListRowVerticalPadding = 12.dp

    /** Horizontal page padding for compact single-column screens. */
    val ScreenPadding = 16.dp

    /** Max width for empty/error body copy on medium+ so text does not stretch edge-to-edge. */
    val ReadableContentMaxWidth = 480.dp

    /** Keeps long settings rows and their trailing controls visually connected on tablets. */
    val SettingsContentMaxWidth = 640.dp

    /** Leading 16:9 cover in browse list rows. Wide enough to recognize a scene. */
    val BrowseListThumbWidth = 128.dp
    val BrowseListThumbHeight = 72.dp

    /** Corner radius for browse thumbnails / grid media. */
    val BrowseThumbCorner = 8.dp

    /** Horizontal/vertical gap between browse grid cells. */
    val BrowseGridSpacing = 12.dp

    /** Outer padding around the browse grid. */
    val BrowseGridPadding = 16.dp
}
