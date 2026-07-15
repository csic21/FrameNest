package com.framenest.data.thumbnail

import java.util.concurrent.atomic.AtomicLong

/** Invalidates queued and in-flight thumbnail work when the cache is cleared. */
internal class ThumbnailCacheGeneration {
    private val value = AtomicLong(0L)

    fun current(): Long = value.get()

    fun advance(): Long = value.incrementAndGet()

    fun isCurrent(generation: Long): Boolean = value.get() == generation
}
