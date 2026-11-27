package com.pipra.browser

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsUiRobolectricTest {

    @get:Rule(order = 0)
    val cleanPrefsRule = object : ExternalResource() {
        private fun wipe() {
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            ctx.getSharedPreferences("pipra", Context.MODE_PRIVATE).edit().clear().commit()
            File(ctx.filesDir, "pipra_blocked_sites.json").delete()
            File(ctx.filesDir, "pipra_rule_hmac.key").delete()
            File(ctx.filesDir, "pipra_policy.json").delete()
        }

        override fun before() = wipe()
        override fun after() = wipe()
    }

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<SettingsActivity>()

    @Test
    fun `settings hub shows all 10 sections and locks newly added website rule in UI`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.waitForIdle()
        listOf(
            "settings_item_appearance",
            "settings_item_ad_blocker",
            "settings_item_website_blocking",
            "settings_item_adult_protection",
            "settings_item_privacy",
            "settings_item_security",
            "settings_item_downloads",
            "settings_item_search_engine",
            "settings_item_data_management",
            "settings_item_about"
        ).forEach { tag ->
            composeRule.onNodeWithTag("settings_hub_list").performScrollToNode(hasTestTag(tag))
            composeRule.onNodeWithTag(tag).assertIsDisplayed()
        }

        // Navigate into Website Blocking and add m.youtube.com
        composeRule.onNodeWithTag("settings_hub_list").performScrollToNode(hasTestTag("settings_item_website_blocking"))
        composeRule.onNodeWithTag("settings_item_website_blocking").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("website_blocking_list").performScrollToNode(hasTestTag("block_host_input"))
        composeRule.onNodeWithTag("block_host_input").performTextInput("m.youtube.com")
        composeRule.onNodeWithTag("website_blocking_list").performScrollToNode(hasTestTag("website_blocking_after_form"))
        composeRule.onNodeWithTag("block_add_rule_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("confirm_lock_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("website_blocking_list").performScrollToNode(hasTestTag("blocked_rule_m.youtube.com"))
        composeRule.onNodeWithTag("blocked_rule_m.youtube.com").assertIsDisplayed()
        composeRule.onNodeWithText("m.youtube.com (exact host)").assertIsDisplayed()
        // Active locked rule must NOT render edit or delete buttons
        composeRule.onNodeWithTag("edit_rule_m.youtube.com").assertDoesNotExist()
        composeRule.onNodeWithTag("delete_rule_m.youtube.com").assertDoesNotExist()

        // Navigate back to Hub and open Ad Blocker
        composeRule.onNodeWithTag("settings_back_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_hub_list").performScrollToNode(hasTestTag("settings_item_ad_blocker"))
        composeRule.onNodeWithTag("settings_item_ad_blocker").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("adblock_list").performScrollToNode(hasTestTag("adblock_mode_ultimate"))
        composeRule.onNodeWithTag("adblock_mode_ultimate").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("adblock_mode_ultimate").assertIsSelected()
        assertEquals(AdBlockMode.ULTIMATE, Store(context).adBlockMode)
    }
}
