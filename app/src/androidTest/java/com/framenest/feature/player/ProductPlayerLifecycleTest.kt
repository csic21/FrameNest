package com.framenest.feature.player

import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.framenest.R
import com.framenest.core.model.PlaybackDataSource
import com.framenest.core.model.PlaybackIdentity
import com.framenest.core.model.PlaybackRequest
import com.framenest.player.PlayerState
import com.framenest.ui.theme.FrameNestTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test

/** The actual product screen/VM/navigation entry with bundled media, not PlayerSpikeActivity. */
class ProductPlayerLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun backgroundResume_manualPause_andRepeatedExitUseFreshProductSessions() {
        val identity = PlaybackIdentity("fn54-local", "samples", "${System.nanoTime()}.mp4")
        val request = PlaybackRequest(identity, "Lifecycle sample", PlaybackDataSource.LocalRawResource(R.raw.sample_h264))
        val key = "${identity.serverId}|${identity.share}|${identity.path}"
        var current: PlayerViewModel? = null
        compose.setContent {
            FrameNestTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "home") {
                    composable("home") {
                        Button(onClick = { nav.navigate("player") }, modifier = Modifier.testTag("open_sample")) {
                            Text("Open")
                        }
                    }
                    composable("player") { entry ->
                        PlayerScreen(request, onBack = { nav.popBackStack() })
                        // PlayerScreen has already created this entry's real VM.
                        val vm = remember(entry) { ViewModelProvider(entry)[key, PlayerViewModel::class.java] }
                        DisposableEffect(vm) {
                            current = vm
                            onDispose { if (current === vm) current = null }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("open_sample").performClick()
        await { current?.controller?.state?.value?.firstFrameReady == true }
        val first = checkNotNull(current)
        compose.runOnIdle {
            assertEquals(PlayerState.Phase.Ready, first.controller.state.value.phase)
            first.setPlaybackRate(0.5f)
        }
        compose.onNodeWithTag("player_play").performClick()
        await { first.controller.state.value.phase == PlayerState.Phase.Playing }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await { first.controller.state.value.phase == PlayerState.Phase.Playing }
        compose.runOnIdle { first.pause() }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await { !first.controller.state.value.isSeeking }
        compose.runOnIdle { assertEquals(PlayerState.Phase.Paused, first.controller.state.value.phase) }
        compose.onNodeWithTag("player_back").performClick()
        compose.runOnIdle {
            assertEquals(PlayerState.Phase.Idle, first.controller.state.value.phase)
            assertFalse(compose.activity.window.decorView.keepScreenOn)
        }
        compose.onNodeWithTag("open_sample").performClick()
        await { current?.controller?.state?.value?.firstFrameReady == true }
        compose.runOnIdle {
            assertNotSame(first, current)
            assertEquals(PlayerState.Phase.Ready, current!!.controller.state.value.phase)
        }
        compose.onNodeWithTag("player_back").performClick()
    }

    private fun await(predicate: () -> Boolean) {
        compose.waitUntil(timeoutMillis = 20_000L, condition = predicate)
    }
}
