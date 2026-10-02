package com.framenest.data.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailVlcPlanTest {

    @Test
    fun seekTargetsStayNearTheOpening() {
        assertEquals(listOf(8_000L, 20_000L), ThumbnailVlcPlan.seekTargetsMs())
        assertTrue(ThumbnailVlcPlan.seekTargetsMs().all { it in 1L..30_000L })
        assertTrue(ThumbnailVlcPlan.BUDGET_MS in 1L..10_000L)
    }
}
