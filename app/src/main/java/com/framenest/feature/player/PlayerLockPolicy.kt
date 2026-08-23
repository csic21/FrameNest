package com.framenest.feature.player

import android.content.pm.ActivityInfo
import com.framenest.player.PlayerState

/**
 * Pure policy for player control-lock and orientation-lock (FN-17).
 * UI/Activity side effects stay in Compose; this only decides intents.
 */
internal object PlayerLockPolicy {
    /** How long the unlock affordance stays visible after a locked-surface tap. */
    const val UNLOCK_HINT_MS: Long = 2_500L

    enum class BackAction {
        /** First back press while locked only unlocks controls. */
        Unlock,
        /** Leave the player route. */
        Leave,
    }

    fun consumeBack(controlsLocked: Boolean): BackAction =
        if (controlsLocked) BackAction.Unlock else BackAction.Leave

    /**
     * Chrome is suppressed while controls are locked so accidental taps cannot
     * hit seek/play. When unlocked, keep existing FN-08 rule: always show chrome
     * outside the Playing phase.
     */
    fun showChrome(
        controlsLocked: Boolean,
        chromeVisible: Boolean,
        phase: PlayerState.Phase,
    ): Boolean {
        if (controlsLocked) return false
        return chromeVisible || phase != PlayerState.Phase.Playing
    }

    /**
     * Auto-unlock on terminal errors so the user can always reach Retry.
     * Ended keeps the lock (user may still want to re-lock after replay).
     */
    fun shouldAutoUnlock(phase: PlayerState.Phase): Boolean =
        phase == PlayerState.Phase.Error

    /**
     * Fullscreen forces landscape even if the device is still physically
     * portrait. Exit fullscreen forces portrait so the button is visible even
     * when the device is lying on its side. Either request wins over the
     * optional rotation freeze; leaving the player restores sensor rotation.
     */
    fun orientationRequest(
        orientationLocked: Boolean,
        forceLandscape: Boolean = false,
        forcePortrait: Boolean = false,
    ): Int = when {
        forceLandscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        forcePortrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        orientationLocked -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
