package com.pipra.browser

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A visited page kept in history. */
data class HistoryEntry(val url: String, val title: String, val time: Long)

/** A saved bookmark. */
data class Bookmark(val url: String, val title: String)

/** A recorded download initiated by the user. */
data class DownloadRecord(
    val fileName: String,
    val url: String,
    val mimeType: String,
    val time: Long
)

/** Ad Blocker protection tiers. */
enum class AdBlockMode(val key: String, val label: String, val summary: String) {
    MEDIUM(
        key = "medium",
        label = "Medium",
        summary = "Conservative filtering for common ad networks and major third-party trackers."
    ),
    HIGH(
        key = "high",
        label = "High",
        summary = "Broader ad, analytics, telemetry, fingerprinting, and path-pattern blocking."
    ),
    ULTIMATE(
        key = "ultimate",
        label = "Ultimate",
        summary = "Strongest filtering: expanded domain lists, tracking parameter stripping, and cosmetic CSS hiding."
    );

    companion object {
        fun fromKey(key: String?): AdBlockMode =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: HIGH
    }
}

/**
 * Persistence layer for Pipra.
 * - Normal browser history & bookmarks stay in private SharedPreferences.
 * - Cross-process privacy/blocking configuration (needed by both main process and
 *   :incognito PrivateActivity) is stored atomically in filesDir/pipra_policy.json
 *   so private windows always honor the latest ad-block, website-block, and
 *   adult-protection state.
 */
