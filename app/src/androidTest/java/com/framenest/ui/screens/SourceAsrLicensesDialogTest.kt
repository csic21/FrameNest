package com.framenest.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.test.platform.app.InstrumentationRegistry
import com.framenest.BuildConfig
import com.framenest.ui.theme.FrameNestTheme
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Compiled by ordinary CI; execution still requires a phone and tablet test device. */
class SourceAsrLicensesDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun stableProfileHasNoEntryAndNeverLoadsAbsentAssets() {
        compose.setContent {
            FrameNestTheme {
                SourceAsrLicensesEntry(sourceProfile = false) { error("Stable must not open assets") }
            }
        }
        compose.onNodeWithTag("source_asr_licenses_entry").assertDoesNotExist()
        compose.onNodeWithTag("source_asr_licenses_dialog").assertDoesNotExist()
    }

    @Test
    fun defaultVisibilityFollowsBuildProfileAndDoesNotLoadBeforeClick() {
        compose.setContent {
            FrameNestTheme {
                SourceAsrLicensesEntry(loadNotice = { error("Closed entry must not read assets") })
            }
        }
        if (BuildConfig.SHERPA_ASR_SOURCE) {
            compose.onNodeWithTag("source_asr_licenses_entry").assertIsDisplayed()
        } else {
            compose.onNodeWithTag("source_asr_licenses_entry").assertDoesNotExist()
        }
    }

    @Test
    fun supplementOpensFirstAndAll29OriginalNoticesAreReachable() {
        showEntry { listOf(it) }
        openEntry()
        waitForText()
        compose.onNodeWithTag("source_asr_notice_text")
            .assertTextEquals(SourceAsrLicenses.supplemental.assetPath)
        for (notice in SourceAsrLicenses.aarNotices) {
            compose.onNodeWithTag("source_asr_licenses_back").performClick()
            compose.onNodeWithTag("source_asr_licenses_index").performScrollToKey(notice.assetPath)
            compose.onNodeWithTag("source_asr_notice_${notice.assetPath}").performClick()
            waitForText()
            compose.onNodeWithTag("source_asr_notice_text").assertTextEquals(notice.assetPath)
        }
    }

    @Test
    fun pageNavigationIsBoundedAndClosingReopensAtSupplementPageOne() {
        showEntry { listOf("first page", "last page") }
        openEntry()
        waitForText()
        compose.onNodeWithTag("source_asr_page_count").assertTextEquals("1 / 2 页")
        compose.onNodeWithTag("source_asr_page_previous").assertIsNotEnabled()
        compose.onNodeWithTag("source_asr_page_next").performClick()
        compose.onNodeWithTag("source_asr_notice_text").assertTextEquals("last page")
        compose.onNodeWithTag("source_asr_page_count").assertTextEquals("2 / 2 页")
        compose.onNodeWithTag("source_asr_page_next").assertIsNotEnabled()
        compose.onNodeWithTag("source_asr_page_previous").performClick()
        compose.onNodeWithTag("source_asr_notice_text").assertTextEquals("first page")
        compose.onNodeWithTag("source_asr_page_next").performClick()
        compose.onNodeWithTag("source_asr_licenses_close").performClick()
        compose.onNodeWithTag("source_asr_licenses_dialog").assertDoesNotExist()
        openEntry()
        waitForText()
        compose.onNodeWithTag("source_asr_page_count").assertTextEquals("1 / 2 页")
    }

    @Test
    fun unreadableNoticeKeepsReturnNavigationAndOtherFilesWork() {
        showEntry { path ->
            if (path == SourceAsrLicenses.supplemental.assetPath) throw IOException("Missing or corrupt")
            listOf("original license")
        }
        openEntry()
        waitForTag("source_asr_notice_error")
        compose.onNodeWithTag("source_asr_licenses_back").performClick()
        val first = SourceAsrLicenses.aarNotices.first()
        compose.onNodeWithTag("source_asr_notice_${first.assetPath}").performClick()
        waitForText()
        compose.onNodeWithTag("source_asr_notice_text").assertTextEquals("original license")
        compose.onNodeWithTag("source_asr_licenses_close").performClick()
        openEntry()
        waitForTag("source_asr_notice_error")
    }

    @Test
    fun closingAndReturningToIndexCancelLoadsWithoutStaleStateOnReopen() {
        val started = AtomicInteger()
        val cancelled = AtomicInteger()
        showEntry {
            started.incrementAndGet()
            try {
                awaitCancellation()
            } finally {
                cancelled.incrementAndGet()
            }
        }
        openEntry()
        compose.waitUntil(5_000) { started.get() == 1 }
        compose.onNodeWithTag("source_asr_licenses_close").performClick()
        compose.waitUntil(5_000) { cancelled.get() == 1 }
        openEntry()
        compose.waitUntil(5_000) { started.get() == 2 }
        compose.onNodeWithTag("source_asr_licenses_back").performClick()
        compose.waitUntil(5_000) { cancelled.get() == 2 }
        compose.onNodeWithTag("source_asr_licenses_index").assertIsDisplayed()
        compose.onNodeWithTag("source_asr_notice_text").assertDoesNotExist()
    }

    @Test
    fun sourceApkContainsAllPinnedReadableNotices() = runBlocking {
        assumeTrue(BuildConfig.SHERPA_ASR_SOURCE)
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        for (notice in listOf(SourceAsrLicenses.supplemental) + SourceAsrLicenses.aarNotices) {
            val pages = readSourceAsrNotice(notice.assetPath) { assets.open(it) }
            assertTrue(pages.isNotEmpty())
            assertTrue(pages.all { it.length <= SourceAsrLicenses.PAGE_CHARS })
            val original = assets.open(notice.assetPath).use { it.readBytes().toString(Charsets.UTF_8) }
            assertEquals(original, pages.joinToString(""))
        }
    }

    private fun showEntry(load: suspend (String) -> List<String>) {
        compose.setContent {
            FrameNestTheme { SourceAsrLicensesEntry(sourceProfile = true, loadNotice = load) }
        }
    }

    private fun openEntry() {
        compose.onNodeWithTag("source_asr_licenses_entry").performClick()
    }

    private fun waitForText() = waitForTag("source_asr_notice_text")

    private fun waitForTag(tag: String) {
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    }
}
