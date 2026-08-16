package com.framenest.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlayerViewportTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun changingOrHidingChrome_keepsSurfaceBoundsStable() {
        val chromeVisible = mutableStateOf(true)
        val tallBottomChrome = mutableStateOf(false)

        composeRule.setContent {
            PlayerViewport(
                modifier = Modifier.size(width = 360.dp, height = 640.dp),
                surface = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                    )
                },
                topChrome = if (chromeVisible.value) {
                    {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp),
                        )
                    }
                } else {
                    null
                },
                bottomChrome = if (chromeVisible.value) {
                    {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (tallBottomChrome.value) 260.dp else 120.dp),
                        )
                    }
                } else {
                    null
                },
            )
        }

        val initialBounds = surfaceBounds()

        tallBottomChrome.value = true
        composeRule.waitForIdle()
        assertEquals(initialBounds, surfaceBounds())

        chromeVisible.value = false
        composeRule.waitForIdle()
        assertEquals(initialBounds, surfaceBounds())
    }

    private fun surfaceBounds() = composeRule
        .onNodeWithTag(PLAYER_VIEWPORT_SURFACE_HOST_TAG)
        .getUnclippedBoundsInRoot()
}
