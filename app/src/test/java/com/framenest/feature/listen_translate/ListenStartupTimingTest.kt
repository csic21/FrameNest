package com.framenest.feature.listen_translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenStartupTimingTest {
    @Test fun startupWallTimeIncludesPreparationQueueAndPublicationWithoutSummingOverlap() {
        var now = 0L
        val timer = ListenStartupTimer { now }
        val token = timer.begin()
        now = 200L // includes queue/connection before ASR loading
        now += 500L
        timer.recordAsrPreparation(token, 500L)
        now += 350L // PCM + ASR, with MT preparation running concurrently
        timer.publish(hasSource = true, hasBilingual = false)
        timer.select(sourceVisible = true, bilingualVisible = false)
        now += 150L // translation and cache/publication
        timer.publish(hasSource = true, hasBilingual = true)
        now += 50L // next player tick makes this cue eligible
        timer.select(sourceVisible = true, bilingualVisible = true)
        assertEquals(500L, timer.sample?.asrPreparationMs)
        assertEquals(1050L, timer.sample?.firstSourcePublishedMs)
        assertEquals(1050L, timer.sample?.firstSourceSelectedMs)
        assertEquals(1200L, timer.sample?.firstBilingualPublishedMs)
        assertEquals(1250L, timer.sample?.firstBilingualSelectedMs)
        now += 9000L
        timer.publish(true, true)
        timer.select(true, true)
        assertEquals(1250L, timer.sample?.firstBilingualSelectedMs)
    }

    @Test fun firstPcmUsesEventClockRatherThanCallbackDeliveryAndRejectsStaleToken() {
        var now = 10L
        val timer = ListenStartupTimer { now }
        val stale = timer.begin()
        now = 50L
        val token = timer.begin()
        now = 500L // dispatch to the UI may lag the PCM event
        timer.recordFirstPcm(stale, 100L)
        assertNull(timer.sample?.firstPcmReadyMs)
        timer.recordFirstPcm(token, 120L)
        timer.recordFirstPcm(token, 200L)
        assertEquals(70L, timer.sample?.firstPcmReadyMs)
        timer.reset()
        timer.recordFirstPcm(token, 600L)
        assertNull(timer.sample)
    }

    @Test fun explicitPreparationCapturesTokenAtEmissionAndRejectsQueuedOldEventInSameMillisecond() {
        var now = 100L
        val timer = ListenStartupTimer { now }
        timer.begin()
        val queuedEventToken = timer.token
        timer.begin() // same millisecond, distinct explicit preparation
        timer.recordFirstPcm(queuedEventToken, 100L)
        assertNull(timer.sample?.firstPcmReadyMs)
        val newEventToken = timer.token
        timer.recordFirstPcm(newEventToken, 90L)
        assertNull(timer.sample?.firstPcmReadyMs)
        now = 150L
        timer.recordFirstPcm(newEventToken, 150L)
        assertEquals(50L, timer.sample?.firstPcmReadyMs)
        timer.reset()
        timer.recordFirstPcm(newEventToken, 200L)
        assertNull(timer.sample)
    }

    @Test fun generatedBreakdownIsRetainedIndependentlyOfWarmCacheAndLaterWindows() {
        val timer = ListenStartupTimer { 0L }
        timer.begin()
        timer.publish(true, true) // warm cache
        timer.select(true, true)
        assertNull(timer.sample?.firstGeneratedBilingualStages)
        val first = ListenStageTimings(pcmReadMs = 20L, asrMs = 50L, translationPreparationMs = 40L)
        timer.recordGeneratedBilingual(first, 7L)
        timer.recordGeneratedBilingual(ListenStageTimings(asrMs = 999L), 30L)
        assertEquals(first, timer.sample?.firstGeneratedBilingualStages)
        assertEquals(7L, timer.sample?.firstGeneratedBilingualCachePublishMs)
        assertEquals(0L, timer.sample?.firstBilingualSelectedMs)
    }

    @Test fun publishedExpiredCueDoesNotImplyOverlaySelection() {
        var now = 10L
        val timer = ListenStartupTimer { now }
        timer.begin()
        now = 80L
        timer.publish(true, true)
        timer.select(false, false)
        assertEquals(70L, timer.sample?.firstBilingualPublishedMs)
        assertNull(timer.sample?.firstSourceSelectedMs)
        assertNull(timer.sample?.firstBilingualSelectedMs)
    }

    @Test fun resetRejectsOldPreparationAndClearsAllMilestones() {
        var now = 0L
        val timer = ListenStartupTimer { now }
        val stale = timer.begin()
        timer.publish(true, true)
        timer.select(true, true)
        timer.reset()
        timer.recordAsrPreparation(stale, 300L)
        timer.publish(true, true)
        timer.select(true, true)
        assertNull(timer.sample)
        now = 100L
        val current = timer.begin()
        timer.recordAsrPreparation(stale, 300L)
        assertNull(timer.sample?.asrPreparationMs)
        timer.recordAsrPreparation(current, 0L)
        timer.select(true, false)
        assertEquals(0L, timer.sample?.asrPreparationMs)
        assertEquals(0L, timer.sample?.firstSourceSelectedMs)
        assertNull(timer.sample?.firstBilingualSelectedMs)
    }

    @Test fun emptyAndSourceOnlyDoNotPretendBilingualReadiness() {
        val timer = ListenStartupTimer { 0L }
        timer.begin()
        timer.publish(false, false)
        timer.select(false, false)
        assertNull(timer.sample?.firstSourcePublishedMs)
        timer.publish(true, false)
        timer.select(true, false)
        assertEquals(0L, timer.sample?.firstSourcePublishedMs)
        assertNull(timer.sample?.firstBilingualPublishedMs)
        assertNull(timer.sample?.firstBilingualSelectedMs)
        assertTrue(formatListenStartupTiming(timer.sample!!).contains("非屏幕渲染"))
    }
}
