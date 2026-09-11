package com.framenest.feature.player

import com.framenest.player.PlayerState

/**
 * Pure decisions for press-and-hold 2x speed boost (the signature interaction
 * of top-tier mobile players: hold to skim speech, release to resume).
 *
 * The gesture layer owns press timing and drag classification; the ViewModel
 * owns saving/restoring the user's chosen rate. This object keeps the shared
 * rules in one testable place so the two sides cannot drift:
 *
 * - Boost only ever starts from real playback ([PlayerState.Phase.Playing]).
 *   Starting from Ready/Paused would silently re-rate the pending play and
 *   surprise the user on the next tap.
 * - A drag that already classified into brightness/volume/scrub wins over the
 *   boost timer — the finger clearly meant to adjust, not to skim.
 * - A release that ends an active boost must not also fire the tap action
 *   (chrome toggle / play) underneath it.
 * - Restoring must not clobber an explicit user rate change made mid-boost
 *   (e.g. cycling the speed chip with a second finger).
 */
internal object SpeedBoostPolicy {
    /** Temporary rate while the finger is held down. */
    const val BOOST_RATE: Float = 2.0f

    /**
     * How long the finger must stay down (without classifying into a drag)
     * before boost engages. Slightly under the platform long-press timeout so
     * the skim feels instant, but well above the double-tap window so a quick
     * double-tap skip never flashes 2x first.
     */
    const val ENTER_DELAY_MS: Long = 400L

    fun isEligiblePhase(phase: PlayerState.Phase): Boolean =
        phase == PlayerState.Phase.Playing

    /**
     * Whether a press held for [pressDurationMs] should engage boost.
     *
     * @param movedBeyondSlop true once the gesture classified into a
     *   brightness/volume/scrub drag — the drag owns the finger from then on.
     */
    fun shouldEnterBoost(
        pressDurationMs: Long,
        movedBeyondSlop: Boolean,
        playing: Boolean,
        controlsLocked: Boolean,
    ): Boolean {
        if (!playing || controlsLocked) return false
        if (movedBeyondSlop) return false
        return pressDurationMs >= ENTER_DELAY_MS
    }

    /** A release after an active boost is consumed by the boost — no tap action. */
    fun shouldSuppressTapAfterBoost(boostWasActive: Boolean): Boolean = boostWasActive

    /**
     * Rate to restore on release, or null when the current rate no longer
     * equals [BOOST_RATE] (the user explicitly changed speed mid-boost, which
     * wins and must be kept).
     */
    fun restoreRateOrNull(currentRate: Float, savedUserRate: Float?): Float? {
        if (savedUserRate == null) return null
        return if (currentRate == BOOST_RATE) savedUserRate else null
    }
}
