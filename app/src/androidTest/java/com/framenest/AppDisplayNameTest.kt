package com.framenest

import android.content.ComponentName
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Display branding must never change the installed app identity or launcher target. */
@RunWith(AndroidJUnit4::class)
class AppDisplayNameTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun applicationAndLauncherUseYingzhouWithoutChangingPackage() {
        assertEquals("com.framenest", context.packageName)
        val packageManager = context.packageManager
        assertEquals("映舟", packageManager.getApplicationLabel(context.applicationInfo).toString())
        val launcher = packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertEquals("映舟", launcher.loadLabel(packageManager).toString())
    }

    @Test
    fun chineseNameIsStableAcrossPhoneTabletAndLocales() {
        for (width in listOf(360, 840)) {
            for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.US)) {
                val configuration = Configuration(context.resources.configuration).apply {
                    setLocale(locale)
                    screenWidthDp = width
                    smallestScreenWidthDp = width
                }
                val resources = context.createConfigurationContext(configuration).resources
                assertEquals("映舟", resources.getString(R.string.app_name))
            }
        }
    }
}
