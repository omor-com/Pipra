package com.pipra.browser

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A visited page kept in history. */
data class HistoryEntry(val url: String, val title: String, val time: Long)

/** A saved bookmark, optionally linked to an offline saved snapshot. */
data class Bookmark(
    val url: String,
    val title: String,
    val offlineArchivePath: String = "",
    val offlineSavedAt: Long = 0L
) {
    val hasOfflineCopy: Boolean
        get() = offlineArchivePath.isNotBlank() && File(offlineArchivePath).exists()
}

/** Legacy download log entry preserved for V1/V2 migration and compatibility. */
data class DownloadRecord(
    val fileName: String,
    val url: String,
    val mimeType: String,
    val time: Long
)

/** Download lifecycle states for V3 Download Manager. */
enum class DownloadStatus(val label: String) {
    QUEUED("Queued"),
    DOWNLOADING("Downloading"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELED("Canceled");

    companion object {
        fun fromName(raw: String?): DownloadStatus =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: FAILED
    }
}

/** Persistent download item used by PipraDownloadManager and the Download & File Center. */
data class DownloadItem(
    val id: String,
    val fileName: String,
    val url: String,
    val mimeType: String,
    val totalBytes: Long = -1L,
    val downloadedBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val localPath: String = "",
    val supportsRange: Boolean = false,
    val eTag: String = "",
    val lastModified: String = "",
    val errorMessage: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0L
)

/** Theme options: Light is mandatory V3 default. */
enum class AppThemeMode(val key: String, val label: String) {
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    SYSTEM("system", "System default");

