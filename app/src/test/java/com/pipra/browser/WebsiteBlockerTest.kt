package com.pipra.browser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WebsiteBlockerTest {

    private lateinit var context: Context
    private lateinit var blocker: WebsiteBlocker

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "pipra_blocked_sites.json").delete()
        File(context.filesDir, "pipra_rule_hmac.key").delete()
        blocker = WebsiteBlocker(context)
    }

    @Test
    fun `lock duration enforces 7 to 365 days strictly in engine`() {
        val now = 1_700_000_000_000L
        assertTrue(blocker.addRule("example.com", false, 6, now) is RuleActionResult.Error)
        assertTrue(blocker.addRule("example.com", false, 0, now) is RuleActionResult.Error)
        assertTrue(blocker.addRule("example.com", false, -10, now) is RuleActionResult.Error)
        assertTrue(blocker.addRule("example.com", false, 366, now) is RuleActionResult.Error)

        val okMin = blocker.addRule("min7.example.com", false, 7, now)
        val okMax = blocker.addRule("max365.example.com", true, 365, now)
        assertTrue(okMin is RuleActionResult.Success)
        assertTrue(okMax is RuleActionResult.Success)
    }

    @Test
    fun `active lock blocks edits and deletion and persists exact deadline across restarts`() {
        val t0 = 1_700_000_000_000L
        val added = blocker.addRule("https://M.YouTube.com/shorts", includeSubdomains = false, lockDays = 30, nowMillis = t0)
        assertTrue(added is RuleActionResult.Success)
        val rule = (added as RuleActionResult.Success).rule!!
        assertEquals("m.youtube.com", rule.host)
        assertEquals(t0 + 30L * WebsiteBlocker.DAY_MILLIS, rule.lockUntilMillis)

        // Attempt edit and delete mid-lock (day 10)
        val day10 = t0 + 10L * WebsiteBlocker.DAY_MILLIS
        assertTrue(blocker.updateRule(rule.id, "other.com", false, 7, day10) is RuleActionResult.Error)
        assertTrue(blocker.deleteRule(rule.id, day10) is RuleActionResult.Error)
        assertEquals(1, blocker.rules().size)

        // Simulate app restart / second process reading from disk
        val restartedBlocker = WebsiteBlocker(context)
        val reloaded = restartedBlocker.rules()
        assertEquals(1, reloaded.size)
        assertEquals(rule.lockUntilMillis, reloaded.first().lockUntilMillis)
        assertTrue(reloaded.first().isLocked(day10))

        // Day 30+: lock expires -> rule still blocks until removed, and edit/delete now succeed
        val day31 = t0 + 31L * WebsiteBlocker.DAY_MILLIS
        assertFalse(reloaded.first().isLocked(day31))
        assertNotNull(reloaded.first().let { restartedBlocker.findMatchingRuleForUrl("https://m.youtube.com/watch?v=1") })

        val editAfterExpiry = restartedBlocker.updateRule(rule.id, "youtube.com", true, 14, day31)
        assertTrue(editAfterExpiry is RuleActionResult.Success)
        assertTrue(restartedBlocker.rules().first().isLocked(day31 + 1000L))

        val day50 = day31 + 20L * WebsiteBlocker.DAY_MILLIS
        assertTrue(restartedBlocker.deleteRule(rule.id, day50) is RuleActionResult.Success)
        assertTrue(restartedBlocker.rules().isEmpty())
    }

    @Test
    fun `supports numerous independent rules and prevents subdomain partial false positives`() {
        val t0 = 1_700_000_000_000L
        assertTrue(blocker.addRule("m.youtube.com", includeSubdomains = false, lockDays = 14, nowMillis = t0) is RuleActionResult.Success)
        assertTrue(blocker.addRule("example.com", includeSubdomains = true, lockDays = 90, nowMillis = t0) is RuleActionResult.Success)
        for (i in 1..25) {
            assertTrue(blocker.addRule("site$i.org", includeSubdomains = (i % 2 == 0), lockDays = 7 + i, nowMillis = t0) is RuleActionResult.Success)
        }
        assertEquals(27, blocker.rules().size)

        // Exact host m.youtube.com blocks m.youtube.com, does NOT block www.youtube.com
        assertNotNull(blocker.findMatchingRuleForUrl("https://m.youtube.com/watch"))
        assertNull(blocker.findMatchingRuleForUrl("https://www.youtube.com/watch"))

        // Subdomain rule example.com blocks sub.example.com, never notexample.com
        assertNotNull(blocker.findMatchingRuleForUrl("https://sub.example.com/a"))
        assertNull(blocker.findMatchingRuleForUrl("https://notexample.com/a"))
    }

    @Test
    fun `tampering with persisted json sets integrity warning`() {
        val t0 = 1_700_000_000_000L
        blocker.addRule("reddit.com", includeSubdomains = true, lockDays = 30, nowMillis = t0)

        val file = File(context.filesDir, "pipra_blocked_sites.json")
        val tampered = file.readText().replace("reddit.com", "safe.com")
        file.writeText(tampered)

        val fresh = WebsiteBlocker(context)
        fresh.rules()
        assertTrue(fresh.hasIntegrityWarning())
    }
}
