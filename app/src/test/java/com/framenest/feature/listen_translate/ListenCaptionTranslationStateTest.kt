package com.framenest.feature.listen_translate

import com.framenest.core.model.PlaybackIdentity
import com.framenest.data.listen_translate.ListenLanguagePair
import com.framenest.data.listen_translate.ListenTranslateCue
import com.framenest.data.listen_translate.ListenTranslateJobStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenCaptionTranslationStateTest {
    private val sourceOnlyCue = ListenTranslateCue(
        id = 1L, identity = PlaybackIdentity("test", "share", "video.mkv"),
        languages = ListenLanguagePair("ja", "zh"), startMs = 0L, endMs = 3_000L,
        textSrc = "原文", textTgt = "", rev = 1,
    )

    @Test fun ordinaryRevisionOneSourceNeedsTargetInBothTranslatedModes() {
        for (mode in listOf(ListenDisplayMode.Bilingual, ListenDisplayMode.TargetOnly)) {
            assertTrue(isListenCaptionTranslationMissing(sourceOnlyCue, mode))
        }
        assertFalse(isListenCaptionTranslationMissing(sourceOnlyCue, ListenDisplayMode.SourceOnly))
    }

    @Test fun missingCueBlankSourceAndExistingTargetDoNotClaimPendingTranslation() {
        for (cue in listOf(null, sourceOnlyCue.copy(textSrc = " \n"),
            sourceOnlyCue.copy(textTgt = "译文"), sourceOnlyCue.copy(textTgt = sourceOnlyCue.textSrc))) {
            assertFalse(isListenCaptionTranslationMissing(cue, ListenDisplayMode.Bilingual))
        }
        assertTrue(isListenCaptionTranslationMissing(sourceOnlyCue.copy(textTgt = " \n"), ListenDisplayMode.Bilingual))
    }

    @Test fun sameLanguageNeverClaimsMissingTranslation() {
        assertFalse(isListenCaptionTranslationMissing(
            sourceOnlyCue.copy(languages = ListenLanguagePair("ja", "JA")), ListenDisplayMode.Bilingual,
        ))
    }

    @Test fun failureOrCancellationDoesNotTurnMissingTextIntoARunningClaim() {
        val state = ListenTranslateUiState(enabled = true, activeCue = sourceOnlyCue)
        for (status in ListenTranslateJobStatus.entries) {
            assertTrue(state.copy(status = status, isProcessing = false, errorMessage = "翻译失败").currentTranslationMissing)
        }
        assertFalse(state.copy(enabled = false).currentTranslationMissing)
    }

    @Test fun seekClearModeChangeAndCompletedTranslationImmediatelyClearMissingState() {
        val state = ListenTranslateUiState(enabled = true, activeCue = sourceOnlyCue)
        assertTrue(state.currentTranslationMissing)
        assertFalse(state.copy(activeCue = null).currentTranslationMissing)
        assertFalse(state.copy(displayMode = ListenDisplayMode.SourceOnly).currentTranslationMissing)
        assertFalse(state.copy(activeCue = sourceOnlyCue.copy(textTgt = "译文")).currentTranslationMissing)
    }
}
