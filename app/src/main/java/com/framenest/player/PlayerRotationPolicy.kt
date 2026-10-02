package com.framenest.player

/** Small, platform-free decisions used by the player rotation recovery path. */
internal object PlayerRotationPolicy {
    /** A configuration recreation is not an app-background transition. */
    fun shouldPauseOnStop(isChangingConfigurations: Boolean): Boolean =
        !isChangingConfigurations

    /**
     * True only after an actual background stop.
     *
     * Opening the player also delivers ON_START. That pass must not detach the
     * video layout. A later return from another app must attach it again:
     * surface destruction clears libVLC's holder callback, and a size-only
     * refresh then does nothing, so play stays dead until the screen is reopened.
     */
    fun shouldReattachVideoOnForeground(returningFromBackground: Boolean): Boolean =
        returningFromBackground

    /**
     * After the video layout is attached again, repaint a resting frame.
     *
     * The new surface has an empty buffer. A same-position seek forces one muted
     * decode so Ready/Paused shows the current picture again. Playing, opening,
     * and error states keep their own path.
     */
    fun shouldRepaintOnForeground(
        firstFrameReady: Boolean,
        phase: PlayerState.Phase,
    ): Boolean = firstFrameReady &&
        (phase == PlayerState.Phase.Ready || phase == PlayerState.Phase.Paused)

    /** A VLC layout may only be reused by the exact same host Activity instance. */
    fun shouldRecreateVideoLayout(
        existingHost: Any?,
        requestedHost: Any?,
    ): Boolean = existingHost == null || existingHost !== requestedHost

    /** Ignore a delayed release callback from a container that is no longer current. */
    fun shouldDetachVideoLayout(
        currentContainer: Any?,
        releasingContainer: Any,
    ): Boolean = currentContainer === releasingContainer
}
