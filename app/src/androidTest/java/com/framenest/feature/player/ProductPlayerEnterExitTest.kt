package com.framenest.feature.player

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FN-05 stress: enter/exit player kernel 20 times using local raw sample.
 * Same [VlcPlayerController] path as product [PlayerViewModel].
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ProductPlayerEnterExitTest {

    @Test
    fun enterAndExit_player_20_times_withoutCrash() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.framenest", appContext.packageName)

        repeat(20) { iteration ->
            ActivityScenario.launch(PlayerSpikeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertTrue(
                        "Activity should not be finishing on iteration $iteration",
                        !activity.isFinishing,
                    )
                }
                Thread.sleep(250)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.DESTROYED)
            }
        }
    }
}