    companion object {
        fun fromKey(key: String?): AppThemeMode =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: LIGHT
    }
}

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
 * Persistence layer for Pipra V3.
 * - Normal browser history, bookmarks (with optional offline snapshots), and downloads stay in SharedPreferences.
 * - Cross-process privacy/blocking/theme configuration (needed by both main process and
 *   :incognito PrivateActivity) is stored atomically in filesDir/pipra_policy.json.
 */
class Store(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pipra", Context.MODE_PRIVATE)

    private val policyFile = File(context.filesDir, "pipra_policy.json")

    companion object {
        private const val KEY_HISTORY = "history"
        private const val KEY_BOOKMARKS = "bookmarks"
        private const val KEY_DOWNLOADS = "downloads"
        private const val KEY_DOWNLOADS_V3 = "downloads_v3"
        private const val HISTORY_CAP = 1000
        private const val DOWNLOADS_CAP = 250

        const val KEY_HOMEPAGE = "homepage"
        const val KEY_SEARCH = "search_engine"
        const val KEY_CUSTOM_SEARCH_URL = "custom_search_url"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_GAME_BEST_SCORE = "game_best_score"
        const val KEY_JS = "javascript"
        const val KEY_DESKTOP_DEFAULT = "desktop_default"
        const val KEY_BLOCK_IMAGES = "block_images"
        const val KEY_SHIELDS = "shields"
        const val KEY_HTTPS_ONLY = "https_only"
        const val KEY_BLOCK_3P_COOKIES = "block_3p_cookies"
        const val KEY_BLOCK_POPUPS = "block_popups"
        const val KEY_ASK_BEFORE_DOWNLOAD = "ask_before_download"
        const val KEY_SHOW_TAB_STRIP = "show_tab_strip"
        const val KEY_BOTTOM_ADDRESS_BAR = "bottom_address_bar"
        const val KEY_SCROLL_HIDE_TOOLBAR = "scroll_hide_toolbar"
        const val KEY_READER_THEME = "reader_theme"
        const val KEY_READER_FONT_SIZE = "reader_font_size"
        const val KEY_GAME_SOUND = "game_sound_enabled"
        const val KEY_GAME_SKIN = "game_skin_key"

        const val DEFAULT_HOMEPAGE = "pipra://home"
        const val DEFAULT_SEARCH_ENGINE = "Google"
        const val CUSTOM_SEARCH_ENGINE = "Custom"

        val SEARCH_ENGINES = linkedMapOf(
            "Google" to "https://www.google.com/search?q=",
            "DuckDuckGo" to "https://duckduckgo.com/?q=",
            "Bing" to "https://www.bing.com/search?q=",
            "Brave Search" to "https://search.brave.com/search?q=",
            "Startpage" to "https://www.startpage.com/sp/search?query=",
            "Yahoo" to "https://search.yahoo.com/search?p=",
            "Ecosia" to "https://www.ecosia.org/search?q=",
            "Qwant" to "https://www.qwant.com/?q="
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

    var themeMode: AppThemeMode
        get() {
            val raw = readPolicyJson().optString(KEY_THEME_MODE, "").ifBlank {
                prefs.getString(KEY_THEME_MODE, AppThemeMode.LIGHT.key) ?: AppThemeMode.LIGHT.key
            }
            return AppThemeMode.fromKey(raw)
        }
        set(v) {
            prefs.edit().putString(KEY_THEME_MODE, v.key).apply()
            updatePolicyJson { it.put(KEY_THEME_MODE, v.key) }
        }

    fun isDarkThemeEffective(): Boolean = when (themeMode) {
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
        AppThemeMode.SYSTEM -> {
            val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            nightMask == Configuration.UI_MODE_NIGHT_YES
        }
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

    var customSearchUrl: String
        get() = readPolicyJson().optString(KEY_CUSTOM_SEARCH_URL, "").ifBlank {
            prefs.getString(KEY_CUSTOM_SEARCH_URL, "") ?: ""
        }
        set(v) {
            val valid = DomainUtils.validateCustomSearchUrl(v) ?: ""
            prefs.edit().putString(KEY_CUSTOM_SEARCH_URL, valid).apply()
            updatePolicyJson { it.put(KEY_CUSTOM_SEARCH_URL, valid) }
        }

    var searchEngine: String
        get() {
            val raw = readPolicyJson().optString(KEY_SEARCH, "").ifBlank {
                prefs.getString(KEY_SEARCH, DEFAULT_SEARCH_ENGINE) ?: DEFAULT_SEARCH_ENGINE
            }
            val normalized = if (raw == "Brave") "Brave Search" else raw
            if (normalized == CUSTOM_SEARCH_ENGINE && customSearchUrl.isNotBlank()) return CUSTOM_SEARCH_ENGINE
            return if (normalized in SEARCH_ENGINES) normalized else DEFAULT_SEARCH_ENGINE
        }
        set(v) {
            val normalized = if (v == "Brave") "Brave Search" else v
            val clean = when {
                normalized == CUSTOM_SEARCH_ENGINE && customSearchUrl.isNotBlank() -> CUSTOM_SEARCH_ENGINE
                normalized in SEARCH_ENGINES -> normalized
                else -> DEFAULT_SEARCH_ENGINE
            }
            prefs.edit().putString(KEY_SEARCH, clean).apply()
            updatePolicyJson { it.put(KEY_SEARCH, clean) }
        }

    fun availableSearchEngines(): List<String> {
        val list = SEARCH_ENGINES.keys.toMutableList()
        if (customSearchUrl.isNotBlank()) list.add(CUSTOM_SEARCH_ENGINE)
        return list
    }

    fun searchTemplate(): String {
        if (searchEngine == CUSTOM_SEARCH_ENGINE && customSearchUrl.isNotBlank()) {
            return customSearchUrl
        }
        return SEARCH_ENGINES[searchEngine] ?: SEARCH_ENGINES[DEFAULT_SEARCH_ENGINE]!!
    }

    var bestGameScore: Int
        get() = prefs.getInt(KEY_GAME_BEST_SCORE, 0)
        set(v) {
            if (v > bestGameScore) {
                prefs.edit().putInt(KEY_GAME_BEST_SCORE, v).apply()
            }
        }

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
        get() = readPolicyBool("adult_protection", true)
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

    var bottomAddressBar: Boolean
        get() = readPolicyBool(KEY_BOTTOM_ADDRESS_BAR, false)
        set(v) = writePolicyBool(KEY_BOTTOM_ADDRESS_BAR, v)

    var scrollToHideToolbar: Boolean
        get() = readPolicyBool(KEY_SCROLL_HIDE_TOOLBAR, false)
        set(v) = writePolicyBool(KEY_SCROLL_HIDE_TOOLBAR, v)

    var readerTheme: String
        get() {
            val raw = readPolicyJson().optString(KEY_READER_THEME, "sepia")
            return if (raw in setOf("light", "sepia", "dark")) raw else "sepia"
        }
        set(v) {
            val clean = if (v in setOf("light", "sepia", "dark")) v else "sepia"
            updatePolicyJson { it.put(KEY_READER_THEME, clean) }
        }

    var readerFontSizeSp: Int
        get() = readPolicyJson().optInt(KEY_READER_FONT_SIZE, 18).coerceIn(14, 28)
        set(v) = updatePolicyJson { it.put(KEY_READER_FONT_SIZE, v.coerceIn(14, 28)) }

    var gameSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_GAME_SOUND, true)
        set(v) = prefs.edit().putBoolean(KEY_GAME_SOUND, v).apply()

    var gameSkinKey: String
        get() = prefs.getString(KEY_GAME_SKIN, "classic") ?: "classic"
        set(v) = prefs.edit().putString(KEY_GAME_SKIN, v).apply()

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

    // ---- Bookmarks & Offline Saved Pages -----------------------------------

    fun bookmarks(): List<Bookmark> {
        val arr = readArray(KEY_BOOKMARKS)
        val out = ArrayList<Bookmark>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Bookmark(
                    url = o.optString("url"),
                    title = o.optString("title"),
                    offlineArchivePath = o.optString("offlineArchivePath", ""),
                    offlineSavedAt = o.optLong("offlineSavedAt", 0L)
                )
            )
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
            val removed = list.removeAt(existing)
            if (removed.offlineArchivePath.isNotBlank()) {
                try { File(removed.offlineArchivePath).delete() } catch (_: Exception) {}
            }
            nowBookmarked = false
        } else {
            list.add(0, Bookmark(url, title.ifBlank { url }))
            nowBookmarked = true
        }
        saveBookmarks(list)
        return nowBookmarked
    }

    fun saveOfflineBookmark(url: String, title: String, archivePath: String) {
        if (url.isBlank() || !url.startsWith("http")) return
        val list = bookmarks().toMutableList()
        val idx = list.indexOfFirst { it.url == url }
        val now = System.currentTimeMillis()
        if (idx >= 0) {
            val prev = list[idx]
            list[idx] = prev.copy(
                title = title.ifBlank { prev.title.ifBlank { url } },
                offlineArchivePath = archivePath,
                offlineSavedAt = now
            )
        } else {
            list.add(
                0,
                Bookmark(
                    url = url,
                    title = title.ifBlank { url },
                    offlineArchivePath = archivePath,
                    offlineSavedAt = now
                )
            )
        }
        saveBookmarks(list)
    }

    fun removeOfflineCopy(url: String) {
        val list = bookmarks().toMutableList()
        val idx = list.indexOfFirst { it.url == url }
        if (idx >= 0) {
            val prev = list[idx]
            if (prev.offlineArchivePath.isNotBlank()) {
                try { File(prev.offlineArchivePath).delete() } catch (_: Exception) {}
            }
            list[idx] = prev.copy(offlineArchivePath = "", offlineSavedAt = 0L)
            saveBookmarks(list)
        }
    }

    fun removeBookmark(url: String) {
        val list = bookmarks().toMutableList()
        list.filter { it.url == url && it.offlineArchivePath.isNotBlank() }.forEach {
            try { File(it.offlineArchivePath).delete() } catch (_: Exception) {}
        }
        list.removeAll { it.url == url }
        saveBookmarks(list)
    }

    private fun saveBookmarks(list: List<Bookmark>) {
        val arr = JSONArray()
        for (b in list) {
            arr.put(
                JSONObject()
                    .put("url", b.url)
                    .put("title", b.title)
                    .put("offlineArchivePath", b.offlineArchivePath)
                    .put("offlineSavedAt", b.offlineSavedAt)
            )
        }
        prefs.edit().putString(KEY_BOOKMARKS, arr.toString()).apply()
    }

    // ---- Downloads log & V3 DownloadItems ----------------------------------

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
        val v3 = downloadItemsV3()
        if (v3.isNotEmpty()) {
            return v3.map { DownloadRecord(it.fileName, it.url, it.mimeType, it.createdAt) }
        }
        return legacyDownloads()
    }

