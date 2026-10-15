package com.framenest.feature.listen_translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTranslateQualityChoiceTest {
    private val availableJapanese = ListenTranslateUiState(
        sourceLang = "ja",
        targetLang = "zh",
        highQualityAvailable = true,
    )

    @Test
    fun qualityChoiceDefaultsOffEvenWhenNativeIsAvailable() {
        assertFalse(ListenTranslateUiState().highQualityJapanese)
        assertFalse(ListenTranslateUiState().highQualityAvailable)
        assertFalse(availableJapanese.highQualityJapanese)
        assertTrue(availableJapanese.canSelectHighQualityJapanese)
        assertTrue(availableJapanese.canChangeHighQualityJapanese)
    }

    @Test
    fun choiceRequiresAvailableBackendAndJapaneseToChineseTranslation() {
        assertFalse(availableJapanese.copy(highQualityAvailable = false).canSelectHighQualityJapanese)
        assertFalse(availableJapanese.copy(sourceLang = "en").canSelectHighQualityJapanese)
        assertFalse(availableJapanese.copy(targetLang = "en").canSelectHighQualityJapanese)
        assertFalse(availableJapanese.copy(displayMode = ListenDisplayMode.SourceOnly).canSelectHighQualityJapanese)
        assertTrue(availableJapanese.copy(displayMode = ListenDisplayMode.TargetOnly).canSelectHighQualityJapanese)
        assertTrue(availableJapanese.copy(sourceLang = " JA ", targetLang = " ZH ").canSelectHighQualityJapanese)
    }

    @Test
    fun selectingQualityDoesNotEnableListeningOrStartInstallation() {
        val selected = availableJapanese.copy(highQualityJapanese = true)
        assertTrue(selected.highQualityJapanese)
        assertFalse(selected.enabled)
        assertFalse(selected.isInstallingModels)
        assertFalse(selected.modelsReady)
    }

    @Test
    fun qualityCannotChangeDuringListeningOrInstallation() {
        listOf(false, true).forEach { selected ->
            assertFalse(availableJapanese.copy(highQualityJapanese = selected, enabled = true).canChangeHighQualityJapanese)
            assertFalse(availableJapanese.copy(highQualityJapanese = selected, isInstallingModels = true).canChangeHighQualityJapanese)
        }
    }

    @Test
    fun unavailableSavedChoiceCanStillBeTurnedOff() {
        val unavailable = availableJapanese.copy(highQualityAvailable = false)
        assertFalse(unavailable.canChangeHighQualityJapanese)
        assertTrue(unavailable.copy(highQualityJapanese = true).canChangeHighQualityJapanese)
        assertTrue(availableJapanese.copy(highQualityJapanese = true, targetLang = "en").canChangeHighQualityJapanese)
        assertTrue(availableJapanese.copy(highQualityJapanese = true, displayMode = ListenDisplayMode.SourceOnly).canChangeHighQualityJapanese)
    }

    @Test
    fun audioReadProgressAndUnconfirmedCaptionDoNotCertifyCoverageOrHideErrors() {
        val pending = availableJapanese.copy(
            currentCaptionUnconfirmed = true,
            recordedReadThroughMs = 12_000L,
            errorMessage = "翻译失败",
        )
        assertEquals(0L, pending.coveredUntilMs)
        assertEquals(0L, pending.preparedAheadMs)
        assertEquals(0, pending.generatedCueCount)
        assertTrue(pending.currentCaptionUnconfirmed)
        assertEquals("翻译失败", pending.errorMessage)
    }
}
