package com.framenest.data.thumbnail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailCacheGenerationTest {
    @Test
    fun `advance invalidates queued and in flight generation`() {
        val generation = ThumbnailCacheGeneration()
        val oldWork = generation.current()

        val newWork = generation.advance()

        assertFalse(generation.isCurrent(oldWork))
        assertTrue(generation.isCurrent(newWork))
    }
}
