package com.pipra.browser

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Calendar

class PipraV4JvmTest {

    @Test
    fun `reader mode extracts article paragraphs and strips scripts and nav`() {
        val rawHtml = """
            <html>
              <head><title>Pipra Engineering Blog</title></head>
              <body>
                <nav><a href="/">Home</a><a href="/about">About</a></nav>
                <article>
                  <h1>Building Fast Privacy Browsers</h1>
                  <p>Modern mobile browsers need strict isolation and fast local content blocking without cloud relays.</p>
                  <script>alert("tracking beacon");</script>
                  <p>GeckoView provides WebExtension APIs and built-in tracking protection right on the device.</p>
                </article>
                <footer>Copyright Pipra</footer>
              </body>
            </html>
        """.trimIndent()

        val article = ReaderModeHelper.extractFromHtml(rawHtml, "https://example.org/blog/fast")
        assertEquals("Pipra Engineering Blog", article.title)
        assertEquals("example.org", article.host)
        assertTrue(article.paragraphs.any { it.contains("Building Fast Privacy Browsers") })
        assertTrue(article.paragraphs.any { it.contains("GeckoView provides WebExtension APIs") })
        assertFalse(article.paragraphs.any { it.contains("tracking beacon") })
        assertTrue(article.wordCount >= 20)

        val sepiaHtml = ReaderModeHelper.renderReaderHtml(article, theme = "sepia", fontSizeSp = 22)
        assertTrue(sepiaHtml.contains("#F5EEDC"))
        assertTrue(sepiaHtml.contains("font-size: 22px"))
        val darkHtml = ReaderModeHelper.renderReaderHtml(article, theme = "dark", fontSizeSp = 10)
        assertTrue(darkHtml.contains("#0F1115"))
        assertTrue(darkHtml.contains("font-size: 14px")) // clamped min 14sp
    }

    @Test
    fun `ant skins resolve keys with safe classic fallback`() {
        assertEquals(AntSkin.CLASSIC, AntSkin.fromKey(null))
        assertEquals(AntSkin.CLASSIC, AntSkin.fromKey("unknown"))
        assertEquals(AntSkin.SOLAR_AMBER, AntSkin.fromKey("amber"))
        assertEquals(AntSkin.EMERALD_LEAF, AntSkin.fromKey("emerald"))
        assertEquals(AntSkin.CRIMSON_EMBER, AntSkin.fromKey("crimson"))
    }

