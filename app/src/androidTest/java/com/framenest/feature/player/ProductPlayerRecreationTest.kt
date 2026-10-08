package com.framenest.feature.player

import android.content.Intent
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.framenest.R
import com.framenest.player.PlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Debug host selects the actual product screen/VM, never the kernel-only spike screen. */
class ProductPlayerRecreationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun activityRecreation_preservesPausedSession_controlsLock_andFullscreenIntent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, PlayerSpikeActivity::class.java)
            .putExtra(PlayerSpikeActivity.EXTRA_PRODUCT_LIFECYCLE_SAMPLE, true)
        ActivityScenario.launch<PlayerSpikeActivity>(intent).use { scenario ->
            compose.waitUntil(20_000L) {
                compose.onAllNodesWithTag("player_play").fetchSemanticsNodes().isNotEmpty()
            }
            lateinit var original: PlayerViewModel
            scenario.onActivity {
                original = ViewModelProvider(it)[PRODUCT_VM_KEY, PlayerViewModel::class.java]
            }
            compose.waitUntil(20_000L) { original.controller.state.value.firstFrameReady }
            scenario.onActivity {
                original.setPlaybackRate(0.5f)
                original.play()
                original.pause()
                assertEquals(PlayerState.Phase.Paused, original.controller.state.value.phase)
            }
            compose.onNodeWithTag("player_fullscreen").performClick()
            compose.onNodeWithTag("player_lock_controls").performClick()
            compose.onNodeWithTag("player_unlock_overlay").assertIsDisplayed()
            scenario.recreate()
            compose.waitUntil(20_000L) {
                compose.onAllNodesWithTag("player_unlock_overlay").fetchSemanticsNodes().isNotEmpty()
            }
            scenario.onActivity {
                val restored = ViewModelProvider(it)[PRODUCT_VM_KEY, PlayerViewModel::class.java]
                assertSame(original, restored)
                assertEquals(PlayerState.Phase.Paused, restored.controller.state.value.phase)
                assertTrue(restored.controller.state.value.firstFrameReady)
            }
            compose.onNodeWithTag("player_unlock").performClick()
            compose.onNodeWithTag("player_fullscreen")
                .assertContentDescriptionEquals(context.getString(R.string.player_exit_fullscreen_cd))
            compose.onNodeWithTag("player_back").performClick()
            compose.waitUntil(10_000L) { original.controller.state.value.phase == PlayerState.Phase.Idle }
        }
    }

    private companion object {
        const val PRODUCT_VM_KEY = "product-lifecycle|samples|sample_h264.mp4"
    }
}
