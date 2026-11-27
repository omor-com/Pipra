package com.pipra.browser

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val themeMode: AppThemeMode = AppThemeMode.LIGHT,
    val homepage: String = Store.DEFAULT_HOMEPAGE,
    val searchEngine: String = Store.DEFAULT_SEARCH_ENGINE,
    val customSearchUrl: String = "",
    val desktopByDefault: Boolean = false,
    val showTabStrip: Boolean = true,
    val blockImages: Boolean = false,
    val adBlockEnabled: Boolean = true,
    val adBlockMode: AdBlockMode = AdBlockMode.HIGH,
    val adBlockListUrl: String = AdBlockerEngine.DEFAULT_FILTER_URL,
    val adBlockLastUpdatedMillis: Long = 0L,
    val adBlockCachedCount: Int = 0,
    val adBlockTotalBlocked: Long = 0L,
    val adBlockExceptions: Set<String> = emptySet(),
    val adBlockUpdating: Boolean = false,
    val adBlockUpdateStatus: String? = null,
    val blockedRules: List<BlockedSiteRule> = emptyList(),
    val blockerIntegrityWarning: Boolean = false,
    val adultProtectionEnabled: Boolean = false,
    val adultListUrl: String = AdultBlockerEngine.DEFAULT_ADULT_LIST_URL,
    val adultLastUpdatedMillis: Long = 0L,
    val adultActiveCount: Int = 0,
    val adultUpdating: Boolean = false,
    val adultUpdateStatus: String? = null,
    val httpsOnly: Boolean = true,
    val blockThirdPartyCookies: Boolean = true,
    val blockPopups: Boolean = true,
    val stripTrackingParams: Boolean = true,
    val javaScriptEnabled: Boolean = true,
    val askBeforeDownload: Boolean = true,
    val downloads: List<DownloadRecord> = emptyList(),
    val feedbackMessage: String? = null
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    val store = Store(application)
    val websiteBlocker = WebsiteBlocker(application)
    val adBlocker = AdBlockerEngine(application)
    val adultBlocker = AdultBlockerEngine(application)

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val rules = websiteBlocker.rules()
            val adCached = adBlocker.cachedRuleCount()
            val adultCount = adultBlocker.activeDomainCount()
            _state.value = _state.value.copy(
                themeMode = store.themeMode,
                homepage = store.homepage,
                searchEngine = store.searchEngine,
                customSearchUrl = store.customSearchUrl,
                desktopByDefault = store.desktopByDefault,
                showTabStrip = store.showTabStrip,
                blockImages = store.blockImages,
                adBlockEnabled = store.shieldsEnabled,
                adBlockMode = store.adBlockMode,
                adBlockListUrl = store.adBlockListUrl,
                adBlockLastUpdatedMillis = store.adBlockLastUpdatedMillis,
                adBlockCachedCount = adCached,
                adBlockTotalBlocked = store.adBlockTotalBlocked,
                adBlockExceptions = store.adBlockExceptions(),
                blockedRules = rules,
                blockerIntegrityWarning = websiteBlocker.hasIntegrityWarning(),
                adultProtectionEnabled = store.adultProtectionEnabled,
                adultListUrl = store.adultListUrl,
                adultLastUpdatedMillis = store.adultLastUpdatedMillis,
                adultActiveCount = adultCount,
                httpsOnly = store.httpsOnly,
                blockThirdPartyCookies = store.blockThirdPartyCookies,
                blockPopups = store.blockPopups,
                stripTrackingParams = store.stripTrackingParams,
                javaScriptEnabled = store.javaScriptEnabled,
                askBeforeDownload = store.askBeforeDownload,
                downloads = store.downloads()
            )
        }
    }

    fun clearFeedback() {
        _state.value = _state.value.copy(feedbackMessage = null)
    }

    fun setThemeMode(mode: AppThemeMode) {
        store.themeMode = mode
        _state.value = _state.value.copy(
            themeMode = mode,
            feedbackMessage = "Theme updated to ${mode.label}"
        )
    }

    fun setHomepage(raw: String) {
        val t = raw.trim()
        val normalized = when {
            t.isEmpty() || t.equals("pipra://home", ignoreCase = true) -> Store.DEFAULT_HOMEPAGE
            else -> DomainUtils.resolveOmniboxInput(t, store.searchTemplate(), Store.DEFAULT_HOMEPAGE)
        }
        store.homepage = normalized
        refresh()
        _state.value = _state.value.copy(feedbackMessage = "Homepage updated")
    }

    fun setSearchEngine(name: String) {
        store.searchEngine = name
        _state.value = _state.value.copy(searchEngine = store.searchEngine)
        refresh()
    }

    fun setCustomSearchUrl(rawUrl: String): Boolean {
        val valid = DomainUtils.validateCustomSearchUrl(rawUrl)
        if (valid == null) {
            _state.value = _state.value.copy(
                feedbackMessage = "Enter a valid http(s) URL (for example: https://search.example.com/?q=%s)"
            )
            return false
        }
        store.customSearchUrl = valid
        store.searchEngine = Store.CUSTOM_SEARCH_ENGINE
        _state.value = _state.value.copy(
            customSearchUrl = valid,
            searchEngine = Store.CUSTOM_SEARCH_ENGINE,
            feedbackMessage = "Custom search engine saved and selected"
        )
        return true
    }

    fun setDesktopByDefault(enabled: Boolean) {
        store.desktopByDefault = enabled
        refresh()
    }

    fun setShowTabStrip(enabled: Boolean) {
        store.showTabStrip = enabled
        refresh()
    }

    fun setBlockImages(enabled: Boolean) {
        store.blockImages = enabled
        refresh()
    }

    fun setAdBlockEnabled(enabled: Boolean) {
        store.shieldsEnabled = enabled
        _state.value = _state.value.copy(adBlockEnabled = enabled)
        refresh()
    }

    fun setAdBlockMode(mode: AdBlockMode) {
        store.adBlockMode = mode
        _state.value = _state.value.copy(adBlockMode = mode)
        refresh()
    }

    fun setAdBlockListUrl(url: String) {
        store.adBlockListUrl = url
        refresh()
    }

    fun addAdBlockException(rawHost: String): Boolean {
        val ok = store.addAdBlockException(rawHost)
        refresh()
        _state.value = _state.value.copy(
            feedbackMessage = if (ok) "Added site exception" else "Enter a valid hostname (e.g. example.com)"
        )
        return ok
    }

    fun removeAdBlockException(host: String) {
        store.removeAdBlockException(host)
        refresh()
    }

    fun updateAdBlockLists(sourceUrl: String = store.adBlockListUrl) {
        if (_state.value.adBlockUpdating) return
        _state.value = _state.value.copy(adBlockUpdating = true, adBlockUpdateStatus = "Updating filter list…")
        viewModelScope.launch(Dispatchers.IO) {
            store.adBlockListUrl = sourceUrl
            val res = adBlocker.updateFilterLists(sourceUrl)
            if (res.success) {
                store.adBlockLastUpdatedMillis = res.updatedAtMillis
            }
            _state.value = _state.value.copy(
                adBlockUpdating = false,
                adBlockUpdateStatus = res.message,
                adBlockCachedCount = adBlocker.cachedRuleCount(),
                adBlockLastUpdatedMillis = store.adBlockLastUpdatedMillis
            )
        }
    }

    fun addWebsiteBlockRule(rawHost: String, includeSubdomains: Boolean, lockDays: Int): RuleActionResult {
        val res = websiteBlocker.addRule(rawHost, includeSubdomains, lockDays)
        if (res is RuleActionResult.Success) {
            _state.value = _state.value.copy(
                blockedRules = websiteBlocker.rules(),
                blockerIntegrityWarning = websiteBlocker.hasIntegrityWarning()
            )
        }
        refresh()
        return res
    }

    fun updateWebsiteBlockRule(id: String, rawHost: String, includeSubdomains: Boolean, lockDays: Int): RuleActionResult {
        val res = websiteBlocker.updateRule(id, rawHost, includeSubdomains, lockDays)
        if (res is RuleActionResult.Success) {
            _state.value = _state.value.copy(
                blockedRules = websiteBlocker.rules(),
                blockerIntegrityWarning = websiteBlocker.hasIntegrityWarning()
            )
        }
        refresh()
        return res
    }

    fun deleteWebsiteBlockRule(id: String): RuleActionResult {
        val res = websiteBlocker.deleteRule(id)
        if (res is RuleActionResult.Success) {
            _state.value = _state.value.copy(
                blockedRules = websiteBlocker.rules(),
                blockerIntegrityWarning = websiteBlocker.hasIntegrityWarning()
            )
        }
        refresh()
        return res
    }

    fun setAdultProtectionEnabled(enabled: Boolean) {
        store.adultProtectionEnabled = enabled
        refresh()
    }

    fun setAdultListUrl(url: String) {
        store.adultListUrl = url
        refresh()
    }

    fun updateAdultBlocklist(sourceUrl: String = store.adultListUrl) {
        if (_state.value.adultUpdating) return
        _state.value = _state.value.copy(adultUpdating = true, adultUpdateStatus = "Updating adult domain list…")
        viewModelScope.launch(Dispatchers.IO) {
            store.adultListUrl = sourceUrl
            val res = adultBlocker.updateBlocklist(sourceUrl)
            if (res.success) {
                store.adultLastUpdatedMillis = res.updatedAtMillis
            }
            _state.value = _state.value.copy(
                adultUpdating = false,
                adultUpdateStatus = res.message,
                adultActiveCount = adultBlocker.activeDomainCount(),
                adultLastUpdatedMillis = store.adultLastUpdatedMillis
            )
        }
    }

    fun setHttpsOnly(enabled: Boolean) {
        store.httpsOnly = enabled
        refresh()
    }

    fun setBlockThirdPartyCookies(enabled: Boolean) {
        store.blockThirdPartyCookies = enabled
        refresh()
    }

    fun setBlockPopups(enabled: Boolean) {
        store.blockPopups = enabled
        refresh()
    }

    fun setStripTrackingParams(enabled: Boolean) {
        store.stripTrackingParams = enabled
        refresh()
    }

    fun setJavaScriptEnabled(enabled: Boolean) {
        store.javaScriptEnabled = enabled
        refresh()
    }

    fun setAskBeforeDownload(enabled: Boolean) {
        store.askBeforeDownload = enabled
        refresh()
    }

    fun clearDownloadsLog() {
        store.clearDownloadsLog()
        refresh()
        _state.value = _state.value.copy(feedbackMessage = "Downloads history cleared")
    }

    fun clearHistoryOnly() {
        store.clearHistory()
        _state.value = _state.value.copy(feedbackMessage = "Browsing history cleared")
    }

    fun clearCookiesOnly() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        _state.value = _state.value.copy(feedbackMessage = "Cookies cleared")
    }

    fun clearSiteStorageOnly() {
        WebStorage.getInstance().deleteAllData()
        _state.value = _state.value.copy(feedbackMessage = "Site storage cleared")
    }

    /**
     * Clears history, cookies, site storage, and HTTP network log.
     * Deliberately NEVER removes Website Blocking rules or bypasses active locks.
     */
    fun clearAllBrowsingData() {
        store.clearHistory()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        NetLog.clear()
        getApplication<Application>().cacheDir.listFiles()?.forEach {
            if (it.name.contains("WebView", ignoreCase = true) || it.name.contains("cache", ignoreCase = true)) {
                it.deleteRecursively()
            }
        }
        _state.value = _state.value.copy(
            feedbackMessage = "Browsing data cleared (active website locks preserved)"
        )
    }
}
