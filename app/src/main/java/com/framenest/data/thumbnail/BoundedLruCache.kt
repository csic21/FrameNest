package com.framenest.data.thumbnail

/**
 * Small synchronized LRU with both entry-count and weighted-size limits.
 *
 * The cache deliberately does not dispose evicted values: a Compose row may
 * still hold a bitmap while the cache releases its own reference.
 */
internal class BoundedLruCache<K, V>(
    private val maxEntries: Int,
    private val maxWeight: Long,
    private val weightOf: (V) -> Long,
) {
    private val values = LinkedHashMap<K, V>(16, 0.75f, true)
    private var currentWeight = 0L

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxWeight > 0L) { "maxWeight must be positive" }
    }

    @Synchronized
    operator fun get(key: K): V? = values[key]

    @Synchronized
    fun put(key: K, value: V) {
        val weight = weightOf(value).coerceAtLeast(0L)
        values.remove(key)?.let { currentWeight -= safeWeight(it) }
        if (weight > maxWeight) return

        values[key] = value
        currentWeight += weight
        trim()
    }

    @Synchronized
    fun remove(key: K): V? {
        val removed = values.remove(key) ?: return null
        currentWeight -= safeWeight(removed)
        return removed
    }

    @Synchronized
    fun clear() {
        values.clear()
        currentWeight = 0L
    }

    @Synchronized
    internal fun size(): Int = values.size

    @Synchronized
    internal fun weight(): Long = currentWeight

    private fun trim() {
        val iterator = values.entries.iterator()
        while ((values.size > maxEntries || currentWeight > maxWeight) && iterator.hasNext()) {
            val eldest = iterator.next()
            currentWeight -= safeWeight(eldest.value)
            iterator.remove()
        }
    }

    private fun safeWeight(value: V): Long = weightOf(value).coerceAtLeast(0L)
}
