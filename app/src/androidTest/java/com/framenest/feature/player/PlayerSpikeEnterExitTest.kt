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
 * Acceptance stress: enter/exit the player spike page 20 times without crash.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class PlayerSpikeEnterExitTest {

    @Test
    fun enterAndExit_playerSpike_20_times_withoutCrash() {
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
                // Brief settle so prepare/attach can run before destroy/release.
                Thread.sleep(250)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.DESTROYED)
            }
        }
    }
}
