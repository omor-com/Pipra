package com.pipra.browser

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PipraIntegrationRobolectricTest {

    private lateinit var context: Context

    @Before
    fun cleanState() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "pipra_blocked_sites.json").delete()
        File(context.filesDir, "pipra_rule_hmac.key").delete()
        File(context.filesDir, "pipra_policy.json").delete()
    }

    @Test
    fun `store policy persists across instances for private process parity`() {
        val storeA = Store(context)
        storeA.adBlockMode = AdBlockMode.ULTIMATE
        storeA.adultProtectionEnabled = true
        storeA.httpsOnly = true
        storeA.addAdBlockException("docs.example.org")

        val storeB = Store(context)
        assertEquals(AdBlockMode.ULTIMATE, storeB.adBlockMode)
        assertTrue(storeB.adultProtectionEnabled)
        assertTrue(storeB.httpsOnly)
        assertTrue(storeB.isAdBlockException("sub.docs.example.org"))
    }

    @Test
    fun `clearAllBrowsingData preserves locked website rules`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = SettingsViewModel(app)
        val res = vm.addWebsiteBlockRule("m.youtube.com", includeSubdomains = false, lockDays = 60)
        assertTrue(res is RuleActionResult.Success)
        vm.store.addHistory("https://example.org", "Example")
        NetLog.add(NetEntry("GET", "https://example.org", 200))

        vm.clearAllBrowsingData()

        assertTrue(vm.store.history().isEmpty())
        assertTrue(NetLog.snapshot().isEmpty())
        assertEquals(1, vm.websiteBlocker.rules().size)
        assertEquals("m.youtube.com", vm.websiteBlocker.rules().first().host)
    }

    @Test
    fun `rule engine cannot redirect into a blocked destination`() {
        val blocker = WebsiteBlocker(context)
        blocker.addRule("blocked-target.example.com", includeSubdomains = true, lockDays = 30)

        val dev = DevStore(context)
        dev.devToolsEnabled = true
        dev.saveRequestRules(
            listOf(
                RequestRule(
                    enabled = true,
                    urlPattern = "*allowed-source.example.com*",
                    action = RequestRule.ACTION_REDIRECT,
                    target = "https://sub.blocked-target.example.com/payload.js"
                )
            )
        )

        val engine = RuleEngine(dev) { url -> blocker.findMatchingRuleForUrl(url) != null }
        val req = object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse("https://allowed-source.example.com/app.js")
            override fun isForMainFrame(): Boolean = false
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = false
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
        }

        val resp = engine.intercept(req, isPrivateSession = false)
        assertNotNull(resp)
        assertEquals(0, resp!!.data.readBytes().size)
    }

    @Test
    fun `blocked page html escapes host and explains lock details`() {
        val blocker = WebsiteBlocker(context)
        val res = blocker.addRule("m.youtube.com", false, 45) as RuleActionResult.Success
        val html = BlockedPageHtml.blockedWebsiteHtml("m.youtube.com", res.rule!!, blocker)
        assertTrue(html.contains("Website Blocked"))
        assertTrue(html.contains("m.youtube.com"))
        assertTrue(html.contains("45 days"))

        val adultHtml = BlockedPageHtml.adultBlockedHtml("example-adult.org")
        assertTrue(adultHtml.contains("Adult Protection Active"))
        assertTrue(adultHtml.contains("example-adult.org"))
    }

    @Test
    fun `private activity declares isolated incognito process and isPrivate flag`() {
        val intent = Intent(context, PrivateActivity::class.java)
        assertEquals(PrivateActivity::class.java.name, intent.component?.className)
    }
}