    @Test
    fun `focus schedule evaluates daytime and overnight windows accurately`() {
        val daytimeRule = FocusScheduleRule(
            id = "day",
            label = "Work Hours",
            hosts = listOf("youtube.com"),
            daysOfWeekMask = FocusScheduleRule.ALL_DAYS_MASK,
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 17 * 60
        )
        val calMidday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 11)
            set(Calendar.MINUTE, 30)
        }
        val calEvening = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 20)
            set(Calendar.MINUTE, 0)
        }
        assertTrue(daytimeRule.isActiveAt(calMidday.timeInMillis))
        assertFalse(daytimeRule.isActiveAt(calEvening.timeInMillis))

        val nightRule = FocusScheduleRule(
            id = "night",
            label = "Sleep Wind-Down",
            hosts = listOf("reddit.com"),
            daysOfWeekMask = FocusScheduleRule.ALL_DAYS_MASK,
            startMinuteOfDay = 22 * 60,
            endMinuteOfDay = 6 * 60
        )
        val calLate = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 15)
        }
        val calEarly = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 4)
            set(Calendar.MINUTE, 45)
        }
        assertTrue(nightRule.isActiveAt(calLate.timeInMillis))
        assertTrue(nightRule.isActiveAt(calEarly.timeInMillis))
        assertFalse(nightRule.isActiveAt(calMidday.timeInMillis))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PipraV4RobolectricTest {

    private lateinit var context: Context

    @Before
    @After
    fun clean() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pipra", Context.MODE_PRIVATE).edit().clear().commit()
        listOf(
            "pipra_policy.json",
            "pipra_blocked_sites.json",
            "pipra_rule_hmac.key",
            "pipra_applock.json",
            "pipra_focus_schedule.json",
            "pipra_privacy_stats.json",
            "pipra_site_permissions.json"
        ).forEach { File(context.filesDir, it).delete() }
    }

    @Test
    fun `app lock hashes pin, locks out after 5 invalid attempts, and disables cleanly`() {
        val lock = AppLockManager(context)
        assertFalse(lock.config().hasPin)
        assertTrue(lock.setPin("12a").isFailure)
        assertTrue(lock.setPin("123").isFailure)
        assertTrue(lock.setPin("2580").isSuccess)

        assertTrue(lock.isLockRequired(LockScope.PRIVATE_WINDOW))
        assertFalse(lock.isLockRequired(LockScope.SETTINGS))

        lock.updateScopes(
            lockOnLaunch = true,
            lockPrivateWindow = true,
            lockSettings = true,
            allowDeviceCredential = false
        )
        assertTrue(lock.isLockRequired(LockScope.LAUNCH))
        assertTrue(lock.isLockRequired(LockScope.SETTINGS))

        val now = 1_000_000L
        assertEquals(PinVerifyResult.Success, lock.verifyPin("2580", now))

        repeat(4) { attempt ->
            val res = lock.verifyPin("0000", now + attempt * 100L)
            assertTrue(res is PinVerifyResult.Invalid)
        }
        val locked = lock.verifyPin("0000", now + 500L)
        assertTrue(locked is PinVerifyResult.LockedOut)

        // Even correct PIN is rejected while lockout timer is active
        assertTrue(lock.verifyPin("2580", now + 2_000L) is PinVerifyResult.LockedOut)

        // After 30 seconds, correct PIN unlocks and disables lock cleanly
        assertEquals(PinVerifyResult.Success, lock.disableLock("2580", now + 35_000L))
        assertFalse(lock.config().hasPin)
    }

    @Test
    fun `focus schedule manager persists rules and pomodoro countdown`() {
        val manager = FocusScheduleManager(context)
        val res = manager.addRule(
            label = "Study",
            hostsInput = "youtube.com, m.tiktok.com",
            includeSubdomains = true,
            daysOfWeekMask = FocusScheduleRule.ALL_DAYS_MASK,
            startMinuteOfDay = 0,
            endMinuteOfDay = 23 * 60 + 59
        )
        assertTrue(res.isSuccess)
        assertNotNull(manager.findActiveBlock("https://www.youtube.com/watch?v=123"))

        val now = 5_000_000L
        val pomo = manager.startPomodoro(25, "reddit.com", now)
        assertTrue(pomo.isSuccess)
        val match = manager.findActiveBlock("https://old.reddit.com/r/android", now + 60_000L)
        assertNotNull(match)
        assertTrue(match!!.isPomodoro)

        manager.stopPomodoro()
        assertFalse(manager.pomodoroState().isRunning(now + 60_000L))
    }

    @Test
    fun `privacy stats manager aggregates category counters and top domains`() {
        val stats = PrivacyStatsManager(context)
        stats.recordAdBlock("https://doubleclick.net/ad.js")
        stats.recordAdBlock("https://doubleclick.net/banner.png")
        stats.recordTrackerBlock("https://analytics.example.com/p.js")
        stats.recordCookieBlock("https://pixel.social.com/c")
        stats.recordFingerprinterBlock("https://fp.tracker.io/canvas.js")
        stats.recordHttpsUpgrade("example.org")

        val snap = stats.snapshot()
        assertEquals(2L, snap.adsBlocked)
        assertEquals(1L, snap.trackersBlocked)
        assertEquals(1L, snap.cookiesBlocked)
        assertEquals(1L, snap.fingerprintersBlocked)
        assertEquals(5L, snap.totalBlocked)
        assertEquals(1L, snap.httpsUpgrades)
        assertEquals("doubleclick.net", snap.topBlockedDomains.first().first)
        assertEquals(2L, snap.topBlockedDomains.first().second)

        stats.resetStats()
        assertEquals(0L, stats.snapshot().totalBlocked)
    }

    @Test
    fun `site permission manager stores, updates, and clears per-host overrides`() {
        val perms = SitePermissionManager(context)
        assertEquals(SitePermissionDecision.ASK, perms.getDecision("https://meet.example.org/room", SitePermissionType.CAMERA))

        perms.setDecision("https://meet.example.org/room", SitePermissionType.CAMERA, SitePermissionDecision.ALLOW)
        perms.setDecision("news.example.com", SitePermissionType.NOTIFICATIONS, SitePermissionDecision.DENY)

        assertEquals(SitePermissionDecision.ALLOW, perms.getDecision("meet.example.org", SitePermissionType.CAMERA))
        assertEquals(SitePermissionDecision.DENY, perms.getDecision("https://news.example.com", SitePermissionType.NOTIFICATIONS))
        assertEquals(2, perms.listEntries().size)

        perms.removeEntry("news.example.com", SitePermissionType.NOTIFICATIONS)
        assertEquals(1, perms.listEntries().size)

        perms.clearAll()
        assertTrue(perms.listEntries().isEmpty())
    }

    @Test
    fun `extension uri validator and backup v4 roundtrip work safely`() {
        assertTrue(PipraExtensionManager.isValidExtensionInstallUri("https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"))
        assertTrue(PipraExtensionManager.isValidExtensionInstallUri("file:///data/user/0/ext.xpi"))
        assertFalse(PipraExtensionManager.isValidExtensionInstallUri("http://insecure.example.com/addon.xpi"))
        assertFalse(PipraExtensionManager.isValidExtensionInstallUri("javascript:alert(1)"))

        val store = Store(context)
        store.bottomAddressBar = true
        store.scrollToHideToolbar = true
        store.readerTheme = "dark"
        store.readerFontSizeSp = 22

        val backup = BackupManager(context)
        val json = backup.exportBackupJson()

        store.bottomAddressBar = false
        store.scrollToHideToolbar = false
        store.readerTheme = "light"
        store.readerFontSizeSp = 16

        assertTrue(backup.importBackupJson(json))
        assertTrue(store.bottomAddressBar)
        assertTrue(store.scrollToHideToolbar)
        assertEquals("dark", store.readerTheme)
        assertEquals(22, store.readerFontSizeSp)
    }

    @Test
    fun `v4 start page and focus block html render expected routes and counters`() {
        val home = BlockedPageHtml.pipraHomeHtml(
            activeEngine = "Google",
            availableEngines = Store.SEARCH_ENGINES.keys.toList(),
            bookmarks = emptyList(),
            isDark = false,
            privacyTotalBlocked = 42L,
            httpsUpgrades = 9L,
            activeExtensionCount = 2
        )
        assertTrue(home.contains("42 blocked · 9 HTTPS"))
        assertTrue(home.contains("2 ext"))
        assertTrue(home.contains("pipra://extensions"))
        assertTrue(home.contains("pipra://focus"))
        assertTrue(home.contains("pipra://stats"))

        val focusHtml = BlockedPageHtml.focusBlockedHtml(
            blockedHost = "youtube.com",
            matchLabel = "Pomodoro Focus (20m left)",
            matchDetail = "Active until 10:30 AM",
            isDark = false
        )
        assertTrue(focusHtml.contains("Focus Window Active"))
        assertTrue(focusHtml.contains("youtube.com"))
        assertTrue(focusHtml.contains("pipra://focus"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsV4UiRobolectricTest {

    @get:Rule(order = 0)
    val cleanPrefsRule = object : ExternalResource() {
        private fun wipe() {
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            ctx.getSharedPreferences("pipra", Context.MODE_PRIVATE).edit().clear().commit()
            listOf(
                "pipra_policy.json",
                "pipra_blocked_sites.json",
                "pipra_rule_hmac.key",
                "pipra_applock.json",
                "pipra_focus_schedule.json",
                "pipra_privacy_stats.json",
                "pipra_site_permissions.json"
            ).forEach { File(ctx.filesDir, it).delete() }
        }

        override fun before() = wipe()
        override fun after() = wipe()
    }

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<SettingsActivity>()

    @Test
    fun `settings search, bottom bar toggle, focus schedule, app lock, and extensions ui work`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        composeRule.waitForIdle()

        // Search filters Settings Hub
        composeRule.onNodeWithTag("settings_search_input").performTextReplacement("pomodoro")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_item_focus_schedule").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // Add focus rule
        composeRule.onNodeWithTag("focus_schedule_list").performScrollToNode(hasTestTag("focus_rule_hosts_input"))
        composeRule.onNodeWithTag("focus_rule_hosts_input").performTextReplacement("youtube.com")
        composeRule.onNodeWithTag("focus_schedule_list").performScrollToNode(hasTestTag("focus_add_rule_btn"))
        composeRule.onNodeWithTag("focus_add_rule_btn").performClick()
        composeRule.waitForIdle()
        assertEquals(1, FocusScheduleManager(ctx).rules().size)

        // Back to Hub and clear search
        composeRule.onNodeWithTag("settings_back_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_search_input").performTextReplacement("")
        composeRule.waitForIdle()

        // Appearance -> Bottom Address Bar toggle
        composeRule.onNodeWithTag("settings_hub_list").performScrollToNode(hasTestTag("settings_item_appearance"))
        composeRule.onNodeWithTag("settings_item_appearance").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("appearance_list").performScrollToNode(hasTestTag("switch_bottom_address_bar"))
        composeRule.onNodeWithTag("switch_bottom_address_bar").performClick()
        composeRule.waitForIdle()
        assertTrue(Store(ctx).bottomAddressBar)

        composeRule.onNodeWithTag("settings_back_button").performClick()
        composeRule.waitForIdle()

        // App Lock -> configure 4-digit PIN
        composeRule.onNodeWithTag("settings_hub_list").performScrollToNode(hasTestTag("settings_item_app_lock"))
        composeRule.onNodeWithTag("settings_item_app_lock").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("app_lock_pin_input").performTextReplacement("4321")
        composeRule.onNodeWithTag("app_lock_save_pin_btn").performClick()
        composeRule.waitForIdle()
        assertTrue(AppLockManager(ctx).config().hasPin)
        composeRule.onNodeWithTag("app_lock_list").performScrollToNode(hasTestTag("switch_lock_private"))
        composeRule.onNodeWithTag("switch_lock_private").assertIsDisplayed()

        composeRule.onNodeWithTag("settings_back_button").performClick()
        composeRule.waitForIdle()

        // Extensions screen displays curated catalog
        composeRule.onNodeWithTag("settings_hub_list").performScrollToNode(hasTestTag("settings_item_extensions"))
        composeRule.onNodeWithTag("settings_item_extensions").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("extensions_list").performScrollToNode(hasTestTag("catalog_ext_uBlock0@raymondhill.net"))
        composeRule.onNodeWithText("uBlock Origin").assertIsDisplayed()
    }
}
