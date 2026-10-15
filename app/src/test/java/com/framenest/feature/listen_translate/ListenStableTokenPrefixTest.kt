package com.framenest.feature.listen_translate

import org.junit.Assert.assertEquals
import org.junit.Test

class ListenStableTokenPrefixTest {
    private fun tokens(vararg text: String) = text.toList()
    @Test fun punctuationCannotInventExtraStableWords() {
        assertEquals(4, listenStableTokenPrefix("この意味。", tokens("こ", "の", "意", "味", "は")))
    }
    @Test fun preservesWholeMultiCharacterToken() {
        assertEquals(1, listenStableTokenPrefix("英語中国", tokens("英語", "中国語")))
    }
    @Test fun earlyDisagreementDoesNotResumeAfterMatchingSuffix() {
        assertEquals(1, listenStableTokenPrefix("この意味", tokens("こ", "れ", "意", "味")))
    }
    @Test fun normalizationAllowsSpacingAndWidthNotTextSubstitution() {
        assertEquals(3, listenStableTokenPrefix("Ａ１、日本", tokens("A1", "、", "日本")))
    }
    @Test fun emptyAgreementNeverCertifiesPunctuation() {
        assertEquals(0, listenStableTokenPrefix("!", tokens("!", "日本")))
    }
}
