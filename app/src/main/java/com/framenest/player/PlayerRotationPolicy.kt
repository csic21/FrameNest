package com.framenest.player

/** Small, platform-free decisions used by the player rotation recovery path. */
internal object PlayerRotationPolicy {
    /** A configuration recreation is not an app-background transition. */
    fun shouldPauseOnStop(isChangingConfigurations: Boolean): Boolean =
        !isChangingConfigurations

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
