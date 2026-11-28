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
import kotlinx.coroutines.flow.update
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
    val adultProtectionEnabled: Boolean = true,
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
    val bottomAddressBar: Boolean = false,
    val scrollToHideToolbar: Boolean = false,
    val readerTheme: String = "sepia",
    val readerFontSizeSp: Int = 18,
    val privacyStats: PrivacyStatsSnapshot = PrivacyStatsSnapshot(),
    val focusRules: List<FocusScheduleRule> = emptyList(),
    val pomodoro: PomodoroSessionState = PomodoroSessionState(),
    val sitePermissions: List<SitePermissionEntry> = emptyList(),
    val appLock: AppLockConfig = AppLockConfig(),
    val installedExtensions: List<InstalledExtensionInfo> = emptyList(),
    val extensionBusy: Boolean = false,
    val extensionStatus: String? = null,
    val downloads: List<DownloadRecord> = emptyList(),
    val feedbackMessage: String? = null
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    val store = Store(application)
    val websiteBlocker = WebsiteBlocker(application)
    val adBlocker = AdBlockerEngine(application)
    val adultBlocker = AdultBlockerEngine(application)
    val privacyStats = PrivacyStatsManager(application)
    val focusSchedule = FocusScheduleManager(application)
    val sitePermissions = SitePermissionManager(application)
    val appLock = AppLockManager(application)

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            PipraExtensionManager.installed.collect { exts ->
                _state.update { it.copy(installedExtensions = exts) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val rules = websiteBlocker.rules()
            val adCached = adBlocker.cachedRuleCount()
            val adultCount = adultBlocker.activeDomainCount()
            val integrityWarn = websiteBlocker.hasIntegrityWarning()
            val dl = store.downloads()
            val statsSnap = privacyStats.snapshot()
            val fRules = focusSchedule.rules()
            val pomo = focusSchedule.pomodoroState()
            val perms = sitePermissions.listEntries()
            val lockCfg = appLock.config()
            _state.update { cur ->
                cur.copy(
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
                    blockerIntegrityWarning = integrityWarn,
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
                    bottomAddressBar = store.bottomAddressBar,
                    scrollToHideToolbar = store.scrollToHideToolbar,
                    readerTheme = store.readerTheme,
                    readerFontSizeSp = store.readerFontSizeSp,
                    privacyStats = statsSnap,
                    focusRules = fRules,
                    pomodoro = pomo,
                    sitePermissions = perms,
                    appLock = lockCfg,
                    installedExtensions = PipraExtensionManager.installed.value,
                    downloads = dl
                )
            }
        }
    }

    fun clearFeedback() {
        _state.update { it.copy(feedbackMessage = null) }
    }

    fun setThemeMode(mode: AppThemeMode) {
        store.themeMode = mode
        _state.update {
            it.copy(
                themeMode = mode,
                feedbackMessage = "Theme updated to ${mode.label}"
            )
        }
    }

    fun setHomepage(raw: String) {
        val t = raw.trim()
        val normalized = when {
            t.isEmpty() || t.equals("pipra://home", ignoreCase = true) -> Store.DEFAULT_HOMEPAGE
            else -> DomainUtils.resolveOmniboxInput(t, store.searchTemplate(), Store.DEFAULT_HOMEPAGE)
        }
        store.homepage = normalized
        _state.update { it.copy(homepage = normalized, feedbackMessage = "Homepage updated") }
        refresh()
    }

    fun setSearchEngine(name: String) {
        store.searchEngine = name
        val effective = store.searchEngine
        _state.update { it.copy(searchEngine = effective) }
        refresh()
    }

    fun setCustomSearchUrl(rawUrl: String): Boolean {
        val valid = DomainUtils.validateCustomSearchUrl(rawUrl)
        if (valid == null) {
            _state.update {
                it.copy(feedbackMessage = "Enter a valid http(s) URL (for example: https://search.example.com/?q=%s)")
            }
            return false
        }
        store.customSearchUrl = valid
        store.searchEngine = Store.CUSTOM_SEARCH_ENGINE
        _state.update {
            it.copy(
                customSearchUrl = valid,
                searchEngine = Store.CUSTOM_SEARCH_ENGINE,
                feedbackMessage = "Custom search engine saved and selected"
            )
        }
        return true
    }

    fun setDesktopByDefault(enabled: Boolean) {
        store.desktopByDefault = enabled
        _state.update { it.copy(desktopByDefault = enabled) }
        refresh()
    }

    fun setShowTabStrip(enabled: Boolean) {
        store.showTabStrip = enabled
        _state.update { it.copy(showTabStrip = enabled) }
        refresh()
    }

    fun setBottomAddressBar(enabled: Boolean) {
        store.bottomAddressBar = enabled
        _state.update {
            it.copy(
                bottomAddressBar = enabled,
                feedbackMessage = if (enabled) "Address bar moved to bottom" else "Address bar moved to top"
            )
        }
        refresh()
    }

    fun setScrollToHideToolbar(enabled: Boolean) {
        store.scrollToHideToolbar = enabled
        _state.update { it.copy(scrollToHideToolbar = enabled) }
        refresh()
    }

    fun setReaderTheme(theme: String) {
        store.readerTheme = theme
        _state.update { it.copy(readerTheme = store.readerTheme) }
    }

    fun setReaderFontSizeSp(sizeSp: Int) {
        store.readerFontSizeSp = sizeSp
        _state.update { it.copy(readerFontSizeSp = store.readerFontSizeSp) }
    }

    fun setBlockImages(enabled: Boolean) {
        store.blockImages = enabled
        _state.update { it.copy(blockImages = enabled) }
        refresh()
    }

    fun setAdBlockEnabled(enabled: Boolean) {
        store.shieldsEnabled = enabled
        _state.update { it.copy(adBlockEnabled = enabled) }
        refresh()
    }

    fun setAdBlockMode(mode: AdBlockMode) {
        store.adBlockMode = mode
        _state.update { it.copy(adBlockMode = mode) }
        refresh()
    }

    fun setAdBlockListUrl(url: String) {
        store.adBlockListUrl = url
        _state.update { it.copy(adBlockListUrl = store.adBlockListUrl) }
        refresh()
    }

    fun addAdBlockException(rawHost: String): Boolean {
        val ok = store.addAdBlockException(rawHost)
        _state.update {
            it.copy(
                adBlockExceptions = store.adBlockExceptions(),
                feedbackMessage = if (ok) "Added site exception" else "Enter a valid hostname (e.g. example.com)"
            )
        }
        refresh()
        return ok
    }

    fun removeAdBlockException(host: String) {
        store.removeAdBlockException(host)
        _state.update { it.copy(adBlockExceptions = store.adBlockExceptions()) }
        refresh()
    }

    fun updateAdBlockLists(sourceUrl: String = store.adBlockListUrl) {
        if (_state.value.adBlockUpdating) return
        _state.update { it.copy(adBlockUpdating = true, adBlockUpdateStatus = "Updating filter list…") }
        viewModelScope.launch(Dispatchers.IO) {
            store.adBlockListUrl = sourceUrl
            val res = adBlocker.updateFilterLists(sourceUrl)
            if (res.success) {
                store.adBlockLastUpdatedMillis = res.updatedAtMillis
            }
            val cached = adBlocker.cachedRuleCount()
            val lastUpdated = store.adBlockLastUpdatedMillis
            _state.update {
                it.copy(
                    adBlockUpdating = false,
                    adBlockUpdateStatus = res.message,
                    adBlockCachedCount = cached,
                    adBlockLastUpdatedMillis = lastUpdated
                )
            }
        }
    }

    fun addWebsiteBlockRule(rawHost: String, includeSubdomains: Boolean, lockDays: Int): RuleActionResult {
        val res = websiteBlocker.addRule(rawHost, includeSubdomains, lockDays)
        if (res is RuleActionResult.Success) {
            val rules = websiteBlocker.rules()
            val warn = websiteBlocker.hasIntegrityWarning()
            _state.update { it.copy(blockedRules = rules, blockerIntegrityWarning = warn) }
        }
        refresh()
        return res
    }

    fun updateWebsiteBlockRule(id: String, rawHost: String, includeSubdomains: Boolean, lockDays: Int): RuleActionResult {
        val res = websiteBlocker.updateRule(id, rawHost, includeSubdomains, lockDays)
        if (res is RuleActionResult.Success) {
            val rules = websiteBlocker.rules()
            val warn = websiteBlocker.hasIntegrityWarning()
            _state.update { it.copy(blockedRules = rules, blockerIntegrityWarning = warn) }
        }
        refresh()
        return res
    }

    fun deleteWebsiteBlockRule(id: String): RuleActionResult {
        val res = websiteBlocker.deleteRule(id)
        if (res is RuleActionResult.Success) {
            val rules = websiteBlocker.rules()
            val warn = websiteBlocker.hasIntegrityWarning()
            _state.update { it.copy(blockedRules = rules, blockerIntegrityWarning = warn) }
        }
        refresh()
        return res
    }

    fun setAdultProtectionEnabled(enabled: Boolean) {
        store.adultProtectionEnabled = enabled
        _state.update { it.copy(adultProtectionEnabled = enabled) }
        refresh()
    }

    fun setAdultListUrl(url: String) {
        store.adultListUrl = url
        _state.update { it.copy(adultListUrl = store.adultListUrl) }
        refresh()
    }

    fun updateAdultBlocklist(sourceUrl: String = store.adultListUrl) {
        if (_state.value.adultUpdating) return
        _state.update { it.copy(adultUpdating = true, adultUpdateStatus = "Updating adult domain list…") }
        viewModelScope.launch(Dispatchers.IO) {
            store.adultListUrl = sourceUrl
            val res = adultBlocker.updateBlocklist(sourceUrl)
            if (res.success) {
                store.adultLastUpdatedMillis = res.updatedAtMillis
            }
            val count = adultBlocker.activeDomainCount()
            val lastUpdated = store.adultLastUpdatedMillis
            _state.update {
                it.copy(
                    adultUpdating = false,
                    adultUpdateStatus = res.message,
                    adultActiveCount = count,
                    adultLastUpdatedMillis = lastUpdated
                )
            }
        }
    }

    fun setHttpsOnly(enabled: Boolean) {
        store.httpsOnly = enabled
        _state.update { it.copy(httpsOnly = enabled) }
        refresh()
    }

    fun setBlockThirdPartyCookies(enabled: Boolean) {
        store.blockThirdPartyCookies = enabled
        _state.update { it.copy(blockThirdPartyCookies = enabled) }
        refresh()
    }

    fun setBlockPopups(enabled: Boolean) {
        store.blockPopups = enabled
        _state.update { it.copy(blockPopups = enabled) }
        refresh()
    }

    fun setStripTrackingParams(enabled: Boolean) {
        store.stripTrackingParams = enabled
        _state.update { it.copy(stripTrackingParams = enabled) }
        refresh()
    }

    fun setJavaScriptEnabled(enabled: Boolean) {
        store.javaScriptEnabled = enabled
        _state.update { it.copy(javaScriptEnabled = enabled) }
        refresh()
    }

    fun setAskBeforeDownload(enabled: Boolean) {
        store.askBeforeDownload = enabled
        _state.update { it.copy(askBeforeDownload = enabled) }
        refresh()
    }

    fun clearDownloadsLog() {
        store.clearDownloadsLog()
        _state.update { it.copy(downloads = emptyList(), feedbackMessage = "Downloads history cleared") }
        refresh()
    }

    fun clearHistoryOnly() {
        store.clearHistory()
        _state.update { it.copy(feedbackMessage = "Browsing history cleared") }
    }

    fun clearCookiesOnly() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        _state.update { it.copy(feedbackMessage = "Cookies cleared") }
    }

    fun clearSiteStorageOnly() {
        WebStorage.getInstance().deleteAllData()
        _state.update { it.copy(feedbackMessage = "Site storage cleared") }
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
        _state.update {
            it.copy(feedbackMessage = "Browsing data cleared (active website locks preserved)")
        }
    }

    // ---- Pipra v4 actions --------------------------------------------------

    fun resetPrivacyStats() {
        privacyStats.resetStats()
        _state.update { it.copy(privacyStats = privacyStats.snapshot(), feedbackMessage = "Privacy Shield stats reset") }
    }

    fun addFocusRule(
        label: String,
        hostsInput: String,
        includeSubdomains: Boolean,
        daysOfWeekMask: Int,
        startMinute: Int,
        endMinute: Int
    ): Boolean {
        val res = focusSchedule.addRule(label, hostsInput, includeSubdomains, daysOfWeekMask, startMinute, endMinute)
        return res.fold(
            onSuccess = {
                _state.update { it.copy(focusRules = focusSchedule.rules(), feedbackMessage = "Focus schedule saved") }
                true
            },
            onFailure = { err ->
                _state.update { it.copy(feedbackMessage = err.message ?: "Could not save focus rule") }
                false
            }
        )
    }

    fun toggleFocusRule(id: String, enabled: Boolean) {
        focusSchedule.toggleRuleEnabled(id, enabled)
        _state.update { it.copy(focusRules = focusSchedule.rules()) }
    }

    fun deleteFocusRule(id: String) {
        focusSchedule.deleteRule(id)
        _state.update { it.copy(focusRules = focusSchedule.rules(), feedbackMessage = "Focus schedule removed") }
    }

    fun startPomodoro(durationMinutes: Int, hostsInput: String): Boolean {
        val res = focusSchedule.startPomodoro(durationMinutes, hostsInput)
        return res.fold(
            onSuccess = { p ->
                _state.update { it.copy(pomodoro = p, feedbackMessage = "Pomodoro active for $durationMinutes min") }
                true
            },
            onFailure = { err ->
                _state.update { it.copy(feedbackMessage = err.message ?: "Enter valid hosts") }
                false
            }
        )
    }

    fun stopPomodoro() {
        focusSchedule.stopPomodoro()
        _state.update { it.copy(pomodoro = focusSchedule.pomodoroState(), feedbackMessage = "Pomodoro stopped") }
    }

    fun setSitePermission(host: String, type: SitePermissionType, decision: SitePermissionDecision) {
        sitePermissions.setDecision(host, type, decision)
        _state.update { it.copy(sitePermissions = sitePermissions.listEntries()) }
    }

    fun removeSitePermission(host: String, type: SitePermissionType) {
        sitePermissions.removeEntry(host, type)
        _state.update { it.copy(sitePermissions = sitePermissions.listEntries()) }
    }

    fun clearAllSitePermissions() {
        sitePermissions.clearAll()
        _state.update { it.copy(sitePermissions = emptyList(), feedbackMessage = "All site permissions reset to Ask") }
    }

    fun setAppLockPin(pin: String): Boolean {
        return appLock.setPin(pin).fold(
            onSuccess = {
                _state.update { it.copy(appLock = appLock.config(), feedbackMessage = "App Lock PIN saved") }
                true
            },
            onFailure = { err ->
                _state.update { it.copy(feedbackMessage = err.message ?: "Invalid PIN") }
                false
            }
        )
    }

    fun updateAppLockScopes(
        lockOnLaunch: Boolean,
        lockPrivateWindow: Boolean,
        lockSettings: Boolean,
        allowDeviceCredential: Boolean
    ) {
        appLock.updateScopes(lockOnLaunch, lockPrivateWindow, lockSettings, allowDeviceCredential)
        _state.update { it.copy(appLock = appLock.config()) }
    }

    fun disableAppLock(currentPin: String): Boolean {
        return when (val res = appLock.disableLock(currentPin)) {
            is PinVerifyResult.Success -> {
                _state.update { it.copy(appLock = appLock.config(), feedbackMessage = "App Lock disabled") }
                true
            }
            is PinVerifyResult.Invalid -> {
                _state.update { it.copy(feedbackMessage = "Wrong PIN (${res.remainingAttempts} attempts left)") }
                false
            }
            is PinVerifyResult.LockedOut -> {
                _state.update { it.copy(feedbackMessage = "Too many attempts. Wait ${res.remainingSeconds}s") }
                false
            }
        }
    }

    fun installExtensionFromUrl(url: String) {
        if (_state.value.extensionBusy) return
        _state.update { it.copy(extensionBusy = true, extensionStatus = "Installing extension…") }
        val runtime = PipraGeckoRuntime.getOrCreate(getApplication(), store)
        PipraExtensionManager.installFromUrl(url, runtime) { res ->
            res.fold(
                onSuccess = { info ->
                    _state.update {
                        it.copy(
                            extensionBusy = false,
                            extensionStatus = "Installed ${info.name} v${info.version}",
                            feedbackMessage = "${info.name} installed"
                        )
                    }
                },
                onFailure = { err ->
                    _state.update {
                        it.copy(
                            extensionBusy = false,
                            extensionStatus = err.message ?: "Installation failed",
                            feedbackMessage = err.message ?: "Extension install failed"
                        )
                    }
                }
            )
        }
    }

    fun installExtensionFromUri(uri: android.net.Uri) {
        if (_state.value.extensionBusy) return
        _state.update { it.copy(extensionBusy = true, extensionStatus = "Installing local .xpi package…") }
        val runtime = PipraGeckoRuntime.getOrCreate(getApplication(), store)
        PipraExtensionManager.installFromContentUri(getApplication(), uri, runtime) { res ->
            res.fold(
                onSuccess = { info ->
                    _state.update {
                        it.copy(
                            extensionBusy = false,
                            extensionStatus = "Installed ${info.name} v${info.version}",
                            feedbackMessage = "${info.name} installed"
                        )
                    }
                },
                onFailure = { err ->
                    _state.update {
                        it.copy(
                            extensionBusy = false,
                            extensionStatus = err.message ?: "Local .xpi install failed",
                            feedbackMessage = err.message ?: "Local .xpi install failed"
                        )
                    }
                }
            )
        }
    }

    fun toggleExtensionEnabled(id: String, enabled: Boolean) {
        PipraExtensionManager.setEnabled(id, enabled)
    }

    fun toggleExtensionPrivate(id: String, allowed: Boolean) {
        PipraExtensionManager.setAllowedInPrivate(id, allowed)
    }

    fun updateExtension(id: String) {
        PipraExtensionManager.updateExtension(id) { ok ->
            _state.update {
                it.copy(feedbackMessage = if (ok) "Extension checked for updates" else "Could not update extension")
            }
        }
    }

    fun uninstallExtension(id: String) {
        PipraExtensionManager.uninstall(id) { ok ->
            _state.update {
                it.copy(feedbackMessage = if (ok) "Extension removed" else "Could not remove extension")
            }
        }
    }
}
