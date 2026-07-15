package com.framenest.feature.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSelectionGateTest {

    @Test
    fun newerUserSelectionInvalidatesOlderAsyncWork() {
        val gate = SubtitleSelectionGate()
        val automaticScan = gate.snapshot()
        assertTrue(gate.isCurrent(automaticScan))

        val manualSelection = gate.advance()

        assertFalse(gate.isCurrent(automaticScan))
        assertTrue(gate.isCurrent(manualSelection))
    }
}