    private fun legacyDownloads(): List<DownloadRecord> {
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

    @Synchronized
    fun downloadItemsV3(): List<DownloadItem> {
        val raw = prefs.getString(KEY_DOWNLOADS_V3, null)
        if (raw.isNullOrBlank()) {
            val legacy = legacyDownloads()
            if (legacy.isEmpty()) return emptyList()
            val migrated = legacy.mapIndexed { idx, rec ->
                DownloadItem(
                    id = "legacy_${rec.time}_$idx",
                    fileName = rec.fileName,
                    url = rec.url,
                    mimeType = rec.mimeType,
                    status = DownloadStatus.COMPLETED,
                    createdAt = rec.time,
                    completedAt = rec.time
                )
            }
            saveDownloadItemsV3(migrated)
            return migrated
        }
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val out = ArrayList<DownloadItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                DownloadItem(
                    id = o.optString("id"),
                    fileName = o.optString("fileName"),
                    url = o.optString("url"),
                    mimeType = o.optString("mimeType", "application/octet-stream"),
                    totalBytes = o.optLong("totalBytes", -1L),
                    downloadedBytes = o.optLong("downloadedBytes", 0L),
                    speedBytesPerSec = 0L,
                    status = DownloadStatus.fromName(o.optString("status", DownloadStatus.FAILED.name)),
                    localPath = o.optString("localPath", ""),
                    supportsRange = o.optBoolean("supportsRange", false),
                    eTag = o.optString("eTag", ""),
                    lastModified = o.optString("lastModified", ""),
                    errorMessage = o.optString("errorMessage", ""),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    completedAt = o.optLong("completedAt", 0L)
                )
            )
        }
        return out
    }

    @Synchronized
    fun saveDownloadItemsV3(items: List<DownloadItem>) {
        val capped = items.take(DOWNLOADS_CAP)
        val arr = JSONArray()
        for (d in capped) {
            arr.put(
                JSONObject()
                    .put("id", d.id)
                    .put("fileName", d.fileName)
                    .put("url", d.url)
                    .put("mimeType", d.mimeType)
                    .put("totalBytes", d.totalBytes)
                    .put("downloadedBytes", d.downloadedBytes)
                    .put("status", d.status.name)
                    .put("localPath", d.localPath)
                    .put("supportsRange", d.supportsRange)
                    .put("eTag", d.eTag)
                    .put("lastModified", d.lastModified)
                    .put("errorMessage", d.errorMessage)
                    .put("createdAt", d.createdAt)
                    .put("completedAt", d.completedAt)
            )
        }
        prefs.edit().putString(KEY_DOWNLOADS_V3, arr.toString()).apply()
    }

    fun clearDownloadsLog() {
        prefs.edit().remove(KEY_DOWNLOADS).remove(KEY_DOWNLOADS_V3).apply()
    }

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
