package com.pipra.browser

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Pure JVM JUnit4 tests that execute even in fully offline CI/sandbox containers. */
class PipraV3JvmTest {

    @Test
    fun `local file classifier detects magic bytes and file kinds`() {
        assertEquals(
            LocalFileKind.PDF,
            LocalFileClassifier.classify("unknown.bin", "application/octet-stream", "%PDF-1.7\n".toByteArray())
        )
        assertEquals(
            LocalFileKind.HTML,
            LocalFileClassifier.classify("page.html", "text/html", ByteArray(0))
        )
        assertEquals(
            LocalFileKind.ZIP,
            LocalFileClassifier.classify("bundle.zip", "application/zip", byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        )
        assertEquals(
            LocalFileKind.APK,
            LocalFileClassifier.classify("app.apk", "application/vnd.android.package-archive", byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        )
    }

    @Test
    fun `virtual path normalizer blocks parent directory escapes`() {
        assertEquals("css/app.css", LocalWebProjectResolver.normalizeVirtualPath("/assets/../css/app.css"))
        assertNull(LocalWebProjectResolver.normalizeVirtualPath("../secret.txt"))
        assertNull(LocalWebProjectResolver.normalizeVirtualPath("a/../../etc/passwd"))
    }

    @Test
    fun `app theme mode and download status enums default cleanly`() {
        assertEquals(AppThemeMode.LIGHT, AppThemeMode.fromKey(null))
        assertEquals(AppThemeMode.LIGHT, AppThemeMode.fromKey("unknown"))
        assertEquals(AppThemeMode.DARK, AppThemeMode.fromKey("dark"))
        assertEquals(DownloadStatus.PAUSED, DownloadStatus.fromName("PAUSED"))
        assertEquals("1.5 MB", PipraDownloadManager.formatBytes(1536L * 1024L))
        assertEquals("text/html", PipraDownloadManager.resolveMimeType("index.html", null, null))
        assertEquals("application/json", PipraDownloadManager.resolveMimeType("data.json", "application/octet-stream", null))
    }

    @Test
    fun `domain matching and download sanitization work strictly`() {
        assertFalse(DomainUtils.matchesHost("notexample.com", "example.com", includeSubdomains = true))
        assertTrue(DomainUtils.matchesHost("sub.example.com", "example.com", includeSubdomains = true))
        val cleanName = DomainUtils.sanitizeDownloadFileName("../../etc/passwd:evil*.html")
        assertFalse(cleanName.contains(".."))
        assertFalse(cleanName.contains("/"))
        assertTrue(cleanName.endsWith(".html"))
    }

    @Test
    fun `offline game engine handles double jump, dive, sugar bonus, and shield absorption`() {
        val engine = OfflineGameEngine(initialBest = 10)
        // First tap starts run, next two perform double jump
        assertTrue(engine.startOrJump().isEmpty())
        assertEquals(listOf(GameEvent.JUMP), engine.startOrJump())
        engine.step(0.03f)
        assertEquals(listOf(GameEvent.DOUBLE_JUMP), engine.startOrJump())
        assertEquals(2, engine.jumpsUsed)
        assertTrue(engine.startOrJump().isEmpty()) // capped at double jump
        assertEquals(listOf(GameEvent.DIVE), engine.fastDive())

        // Sugar pickup adds +25 bonus and updates best score
        engine.step(0.4f) // settle back to ground
        while (engine.playerY > 0f) engine.step(0.03f)
        engine.pickups.clear()
        engine.pickups.add(GamePickup(xNorm = 0.17f, elevationDp = 18f, kind = PickupKind.SUGAR_DROP))
        val sugarEvents = engine.step(0.016f)
        assertTrue(GameEvent.SUGAR_COLLECTED in sugarEvents)
        assertEquals(25, engine.bonusScore)
        assertTrue(engine.bestScore >= 25)

        // Leaf shield absorbs one obstacle hit without ending the game
        engine.pickups.add(GamePickup(xNorm = 0.17f, elevationDp = 18f, kind = PickupKind.LEAF_SHIELD))
        assertTrue(GameEvent.SHIELD_COLLECTED in engine.step(0.016f))
        assertTrue(engine.hasShield)

        engine.obstacles.clear()
        engine.obstacles.add(GameObstacle(xNorm = 0.17f, widthDp = 28f, heightDp = 32f, bottomElevationDp = 0f, kind = ObstacleKind.SPIKY_BURR))
        val crashEvents = engine.step(0.016f)
        assertTrue(GameEvent.SHIELD_USED in crashEvents)
        assertFalse(engine.hasShield)
        assertFalse(engine.isGameOver)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PipraV3RobolectricTest {

    private lateinit var context: Context

    @Before
    @After
    fun clean() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pipra", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "pipra_policy.json").delete()
        File(context.filesDir, "pipra_blocked_sites.json").delete()
        File(context.filesDir, "pipra_rule_hmac.key").delete()
    }

    @Test
    fun `fresh install defaults to Light theme, Google search, and Adult Protection on`() {
        val store = Store(context)
        assertEquals(AppThemeMode.LIGHT, store.themeMode)
        assertFalse(store.isDarkThemeEffective())
        assertEquals("Google", store.searchEngine)
        assertTrue(store.adultProtectionEnabled)
        assertTrue(store.searchTemplate().startsWith("https://www.google.com/search"))
    }

    @Test
    fun `custom search url validation and formatting works safely`() {
        assertNull(DomainUtils.validateCustomSearchUrl("javascript:alert(1)"))
        assertNull(DomainUtils.validateCustomSearchUrl("file:///sdcard/search"))
        assertNull(DomainUtils.validateCustomSearchUrl("not a url"))
        val valid = DomainUtils.validateCustomSearchUrl("https://kagi.com/search?q=%s")
        assertEquals("https://kagi.com/search?q=%s", valid)
        assertEquals(
            "https://kagi.com/search?q=pipra%20browser",
            DomainUtils.formatSearchUrl(valid!!, "pipra browser")
        )
    }

    @Test
    fun `backup exports and restores themeMode and customSearchUrl`() {
        val store = Store(context)
        store.themeMode = AppThemeMode.DARK
        store.customSearchUrl = "https://search.example.org/?q=%s"
        store.searchEngine = Store.CUSTOM_SEARCH_ENGINE

        val backup = BackupManager(context)
        val json = backup.exportBackupJson()

        store.themeMode = AppThemeMode.LIGHT
        store.customSearchUrl = ""
        store.searchEngine = "Google"

        assertTrue(backup.importBackupJson(json))
        assertEquals(AppThemeMode.DARK, store.themeMode)
        assertEquals("https://search.example.org/?q=%s", store.customSearchUrl)
        assertEquals(Store.CUSTOM_SEARCH_ENGINE, store.searchEngine)
    }

    @Test
    fun `legacy download log migrates into downloadItemsV3 automatically`() {
        val store = Store(context)
        store.addDownload("report.pdf", "https://example.org/report.pdf", "application/pdf")

        val migrated = store.downloadItemsV3()
        assertEquals(1, migrated.size)
        assertEquals("report.pdf", migrated[0].fileName)
        assertEquals(DownloadStatus.COMPLETED, migrated[0].status)
    }

    @Test
    fun `safe zip handler rejects zip slip path traversal`() {
        val zipFile = File(context.cacheDir, "evil_traversal.zip")
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("../../outside.html"))
            zos.write("<h1>hacked</h1>".toByteArray())
            zos.closeEntry()
        }

        var blocked = false
        try {
            SafeZipHandler.safeExtract(context, Uri.fromFile(zipFile), "evil_traversal.zip")
        } catch (e: SecurityException) {
            blocked = true
        } finally {
            zipFile.delete()
        }
        assertTrue(blocked)
    }

    @Test
    fun `safe zip handler extracts nested html site and resolves confined assets`() {
        val zipFile = File(context.cacheDir, "sample_site.zip")
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("index.html"))
            zos.write("<html><link rel='stylesheet' href='css/app.css'><h1>Hello</h1></html>".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("css/app.css"))
            zos.write("body { color: #222; }".toByteArray())
            zos.closeEntry()
        }
        zipFile.writeBytes(baos.toByteArray())

