package com.pipra.browser

import android.content.Context
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSessionSettings

/**
 * Lightweight active-tab handle consumed by modular cookie/dev-tools dialogs so they remain
 * decoupled from legacy WebView and work seamlessly on GeckoView sessions.
 */
internal interface BrowserSessionHandle {
    val url: String?
    fun reload()
    fun navigate(url: String)
    fun evaluateJavascript(script: String, callback: ((String?) -> Unit)? = null)
}

/**
 * Process-wide Mozilla GeckoRuntime lifecycle manager and privacy/content-blocking configurator.
 * Normal and :incognito processes each maintain their own isolated GeckoRuntime instance.
 */
object PipraGeckoRuntime {

    @Volatile
    private var instance: GeckoRuntime? = null

    fun getOrCreate(context: Context, store: Store): GeckoRuntime {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val appCtx = context.applicationContext
            val settings = GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(store.javaScriptEnabled)
                .automaticFontSizeAdjustment(true)
                .allowInsecureConnections(resolveInsecurePolicy(store.httpsOnly))
                .contentBlocking(buildContentBlocking(store))
                .build()
            val runtime = GeckoRuntime.create(appCtx, settings)
            instance = runtime
            return runtime
        }
    }

    fun applyStoreSettings(store: Store) {
        val runtime = instance ?: return
        runtime.settings.javaScriptEnabled = store.javaScriptEnabled
        runtime.settings.allowInsecureConnections = resolveInsecurePolicy(store.httpsOnly)
        runtime.settings.contentBlocking.setCookieBehavior(resolveCookieBehavior(store.blockThirdPartyCookies))
        runtime.settings.contentBlocking.setAntiTracking(resolveAntiTracking(store))
    }

    fun buildSessionSettings(
        isPrivate: Boolean,
        desktop: Boolean,
        store: Store,
        desktopUa: String
    ): GeckoSessionSettings {
        return GeckoSessionSettings.Builder()
            .usePrivateMode(isPrivate)
            .useTrackingProtection(store.shieldsEnabled)
            .apply {
                if (desktop) {
                    userAgentOverride(desktopUa)
                    userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                    viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
                } else {
                    userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                    viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                }
            }
            .build()
    }

    private fun resolveInsecurePolicy(httpsOnly: Boolean): Int =
        if (httpsOnly) GeckoRuntimeSettings.HTTPS_ONLY else GeckoRuntimeSettings.ALLOW_ALL

    private fun resolveCookieBehavior(blockThirdParty: Boolean): Int =
        if (blockThirdParty) ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS
        else ContentBlocking.CookieBehavior.ACCEPT_ALL

    private fun resolveAntiTracking(store: Store): Int =
        if (store.shieldsEnabled) ContentBlocking.AntiTracking.STRICT
        else ContentBlocking.AntiTracking.NONE

    private fun buildContentBlocking(store: Store): ContentBlocking.Settings {
        return ContentBlocking.Settings.Builder()
            .antiTracking(resolveAntiTracking(store))
            .cookieBehavior(resolveCookieBehavior(store.blockThirdPartyCookies))
            .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
            .build()
    }
}
