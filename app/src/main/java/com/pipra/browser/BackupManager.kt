package com.pipra.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Handles exporting and restoring Pipra configuration, bookmarks, and blocking rules
 * to/from a local JSON backup archive.
 */
class BackupManager(private val context: Context) {

    private val store = Store(context)
    private val websiteBlocker = WebsiteBlocker(context)

    fun exportBackupJson(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("app", "Pipra")
        root.put("timestamp", System.currentTimeMillis())

        // Preferences
        val prefsObj = JSONObject()
            .put("homepage", store.homepage)
            .put("searchEngine", store.searchEngine)
            .put("desktopByDefault", store.desktopByDefault)
            .put("showTabStrip", store.showTabStrip)
            .put("blockImages", store.blockImages)
            .put("shieldsEnabled", store.shieldsEnabled)
            .put("adBlockMode", store.adBlockMode.key)
            .put("adultProtectionEnabled", store.adultProtectionEnabled)
            .put("httpsOnly", store.httpsOnly)
            .put("blockThirdPartyCookies", store.blockThirdPartyCookies)
            .put("blockPopups", store.blockPopups)
            .put("stripTrackingParams", store.stripTrackingParams)
            .put("javaScriptEnabled", store.javaScriptEnabled)
            .put("askBeforeDownload", store.askBeforeDownload)

        root.put("preferences", prefsObj)

        // Bookmarks
        val bookmarksArr = JSONArray()
        for (b in store.bookmarks()) {
            bookmarksArr.put(JSONObject().put("url", b.url).put("title", b.title))
        }
        root.put("bookmarks", bookmarksArr)

        // Website blocking rules
        val rulesArr = JSONArray()
        for (r in websiteBlocker.rules()) {
            rulesArr.put(
                JSONObject()
                    .put("host", r.host)
                    .put("includeSubdomains", r.includeSubdomains)
                    .put("lockDays", r.lockDays)
                    .put("createdAtMillis", r.createdAtMillis)
                    .put("lockUntilMillis", r.lockUntilMillis)
            )
        }
        root.put("websiteBlockingRules", rulesArr)

        // Adblock exceptions
        val exceptionsArr = JSONArray()
        for (ex in store.adBlockExceptions()) {
            exceptionsArr.put(ex)
        }
        root.put("adBlockExceptions", exceptionsArr)

        return root.toString(2)
    }

    fun importBackupJson(jsonString: String): Boolean {
        return try {
            val root = JSONObject(jsonString)
            if (root.optString("app") != "Pipra") return false

            val prefs = root.optJSONObject("preferences")
            if (prefs != null) {
                store.homepage = prefs.optString("homepage", Store.DEFAULT_HOMEPAGE)
                store.searchEngine = prefs.optString("searchEngine", "Google")
                store.desktopByDefault = prefs.optBoolean("desktopByDefault", false)
                store.showTabStrip = prefs.optBoolean("showTabStrip", true)
                store.blockImages = prefs.optBoolean("blockImages", false)
                store.shieldsEnabled = prefs.optBoolean("shieldsEnabled", true)
                store.adBlockMode = AdBlockMode.fromKey(prefs.optString("adblock_mode", prefs.optString("adBlockMode", "high")))
                store.adultProtectionEnabled = prefs.optBoolean("adultProtectionEnabled", false)
                store.httpsOnly = prefs.optBoolean("httpsOnly", true)
                store.blockThirdPartyCookies = prefs.optBoolean("blockThirdPartyCookies", true)
                store.blockPopups = prefs.optBoolean("blockPopups", true)
                store.stripTrackingParams = prefs.optBoolean("stripTrackingParams", true)
                store.javaScriptEnabled = prefs.optBoolean("javaScriptEnabled", true)
                store.askBeforeDownload = prefs.optBoolean("askBeforeDownload", true)
            }

            val bookmarks = root.optJSONArray("bookmarks")
            if (bookmarks != null) {
                for (i in 0 until bookmarks.length()) {
                    val o = bookmarks.optJSONObject(i) ?: continue
                    val url = o.optString("url", "")
                    val title = o.optString("title", "")
                    if (url.isNotBlank() && !store.isBookmarked(url)) {
                        store.toggleBookmark(url, title)
                    }
                }
            }

            val rules = root.optJSONArray("websiteBlockingRules")
            if (rules != null) {
                for (i in 0 until rules.length()) {
                    val o = rules.optJSONObject(i) ?: continue
                    val host = o.optString("host", "")
                    val subs = o.optBoolean("includeSubdomains", false)
                    val days = o.optInt("lockDays", 30).coerceIn(7, 365)
                    if (host.isNotBlank()) {
                        websiteBlocker.addRule(host, subs, days)
                    }
                }
            }

            val exceptions = root.optJSONArray("adBlockExceptions")
            if (exceptions != null) {
                for (i in 0 until exceptions.length()) {
                    val ex = exceptions.optString(i, "")
                    if (ex.isNotBlank()) {
                        store.addAdBlockException(ex)
                    }
                }
            }

            true
        } catch (_: Exception) {
            false
        }
    }
}
