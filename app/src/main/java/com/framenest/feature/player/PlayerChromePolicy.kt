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
    ): Boolean = phase == PlayerState.Phase.Playing &&
        chromeVisible &&
        !controlsLocked &&
        !panelOpen
}

internal object PlayerControlLayoutPolicy {
    private const val COMPACT_WIDTH_DP = 420f
    private const val LARGE_FONT_SCALE = 1.15f

    fun useTwoActionRows(widthDp: Float, fontScale: Float): Boolean =
        widthDp < COMPACT_WIDTH_DP || fontScale >= LARGE_FONT_SCALE
}
