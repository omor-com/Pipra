package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `app identity and launcher name match Pipra`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Pipra", appName)
    assertEquals("com.pipra.browser.app", BuildConfig.APPLICATION_ID)
    assertEquals("Muhammad Omor", context.getString(R.string.about_dev_line).removePrefix("Developed by "))
  }
}
