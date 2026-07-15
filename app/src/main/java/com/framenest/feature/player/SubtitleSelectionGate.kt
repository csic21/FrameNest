package com.framenest.feature.player

/** Prevents stale async subtitle work from overriding a newer user selection. */
internal class SubtitleSelectionGate {
    private var generation: Long = 0L

    fun snapshot(): Long = generation

    fun advance(): Long {
        generation += 1L
        return generation
    }

    fun isCurrent(snapshot: Long): Boolean = snapshot == generation
}
