package com.framenest.feature.player

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSurfaceInputPolicyTest {

    @Test
    fun cancelledHorizontalGesture_clearsSeekWithoutCommit() {
        val cancelled = PlayerSurfaceInputPolicy.gestureEndAction(
            axis = GestureSeekMath.Axis.Horizontal,
            reason = PlayerSurfaceInputPolicy.GestureEndReason.Cancel,
        )
        assertFalse(cancelled.commitSeek)
        assertTrue(cancelled.clearSeekUi)
        assertFalse(cancelled.endLevelGesture)

        val released = PlayerSurfaceInputPolicy.gestureEndAction(
            axis = GestureSeekMath.Axis.Horizontal,
            reason = PlayerSurfaceInputPolicy.GestureEndReason.Release,
        )
        assertTrue(released.commitSeek)
        assertTrue(released.clearSeekUi)
    }

    @Test
    fun verticalGesture_alwaysEndsTransientIndicator() {
        PlayerSurfaceInputPolicy.GestureEndReason.entries.forEach { reason ->
            val action = PlayerSurfaceInputPolicy.gestureEndAction(
                axis = GestureSeekMath.Axis.Vertical,
                reason = reason,
            )
            assertTrue(action.endLevelGesture)
            assertFalse(action.commitSeek)
        }
    }

    @Test
    fun keyboardMapsActivationAndTimelineKeys() {
        assertEquals(
            PlayerSurfaceInputPolicy.KeyAction.Activate,
            PlayerSurfaceInputPolicy.keyAction(Key.Spacebar, KeyEventType.KeyDown),
        )
        assertEquals(
            PlayerSurfaceInputPolicy.KeyAction.Activate,
            PlayerSurfaceInputPolicy.keyAction(Key.Enter, KeyEventType.KeyDown),
        )
        assertEquals(
            PlayerSurfaceInputPolicy.KeyAction.SkipBack,
            PlayerSurfaceInputPolicy.keyAction(Key.DirectionLeft, KeyEventType.KeyDown),
        )
        assertEquals(
            PlayerSurfaceInputPolicy.KeyAction.SkipForward,
            PlayerSurfaceInputPolicy.keyAction(Key.DirectionRight, KeyEventType.KeyDown),
        )
        assertEquals(
            PlayerSurfaceInputPolicy.KeyAction.Consume,
            PlayerSurfaceInputPolicy.keyAction(Key.Spacebar, KeyEventType.KeyUp),
        )
        assertEquals(
            PlayerSurfaceInputPolicy.KeyAction.Ignore,
            PlayerSurfaceInputPolicy.keyAction(Key.Tab, KeyEventType.KeyDown),
        )
    }
}
