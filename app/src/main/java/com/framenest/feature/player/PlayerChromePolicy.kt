package com.framenest.feature.player

import com.framenest.player.PlayerState

/** Pure decisions for transient player chrome and compact control layout. */
internal object PlayerChromePolicy {
    const val AUTO_HIDE_MS = 4_000L

    fun shouldAutoHide(
        phase: PlayerState.Phase,
        chromeVisible: Boolean,
        controlsLocked: Boolean,
        panelOpen: Boolean,
        userSeeking: Boolean = false,
    ): Boolean = phase == PlayerState.Phase.Playing &&
        chromeVisible &&
        !controlsLocked &&
        !panelOpen &&
        !userSeeking
}

internal object PlayerControlLayoutPolicy {
    private const val COMPACT_WIDTH_DP = 420f
    private const val LARGE_FONT_SCALE = 1.15f

    fun useTwoActionRows(widthDp: Float, fontScale: Float): Boolean =
        widthDp < COMPACT_WIDTH_DP || fontScale >= LARGE_FONT_SCALE
}

/** Keeps recovery actions on the error overlay instead of duplicating Retry. */
internal object PlayerActionPolicy {
    fun playEnabled(
        phase: PlayerState.Phase,
        canPlay: Boolean,
        canPause: Boolean,
    ): Boolean = phase != PlayerState.Phase.Error && (canPlay || canPause)
}
