package com.framenest.player

/**
 * Distinguishes libVLC SPU tracks created by [org.videolan.libvlc.MediaPlayer.addSlave]
 * from tracks that were already in the media.
 *
 * libVLC exposes slaves as extra [MediaPlayer.getSpuTracks] entries, so the product
 * UI must not list them under "embedded".
 */
internal class SlaveSubtitleTracker {
    private val slaveIds = mutableSetOf<Int>()
    private var idsBeforePendingSlave: Set<Int>? = null

    fun reset() {
        slaveIds.clear()
        idsBeforePendingSlave = null
    }

    fun captureBeforeAddSlave(currentIds: Collection<Int>) {
        idsBeforePendingSlave = currentIds.filter { it >= 0 }.toSet()
    }

    fun cancelPending() {
        idsBeforePendingSlave = null
    }

    fun syncWithCurrentIds(currentIds: Collection<Int>): Set<Int> {
        val before = idsBeforePendingSlave
        if (before != null) {
            slaveIds += currentIds.filter { it >= 0 }.toSet() - before
            idsBeforePendingSlave = null
        }
        return slaveIds
    }

    fun isSlave(id: Int): Boolean = id in slaveIds
}
