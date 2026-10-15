package com.framenest.data.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses an isolated preference file, never the installed app's saved settings. */
class UserPreferencesQualityChoiceTest {
    private val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val fileName = "quality-choice-test-${UUID.randomUUID()}"
    private val context = object : ContextWrapper(baseContext) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences(fileName, mode)
    }

    @After
    fun cleanUpIsolatedFile() {
        baseContext.deleteSharedPreferences(fileName)
    }

    @Test
    fun preferenceDefaultsOffAndRequiresExplicitOptIn() {
        assertFalse(UserPreferences(context).highQualityJapanese())
        UserPreferences(context).setHighQualityJapanese(true)
        assertTrue(UserPreferences(context).highQualityJapanese())
        UserPreferences(context).setHighQualityJapanese(false)
        assertFalse(UserPreferences(context).highQualityJapanese())
    }

    @Test
    fun qualityOptInDoesNotPermitMeteredDownloadsOrChangeAsrSelection() {
        val preferences = UserPreferences(context)
        preferences.setAsrEngine(AsrEngineChoice.VOSK)
        preferences.setHighQualityJapanese(true)
        assertFalse(preferences.allowMeteredModelDownloads())
        org.junit.Assert.assertEquals(AsrEngineChoice.VOSK, preferences.asrEngine())
    }
}
