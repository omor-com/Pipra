package com.pipra.browser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SecureDnsAndBackupTest {

    private lateinit var context: Context

    @Before
    fun clean() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "pipra_secure_dns.json").delete()
        File(context.filesDir, "pipra_policy.json").delete()
    }

    @Test
    fun `secure dns manager persists kahf guard profile and endpoints`() {
        val manager = SecureDnsManager(context)
        assertEquals(SecureDnsProfile.MEDIUM, manager.getProfile())
        assertEquals("https://medium.kahfguard.com/dns-query", manager.activeEndpoint())

        manager.setProfile(SecureDnsProfile.HIGH)
        assertEquals(SecureDnsProfile.HIGH, manager.getProfile())
        assertEquals("https://high.kahfguard.com/dns-query", manager.activeEndpoint())

        manager.setProfile(SecureDnsProfile.CUSTOM)
        manager.setCustomEndpoint("https://dns.example.com/query")
        assertEquals(SecureDnsProfile.CUSTOM, manager.getProfile())
        assertEquals("https://dns.example.com/query", manager.activeEndpoint())
    }

    @Test
    fun `backup manager exports and restores configuration successfully`() {
        val store = Store(context)
        store.searchEngine = "DuckDuckGo"
        store.toggleBookmark("https://example.org", "Example Org")

        val backupManager = BackupManager(context)
        val json = backupManager.exportBackupJson()
        assertTrue(json.contains("Pipra"))
        assertTrue(json.contains("DuckDuckGo"))

        // Reset and restore
        store.searchEngine = "Google"
        store.removeBookmark("https://example.org")

        val restored = backupManager.importBackupJson(json)
        assertTrue(restored)
        assertEquals("DuckDuckGo", store.searchEngine)
        assertTrue(store.isBookmarked("https://example.org"))
    }
}
