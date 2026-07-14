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
}
