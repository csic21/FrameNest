package com.framenest.data.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundedLruCacheTest {
    @Test
    fun `five hundred inserts retain only configured entry capacity`() {
        val cache = BoundedLruCache<Int, Int>(
            maxEntries = 64,
            maxWeight = 10_000L,
            weightOf = { 1L },
        )

        repeat(500) { cache.put(it, it) }

        assertEquals(64, cache.size())
        assertNull(cache[435])
        assertEquals(499, cache[499])
    }

    @Test
    fun `recent access protects entry when weight limit evicts`() {
        val cache = BoundedLruCache<String, Int>(
            maxEntries = 10,
            maxWeight = 6L,
            weightOf = { it.toLong() },
        )
        cache.put("a", 2)
        cache.put("b", 2)
        cache.put("c", 2)
        cache["a"]

        cache.put("d", 2)

        assertNull(cache["b"])
        assertEquals(2, cache["a"])
        assertEquals(6L, cache.weight())
    }
}
