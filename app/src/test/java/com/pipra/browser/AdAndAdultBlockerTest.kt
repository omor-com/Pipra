package com.pipra.browser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AdAndAdultBlockerTest {

    private lateinit var context: Context
    private lateinit var adBlocker: AdBlockerEngine
    private lateinit var adultBlocker: AdultBlockerEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "pipra_adblock_cache.txt").delete()
        File(context.filesDir, "pipra_adult_cache.txt").delete()
        adBlocker = AdBlockerEngine(context)
        adultBlocker = AdultBlockerEngine(context)
    }

    @Test
    fun `ad blocker modes have distinguishable filtering behavior`() {
        val page = "https://news.example.com/article"
        val coreAd = "https://securepubads.g.doubleclick.net/tag/js/gpt.js"
        val telemetry = "https://static.hotjar.com/c/hotjar-123.js"
        val thirdPartyHeuristic = "https://analytics.vendor.io/collect.js"
        val ultimateCrypto = "https://coinhive.com/lib/coinhive.min.js"
        val ultimatePop = "https://cdn.other.net/popunder/script.js"

        // Master OFF blocks nothing
        assertFalse(adBlocker.shouldBlockSubresource(coreAd, page, AdBlockMode.ULTIMATE, enabled = false, emptySet()))

        // MEDIUM: conservative core ads only
        assertTrue(adBlocker.shouldBlockSubresource(coreAd, page, AdBlockMode.MEDIUM, enabled = true, emptySet()))
        assertFalse(adBlocker.shouldBlockSubresource(telemetry, page, AdBlockMode.MEDIUM, enabled = true, emptySet()))
        assertFalse(adBlocker.shouldBlockSubresource(thirdPartyHeuristic, page, AdBlockMode.MEDIUM, enabled = true, emptySet()))
        assertFalse(adBlocker.shouldBlockSubresource(ultimateCrypto, page, AdBlockMode.MEDIUM, enabled = true, emptySet()))

        // HIGH: adds telemetry + third-party prefix heuristics, leaves ULTIMATE-only items alone
        assertTrue(adBlocker.shouldBlockSubresource(coreAd, page, AdBlockMode.HIGH, enabled = true, emptySet()))
        assertTrue(adBlocker.shouldBlockSubresource(telemetry, page, AdBlockMode.HIGH, enabled = true, emptySet()))
        assertTrue(adBlocker.shouldBlockSubresource(thirdPartyHeuristic, page, AdBlockMode.HIGH, enabled = true, emptySet()))
        assertFalse(adBlocker.shouldBlockSubresource(ultimateCrypto, page, AdBlockMode.HIGH, enabled = true, emptySet()))
        assertFalse(adBlocker.shouldBlockSubresource(ultimatePop, page, AdBlockMode.HIGH, enabled = true, emptySet()))

        // ULTIMATE: blocks all tiers + aggressive path heuristics
        assertTrue(adBlocker.shouldBlockSubresource(ultimateCrypto, page, AdBlockMode.ULTIMATE, enabled = true, emptySet()))
        assertTrue(adBlocker.shouldBlockSubresource(ultimatePop, page, AdBlockMode.ULTIMATE, enabled = true, emptySet()))
    }

    @Test
    fun `per-site exceptions disable blocking only on allowlisted site`() {
        val req = "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"
        val exceptions = setOf("example.com")

        assertFalse(adBlocker.shouldBlockSubresource(req, "https://sub.example.com/post", AdBlockMode.ULTIMATE, true, exceptions))
        assertTrue(adBlocker.shouldBlockSubresource(req, "https://notexample.com/post", AdBlockMode.ULTIMATE, true, exceptions))
    }

    @Test
    fun `stripTrackingParams removes tracking tags while keeping functional parameters`() {
        val dirty = "https://shop.example.org/item?id=42&utm_source=newsletter&fbclid=abc123&sort=price"
        val clean = adBlocker.stripTrackingParams(dirty)
        assertTrue(clean.contains("id=42"))
        assertTrue(clean.contains("sort=price"))
        assertFalse(clean.contains("utm_source"))
        assertFalse(clean.contains("fbclid"))
    }

    @Test
    fun `filter parser handles hosts and adblock syntax and rejects html error pages`() {
        val sample = """
            # Comment line
            ! Adblock comment
            0.0.0.0 tracker.badads.net
            127.0.0.1 pixel.example.org # inline comment
            ||banner.adnetwork.io^
            localhost
        """.trimIndent()

        val parsed = adBlocker.parseFilterList(sample)
        assertTrue("tracker.badads.net" in parsed)
        assertTrue("pixel.example.org" in parsed)
        assertTrue("banner.adnetwork.io" in parsed)
        assertFalse("localhost" in parsed)

        val htmlError = "<!DOCTYPE html><html><body>503 Service Unavailable</body></html>"
        assertTrue(adBlocker.parseFilterList(htmlError).isEmpty())
    }

    @Test
    fun `failed filter update preserves existing cached rules`() {
        val cacheFile = File(context.filesDir, "pipra_adblock_cache.txt")
        val initialDomains = (1..30).map { "cached-ad-$it.example.net" }
        cacheFile.writeText(initialDomains.joinToString("\n"))

        assertEquals(30, adBlocker.cachedRuleCount())

        // Non-HTTPS or unreachable update must fail gracefully and keep the 30 cached rules
        val res = adBlocker.updateFilterLists("http://insecure.invalid/list.txt")
        assertFalse(res.success)
        assertEquals(30, adBlocker.cachedRuleCount())
        assertTrue(adBlocker.shouldBlockSubresource("https://cached-ad-5.example.net/ad.js", "https://news.org", AdBlockMode.HIGH, true, emptySet()))
    }

    @Test
    fun `adult protection works offline using baseline and cached rules and respects toggle`() {
        assertTrue(adultBlocker.activeDomainCount() >= 50)
        assertFalse(adultBlocker.isBlockedUrl("https://www.pornhub.com/", enabled = false))
        assertTrue(adultBlocker.isBlockedUrl("https://www.pornhub.com/", enabled = true))
        assertTrue(adultBlocker.isBlockedUrl("https://sub.xvideos.com/video", enabled = true))
        assertFalse(adultBlocker.isBlockedUrl("https://www.wikipedia.org/", enabled = true))
    }
}
