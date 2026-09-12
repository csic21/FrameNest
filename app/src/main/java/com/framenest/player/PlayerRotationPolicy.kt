package com.framenest.player

/** Small, platform-free decisions used by the player rotation recovery path. */
internal object PlayerRotationPolicy {
    /** A configuration recreation is not an app-background transition. */
    fun shouldPauseOnStop(isChangingConfigurations: Boolean): Boolean =
        !isChangingConfigurations

    /**
     * Foreground recovery after an app-background transition.
     *
     * The underlying SurfaceView surface is destroyed on STOP and recreated on
     * START with the same size, so [VlcVideoSurface]'s size-change refresh does
     * not fire. The caller must always rebind via `refreshVideoSurfaces()`.
     *
     * When a decoded frame was already on screen and playback is resting in
     * Ready/Paused, the dead surface's BufferQueue took the last frame with it —
     * rebinding alone leaves black. A same-position seek forces one muted decode
     * to repaint the current picture without changing user intent.
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