        val extracted = SafeZipHandler.safeExtract(context, Uri.fromFile(zipFile), "sample_site.zip")
        try {
            val entryFile = extracted.entryHtmlFile
            assertNotNull(entryFile)
            assertEquals("index.html", entryFile!!.name)

            val resolver = LocalWebProjectResolver(
                context = context,
                entryUri = Uri.fromFile(entryFile),
                rootDir = extracted.outputDir,
                entryRelativePath = "index.html"
            )
            val cssRes = resolver.openResource("css/app.css")
            assertNotNull(cssRes)
            assertEquals("text/css", cssRes!!.first)
            cssRes.second.close()

            // Traversal escape must be blocked cleanly
            assertNull(resolver.openResource("../../secret.txt"))
        } finally {
            zipFile.delete()
            extracted.outputDir.deleteRecursively()
        }
    }

    @Test
    fun `home html and network error html render light mode and offline game action`() {
        val offlineFile = File.createTempFile("offline_example", ".mht", context.cacheDir)
        try {
            val home = BlockedPageHtml.pipraHomeHtml(
                activeEngine = "Google",
                availableEngines = Store.SEARCH_ENGINES.keys.toList(),
                bookmarks = listOf(Bookmark("https://example.org", "Example", offlineFile.absolutePath, 1234L)),
                isDark = false
            )
            assertTrue(home.contains("Search Google"))
            assertTrue(home.contains("#F4F6F9"))
            assertTrue(home.contains("Offline"))

            val darkHome = BlockedPageHtml.pipraHomeHtml(
                activeEngine = "DuckDuckGo",
                availableEngines = Store.SEARCH_ENGINES.keys.toList(),
                bookmarks = emptyList(),
                isDark = true
            )
            assertTrue(darkHome.contains("Search DuckDuckGo"))
            assertTrue(darkHome.contains("#0B0C0E"))

            val offlinePage = BlockedPageHtml.networkErrorHtml(
                failedUrl = "https://example.org",
                errorDescription = "ERR_INTERNET_DISCONNECTED",
                isOffline = true,
                isDark = false
            )
            assertTrue(offlinePage.contains("You're Offline"))
            assertTrue(offlinePage.contains("pipra://game"))
            assertTrue(offlinePage.contains("Play Ant Dash"))
        } finally {
            offlineFile.delete()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsV3UiRobolectricTest {

    @get:Rule(order = 0)
    val cleanPrefsRule = object : ExternalResource() {
        private fun wipe() {
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            ctx.getSharedPreferences("pipra", Context.MODE_PRIVATE).edit().clear().commit()
            File(ctx.filesDir, "pipra_policy.json").delete()
            File(ctx.filesDir, "pipra_blocked_sites.json").delete()
            File(ctx.filesDir, "pipra_rule_hmac.key").delete()
        }

        override fun before() = wipe()
        override fun after() = wipe()
    }

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<SettingsActivity>()

    @Test
    fun `appearance theme switch and legal docs in about screen work without login`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_hub_list").performScrollToNode(hasTestTag("settings_item_appearance"))
        composeRule.onNodeWithTag("settings_item_appearance").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("appearance_list").performScrollToNode(hasTestTag("theme_mode_dark"))
        composeRule.onNodeWithTag("theme_mode_dark").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("theme_mode_dark").assertIsSelected()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(AppThemeMode.DARK, Store(ctx).themeMode)

        composeRule.onNodeWithTag("settings_back_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_hub_list").performScrollToNode(hasTestTag("settings_item_search_engine"))
        composeRule.onNodeWithTag("settings_item_search_engine").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("search_engine_list").performScrollToNode(hasTestTag("search_engine_footer"))
        composeRule.onNodeWithTag("custom_search_input").performTextReplacement("https://kagi.com/search?q=%s")
        composeRule.onNodeWithTag("search_engine_list").performScrollToNode(hasTestTag("search_engine_footer"))
        composeRule.onNodeWithTag("save_custom_search_button").performClick()
        composeRule.waitForIdle()
        assertEquals(Store.CUSTOM_SEARCH_ENGINE, Store(ctx).searchEngine)
        assertEquals("https://kagi.com/search?q=%s", Store(ctx).customSearchUrl)

        composeRule.onNodeWithTag("settings_back_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_hub_list").performScrollToNode(hasTestTag("settings_item_about"))
        composeRule.onNodeWithTag("settings_item_about").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("about_pipra_list").performScrollToNode(hasTestTag("about_privacy_policy_btn"))
        composeRule.onNodeWithTag("about_privacy_policy_btn").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Pipra Browser Privacy Policy (v3.0)", substring = true).assertIsDisplayed()
    }
}