class Store(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pipra", Context.MODE_PRIVATE)

    private val policyFile = File(context.filesDir, "pipra_policy.json")

    companion object {
        private const val KEY_HISTORY = "history"
        private const val KEY_BOOKMARKS = "bookmarks"
        private const val KEY_DOWNLOADS = "downloads"
        private const val HISTORY_CAP = 1000
        private const val DOWNLOADS_CAP = 200

        const val KEY_HOMEPAGE = "homepage"
        const val KEY_SEARCH = "search_engine"
        const val KEY_JS = "javascript"
        const val KEY_DESKTOP_DEFAULT = "desktop_default"
        const val KEY_BLOCK_IMAGES = "block_images"
        const val KEY_SHIELDS = "shields"
        const val KEY_HTTPS_ONLY = "https_only"
        const val KEY_BLOCK_3P_COOKIES = "block_3p_cookies"
        const val KEY_BLOCK_POPUPS = "block_popups"
        const val KEY_ASK_BEFORE_DOWNLOAD = "ask_before_download"
        const val KEY_SHOW_TAB_STRIP = "show_tab_strip"

        const val DEFAULT_HOMEPAGE = "pipra://home"

        val SEARCH_ENGINES = linkedMapOf(
            "DuckDuckGo" to "https://duckduckgo.com/?q=",
            "Brave" to "https://search.brave.com/search?q=",
            "Startpage" to "https://www.startpage.com/sp/search?query=",
            "Google" to "https://www.google.com/search?q=",
            "Bing" to "https://www.bing.com/search?q="
        )
    }

    // ---- Cross-process policy state (read by both normal & :incognito) -----

    @Synchronized
    private fun readPolicyJson(): JSONObject {
        if (!policyFile.exists()) return JSONObject()
        return try {
            JSONObject(policyFile.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            JSONObject()
        }
    }

    @Synchronized
    private fun updatePolicyJson(block: (JSONObject) -> Unit) {
        val obj = readPolicyJson()
        block(obj)
        AtomicFiles.writeText(policyFile, obj.toString(2))
    }

    var homepage: String
        get() = readPolicyJson().optString(KEY_HOMEPAGE, "").ifBlank {
            prefs.getString(KEY_HOMEPAGE, DEFAULT_HOMEPAGE) ?: DEFAULT_HOMEPAGE
        }
        set(v) {
            val clean = v.trim().ifBlank { DEFAULT_HOMEPAGE }
            prefs.edit().putString(KEY_HOMEPAGE, clean).apply()
            updatePolicyJson { it.put(KEY_HOMEPAGE, clean) }
        }

    var searchEngine: String
        get() = readPolicyJson().optString(KEY_SEARCH, "").ifBlank {
            prefs.getString(KEY_SEARCH, "DuckDuckGo") ?: "DuckDuckGo"
        }
        set(v) {
            val clean = if (v in SEARCH_ENGINES) v else "DuckDuckGo"
            prefs.edit().putString(KEY_SEARCH, clean).apply()
            updatePolicyJson { it.put(KEY_SEARCH, clean) }
        }

    fun searchTemplate(): String =
        SEARCH_ENGINES[searchEngine] ?: SEARCH_ENGINES["DuckDuckGo"]!!

    var javaScriptEnabled: Boolean
        get() = readPolicyBool(KEY_JS, true)
        set(v) = writePolicyBool(KEY_JS, v)

    var desktopByDefault: Boolean
        get() = readPolicyBool(KEY_DESKTOP_DEFAULT, false)
        set(v) = writePolicyBool(KEY_DESKTOP_DEFAULT, v)

    var blockImages: Boolean
        get() = readPolicyBool(KEY_BLOCK_IMAGES, false)
        set(v) = writePolicyBool(KEY_BLOCK_IMAGES, v)

    var shieldsEnabled: Boolean
        get() = readPolicyBool(KEY_SHIELDS, true)
        set(v) = writePolicyBool(KEY_SHIELDS, v)

    var adBlockMode: AdBlockMode
        get() = AdBlockMode.fromKey(readPolicyJson().optString("adblock_mode", AdBlockMode.HIGH.key))
        set(v) = updatePolicyJson { it.put("adblock_mode", v.key) }

    var adBlockLastUpdatedMillis: Long
        get() = readPolicyJson().optLong("adblock_last_updated", 0L)
        set(v) = updatePolicyJson { it.put("adblock_last_updated", v) }

    var adBlockTotalBlocked: Long
        get() = readPolicyJson().optLong("adblock_total_blocked", 0L)
        set(v) = updatePolicyJson { it.put("adblock_total_blocked", v) }

    fun incrementAdBlocked(delta: Long = 1L) {
        if (delta <= 0L) return
        updatePolicyJson {
            val cur = it.optLong("adblock_total_blocked", 0L)
            it.put("adblock_total_blocked", cur + delta)
        }
    }

    var adBlockListUrl: String
        get() = readPolicyJson().optString("adblock_list_url", AdBlockerEngine.DEFAULT_FILTER_URL)
            .ifBlank { AdBlockerEngine.DEFAULT_FILTER_URL }
        set(v) = updatePolicyJson { it.put("adblock_list_url", v.trim()) }

    fun adBlockExceptions(): Set<String> {
        val arr = readPolicyJson().optJSONArray("adblock_exceptions") ?: return emptySet()
        val out = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val h = arr.optString(i, "").trim()
            if (h.isNotEmpty()) out.add(h)
        }
        return out
    }

    fun isAdBlockException(host: String): Boolean {
        val norm = DomainUtils.normalizeHost(host) ?: return false
        return adBlockExceptions().any { DomainUtils.matchesHost(norm, it, includeSubdomains = true) }
    }

    fun addAdBlockException(hostInput: String): Boolean {
        val norm = DomainUtils.normalizeHost(hostInput) ?: return false
        val current = adBlockExceptions().toMutableSet()
        val added = current.add(norm)
        if (added) saveAdBlockExceptions(current)
        return added
    }

    fun removeAdBlockException(host: String) {
        val current = adBlockExceptions().toMutableSet()
        if (current.remove(host)) saveAdBlockExceptions(current)
    }

    private fun saveAdBlockExceptions(hosts: Set<String>) {
        val arr = JSONArray()
        hosts.sorted().forEach { arr.put(it) }
        updatePolicyJson { it.put("adblock_exceptions", arr) }
    }

    var adultProtectionEnabled: Boolean
        get() = readPolicyBool("adult_protection", false)
        set(v) = writePolicyBool("adult_protection", v)

    var adultLastUpdatedMillis: Long
        get() = readPolicyJson().optLong("adult_last_updated", 0L)
        set(v) = updatePolicyJson { it.put("adult_last_updated", v) }

    var adultListUrl: String
        get() = readPolicyJson().optString("adult_list_url", AdultBlockerEngine.DEFAULT_ADULT_LIST_URL)
            .ifBlank { AdultBlockerEngine.DEFAULT_ADULT_LIST_URL }
        set(v) = updatePolicyJson { it.put("adult_list_url", v.trim()) }

    var stripTrackingParams: Boolean
        get() = readPolicyBool("strip_tracking_params", true)
        set(v) = writePolicyBool("strip_tracking_params", v)

    var httpsOnly: Boolean
        get() = readPolicyBool(KEY_HTTPS_ONLY, true)
        set(v) = writePolicyBool(KEY_HTTPS_ONLY, v)

    var blockThirdPartyCookies: Boolean
        get() = readPolicyBool(KEY_BLOCK_3P_COOKIES, true)
        set(v) = writePolicyBool(KEY_BLOCK_3P_COOKIES, v)

    var blockPopups: Boolean
        get() = readPolicyBool(KEY_BLOCK_POPUPS, true)
        set(v) = writePolicyBool(KEY_BLOCK_POPUPS, v)

    var askBeforeDownload: Boolean
        get() = readPolicyBool(KEY_ASK_BEFORE_DOWNLOAD, true)
        set(v) = writePolicyBool(KEY_ASK_BEFORE_DOWNLOAD, v)

    var showTabStrip: Boolean
        get() = readPolicyBool(KEY_SHOW_TAB_STRIP, true)
        set(v) = writePolicyBool(KEY_SHOW_TAB_STRIP, v)

    private fun readPolicyBool(key: String, default: Boolean): Boolean {
        val p = readPolicyJson()
        if (p.has(key)) return p.optBoolean(key, default)
        return prefs.getBoolean(key, default)
    }

    private fun writePolicyBool(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        updatePolicyJson { it.put(key, value) }
    }

    // ---- History -----------------------------------------------------------

    fun addHistory(url: String, title: String) {
        if (url.isBlank() || url.startsWith("data:") || url.startsWith("about:") || url.startsWith("pipra://")) return
        val list = history().toMutableList()
        if (list.isNotEmpty() && list.first().url == url) {
            list[0] = HistoryEntry(url, title, System.currentTimeMillis())
        } else {
            list.add(0, HistoryEntry(url, title, System.currentTimeMillis()))
        }
        while (list.size > HISTORY_CAP) list.removeAt(list.size - 1)
        saveHistory(list)
    }

    fun history(): List<HistoryEntry> {
        val arr = readArray(KEY_HISTORY)
        val out = ArrayList<HistoryEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(HistoryEntry(o.optString("url"), o.optString("title"), o.optLong("time")))
        }
        return out
    }

    fun clearHistory() = prefs.edit().remove(KEY_HISTORY).apply()

    private fun saveHistory(list: List<HistoryEntry>) {
        val arr = JSONArray()
        for (h in list) {
            arr.put(JSONObject().put("url", h.url).put("title", h.title).put("time", h.time))
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    // ---- Bookmarks ---------------------------------------------------------

    fun bookmarks(): List<Bookmark> {
        val arr = readArray(KEY_BOOKMARKS)
        val out = ArrayList<Bookmark>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Bookmark(o.optString("url"), o.optString("title")))
        }
        return out
    }

    fun isBookmarked(url: String): Boolean = bookmarks().any { it.url == url }

    fun toggleBookmark(url: String, title: String): Boolean {
        if (url.isBlank() || !url.startsWith("http")) return false
        val list = bookmarks().toMutableList()
        val existing = list.indexOfFirst { it.url == url }
        val nowBookmarked: Boolean
        if (existing >= 0) {
            list.removeAt(existing); nowBookmarked = false
        } else {
            list.add(0, Bookmark(url, title.ifBlank { url })); nowBookmarked = true
        }
        saveBookmarks(list)
        return nowBookmarked
    }

    fun removeBookmark(url: String) {
        val list = bookmarks().toMutableList()
        list.removeAll { it.url == url }
        saveBookmarks(list)
    }

    private fun saveBookmarks(list: List<Bookmark>) {
        val arr = JSONArray()
        for (b in list) arr.put(JSONObject().put("url", b.url).put("title", b.title))
        prefs.edit().putString(KEY_BOOKMARKS, arr.toString()).apply()
    }

    // ---- Downloads log -----------------------------------------------------

    fun addDownload(fileName: String, url: String, mimeType: String) {
        val list = downloads().toMutableList()
        list.add(0, DownloadRecord(fileName, url, mimeType, System.currentTimeMillis()))
        while (list.size > DOWNLOADS_CAP) list.removeAt(list.size - 1)
        val arr = JSONArray()
        for (d in list) {
            arr.put(
                JSONObject()
                    .put("fileName", d.fileName)
                    .put("url", d.url)
                    .put("mimeType", d.mimeType)
                    .put("time", d.time)
            )
        }
        prefs.edit().putString(KEY_DOWNLOADS, arr.toString()).apply()
    }

    fun downloads(): List<DownloadRecord> {
        val arr = readArray(KEY_DOWNLOADS)
        val out = ArrayList<DownloadRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                DownloadRecord(
                    o.optString("fileName"),
                    o.optString("url"),
                    o.optString("mimeType"),
                    o.optLong("time")
                )
            )
        }
        return out
    }

    fun clearDownloadsLog() = prefs.edit().remove(KEY_DOWNLOADS).apply()

    // ---- helpers -----------------------------------------------------------

    private fun readArray(key: String): JSONArray =
        try { JSONArray(prefs.getString(key, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
}

/** Atomic file writer so concurrent reads (e.g. :incognito process) never observe half-written JSON/rules. */
object AtomicFiles {
    fun writeText(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp.${Process.pidCompat()}")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }

    private object Process {
        fun pidCompat(): Int = android.os.Process.myPid()
    }
}
