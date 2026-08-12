package com.framenest.data.thumbnail

/** Identity of one thumbnail extraction attempt inside a cache generation. */
internal data class ThumbnailWorkKey(
    val generation: Long,
    val digest: String,
)

/**
 * Small keyed queue for thumbnail work that is still interesting to the UI.
 *
 * Pending work can be removed when its last Compose row leaves the viewport.
 * In-flight keys stay registered until [finish] so recomposition cannot enqueue
 * duplicate SMB extraction for the same thumbnail.
 */
internal class ThumbnailWorkQueue<T> {
    private val pending = LinkedHashMap<ThumbnailWorkKey, T>()
    private val inFlight = HashSet<ThumbnailWorkKey>()

    @Synchronized
    fun offer(key: ThumbnailWorkKey, work: T): Boolean {
        if (key in pending || key in inFlight) return false
        pending[key] = work
        return true
    }

    @Synchronized
    fun cancelPending(key: ThumbnailWorkKey): Boolean = pending.remove(key) != null

    @Synchronized
    fun takeNext(): Pair<ThumbnailWorkKey, T>? {
        val entry = pending.entries.firstOrNull() ?: return null
        pending.remove(entry.key)
        inFlight += entry.key
        return entry.key to entry.value
    }

    @Synchronized
    fun finish(key: ThumbnailWorkKey): Boolean = inFlight.remove(key)

    @Synchronized
    fun hasPending(): Boolean = pending.isNotEmpty()

    @Synchronized
    fun clear() {
        pending.clear()
        inFlight.clear()
    }

    @Synchronized
    internal fun pendingSize(): Int = pending.size

    @Synchronized
    internal fun inFlightSize(): Int = inFlight.size
}
