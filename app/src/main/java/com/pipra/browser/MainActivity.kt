package com.pipra.browser

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.absoluteValue
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse

open class MainActivity : AppCompatActivity() {

    companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0"
        private const val PRIVATE_HOME = "about:blank"
        private const val DONATE_URL = "https://www.supportkori.com/muhammedomor"
        private const val MAX_CLOSED_TABS = 20
    }

    protected open val isPrivate: Boolean = false

    private class Tab(
        val session: GeckoSession,
        val chip: View,
        val titleView: TextView,
        val closeView: TextView
    ) {
        var title: String = "New Tab"
        var desktop: Boolean = false
        var canGoBack: Boolean = false
        var canGoForward: Boolean = false
        var historyCurrentIndex: Int = 0
        val historyEntries: MutableList<Pair<String, String>> = mutableListOf()
        val blocked = AtomicInteger(0)
        @Volatile var currentUrl: String? = null
        @Volatile var lastFailedUrl: String? = null
        @Volatile var internalRoute: String? = null
    }

    private data class ClosedTabRecord(
        val url: String,
        val title: String,
        val desktop: Boolean
    )

    private val tabs = mutableListOf<Tab>()
    private val closedTabs = ArrayDeque<ClosedTabRecord>()
    private var currentIndex = -1
    private var isFullscreen = false

    private lateinit var store: Store
    private lateinit var dev: DevStore
    private lateinit var websiteBlocker: WebsiteBlocker
    private lateinit var adBlocker: AdBlockerEngine
    private lateinit var adultBlocker: AdultBlockerEngine
    private lateinit var ruleEngine: RuleEngine
    private lateinit var cookieDialogs: BrowserCookieDialogs
    private lateinit var devDialogs: BrowserDevDialogs
    private lateinit var geckoRuntime: GeckoRuntime
    private lateinit var geckoView: GeckoView

    private lateinit var rootView: View
    private lateinit var tabStrip: View
    private lateinit var toolbarRow: View
    private lateinit var webContainer: FrameLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var urlBar: AutoCompleteTextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnHome: ImageButton
    private lateinit var btnReload: ImageButton
    private lateinit var btnMenu: ImageButton
    private lateinit var btnNewTab: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView
    private lateinit var findPrev: ImageButton
    private lateinit var findNext: ImageButton
    private lateinit var findClose: ImageButton

    private val httpAllowed = mutableSetOf<String>()
    private val upgradedHosts = mutableSetOf<String>()

    private var pendingFilePromptResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null
    private var pendingFilePrompt: GeckoSession.PromptDelegate.FilePrompt? = null
    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val prompt = pendingFilePrompt
            val res = pendingFilePromptResult
            pendingFilePrompt = null
            pendingFilePromptResult = null
            if (prompt != null && res != null) {
                val clip = result.data?.clipData
                val single = result.data?.data
                val uris = when {
                    result.resultCode != RESULT_OK -> null
                    clip != null && clip.itemCount > 0 ->
                        Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                    single != null -> arrayOf(single)
                    else -> null
                }
                if (uris != null && uris.isNotEmpty()) {
                    res.complete(prompt.confirm(this, uris))
                } else {
                    res.complete(prompt.dismiss())
                }
            }
        }

    private var pendingImportFormat: String = "json"
    private var pendingExportText: String = ""
    private val openCookieFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importCookiesFrom(uri, pendingImportFormat)
        }
    private val createJsonFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) writeTextTo(uri, pendingExportText)
        }
    private val createTxtFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) writeTextTo(uri, pendingExportText)
        }

    private val homeUrl: String get() = if (isPrivate) PRIVATE_HOME else store.homepage

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (isPrivate) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        setContentView(R.layout.activity_main)

        rootView = findViewById(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        store = Store(this)
        dev = DevStore(this)
        websiteBlocker = WebsiteBlocker(this)
        adBlocker = AdBlockerEngine(this)
        adultBlocker = AdultBlockerEngine(this)
        ruleEngine = RuleEngine(dev) { url -> isBlockedDestination(url) }
        PipraDownloadManager.ensureInitialized(this)
        geckoRuntime = PipraGeckoRuntime.getOrCreate(this, store)

        cookieDialogs = BrowserCookieDialogs(
            activity = this,
            isPrivate = isPrivate,
            currentSession = { currentSessionHandle() },
            resolveUrl = { DomainUtils.resolveOmniboxInput(it, store.searchTemplate(), homeUrl) },
            openFileImport = { fmt ->
                pendingImportFormat = fmt
                val mime = if (fmt == "json") arrayOf("application/json", "text/plain", "*/*") else arrayOf("text/plain", "*/*")
                openCookieFile.launch(mime)
            },
            exportFile = { fmt, text ->
                pendingExportText = text
                if (fmt == "netscape") createTxtFile.launch("cookies.txt")
                else createJsonFile.launch("cookies.json")
            },
            copyText = ::copyText,
            toast = ::toast,
            confirm = ::confirm
        )
        devDialogs = BrowserDevDialogs(
            activity = this,
            isPrivate = isPrivate,
            store = store,
            dev = dev,
            currentSession = { currentSessionHandle() },
            applySettingsToTabs = ::applySettingsToAllTabs,
            copyText = ::copyText,
            toast = ::toast,
            confirm = ::confirm,
            isBlockedDestination = ::isBlockedDestination
        )

        tabStrip = findViewById(R.id.tabStrip)
        toolbarRow = findViewById(R.id.toolbarRow)
        webContainer = findViewById(R.id.webContainer)
        tabBar = findViewById(R.id.tabBar)
        urlBar = findViewById(R.id.urlBar)
        progress = findViewById(R.id.progress)
        findBar = findViewById(R.id.findBar)
        findInput = findViewById(R.id.findInput)
        findCount = findViewById(R.id.findCount)

        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        btnHome = findViewById(R.id.btnHome)
        btnReload = findViewById(R.id.btnReload)
        btnMenu = findViewById(R.id.btnMenu)
        btnNewTab = findViewById(R.id.btnNewTab)
        findPrev = findViewById(R.id.findPrev)
        findNext = findViewById(R.id.findNext)
        findClose = findViewById(R.id.findClose)

        geckoView = GeckoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        webContainer.addView(geckoView)

        if (isPrivate) applyPrivateLook() else applyThemeLook()
        tabStrip.visibility = if (store.showTabStrip) View.VISIBLE else View.GONE

        CookieManager.getInstance().setAcceptCookie(true)

        btnReload.setOnClickListener {
            val tab = current() ?: return@setOnClickListener
            if (tab.currentUrl == Store.DEFAULT_HOMEPAGE) loadInto(tab, Store.DEFAULT_HOMEPAGE)
            else tab.session.reload()
        }
        btnNewTab.setOnClickListener { createTab(homeUrl) }
        btnMenu.setOnClickListener { showMenu() }
        btnHome.setOnClickListener { current()?.let { loadInto(it, homeUrl) } }

        btnBack.setOnClickListener { current()?.let { if (it.canGoBack) it.session.goBack() } }
        btnForward.setOnClickListener { current()?.let { if (it.canGoForward) it.session.goForward() } }
        btnBack.setOnLongClickListener { showNavHistory(); true }
        btnForward.setOnLongClickListener { showNavHistory(); true }

        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                loadUserInput(urlBar.text.toString()); true
            } else false
        }
        setupSuggestions()
        setupFindBar()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val tab = current()
                when {
                    isFullscreen -> {
                        tab?.session?.exitFullScreen()
                        exitFullscreenUi()
                    }
                    findBar.visibility == View.VISIBLE -> closeFind()
                    tab != null && tab.canGoBack -> tab.session.goBack()
                    tabs.size > 1 -> closeTab(currentIndex)
                    else -> closeWindow()
                }
            }
        })

        createTab(incomingUrl(intent) ?: homeUrl)
    }

    override fun onResume() {
        super.onResume()
        if (::store.isInitialized) {
            tabStrip.visibility = if (store.showTabStrip) View.VISIBLE else View.GONE
            if (!isPrivate) applyThemeLook()
            applySettingsToAllTabs()
            tabs.forEach { tab ->
                if (tab.currentUrl == Store.DEFAULT_HOMEPAGE) {
                    loadInto(tab, Store.DEFAULT_HOMEPAGE)
                }
            }
            current()?.let { tab ->
                val url = tab.currentUrl
                if (url != null && checkBlockedAndRender(tab, url)) {
                    setUrlBarText("")
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incomingUrl(intent)?.let { createTab(it) }
    }

    private fun incomingUrl(intent: Intent?): String? {
        val data = intent?.data ?: return null
        val scheme = data.scheme?.lowercase()
        return if (scheme == "http" || scheme == "https") data.toString() else null
    }

    override fun onDestroy() {
        if (isPrivate && isFinishing) erasePrivateData()
        if (::geckoView.isInitialized) {
            try { geckoView.releaseSession() } catch (_: Exception) {}
        }
        for (t in tabs) {
            try { t.session.close() } catch (_: Exception) {}
        }
        tabs.clear()
        super.onDestroy()
        if (isPrivate && isFinishing) android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun closeWindow() {
        if (isPrivate) {
            erasePrivateData()
            finishAndRemoveTask()
        } else finish()
    }

    private fun erasePrivateData() {
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        } catch (_: Exception) {}
        for (t in tabs) {
            try { t.session.close() } catch (_: Exception) {}
        }
    }

    private fun currentSessionHandle(): BrowserSessionHandle? {
        val tab = current() ?: return null
        return object : BrowserSessionHandle {
            override val url: String? get() = tab.currentUrl
            override fun reload() = tab.session.reload()
            override fun navigate(url: String) = this@MainActivity.navigate(tab, url)
            override fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
                callback?.invoke(null)
            }
        }
    }

    private fun isDarkUi(): Boolean = isPrivate || store.isDarkThemeEffective()

    private fun themedContext(): Context =
        ContextThemeWrapper(
            this,
            if (isDarkUi()) R.style.Theme_Pipra_Dialog_Dark else R.style.Theme_Pipra_Dialog_Light
        )

    private fun applyNightModeDelegate() {
        if (isPrivate) return
        val desired = when (store.themeMode) {
            AppThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            AppThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            AppThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != desired) {
            AppCompatDelegate.setDefaultNightMode(desired)
        }
    }

    private fun applyThemeLook() {
        applyNightModeDelegate()
        val dark = store.isDarkThemeEffective()
        val bg = getColor(if (dark) R.color.pipra_dark_bg else R.color.pipra_light_bg)
        val toolbarBg = getColor(if (dark) R.color.pipra_dark_toolbar else R.color.pipra_light_toolbar)
        val cardInactive = getColor(if (dark) R.color.pipra_dark_tab_inactive else R.color.pipra_light_tab_inactive)
        val border = getColor(if (dark) R.color.pipra_dark_border else R.color.pipra_light_border)
        val text = getColor(if (dark) R.color.pipra_dark_text else R.color.pipra_light_text)
        val textDim = getColor(if (dark) R.color.pipra_dark_text_dim else R.color.pipra_light_text_dim)
        val accent = getColor(if (dark) R.color.pipra_dark_accent else R.color.pipra_light_accent)

        rootView.setBackgroundColor(bg)
        tabStrip.setBackgroundColor(bg)
        toolbarRow.setBackgroundColor(toolbarBg)
        findBar.setBackgroundColor(bg)
        webContainer.setBackgroundColor(bg)

        val pillBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(cardInactive)
            setStroke(dp(1), border)
        }
        urlBar.background = pillBg
        urlBar.setTextColor(text)
        urlBar.setHintTextColor(textDim)

        findInput.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(cardInactive)
            setStroke(dp(1), border)
        }
        findInput.setTextColor(text)
        findInput.setHintTextColor(textDim)
        findCount.setTextColor(textDim)
        progress.progressTintList = ColorStateList.valueOf(accent)

        val iconTint = ColorStateList.valueOf(text)
        listOf(btnBack, btnForward, btnHome, btnReload, btnMenu, btnNewTab, findPrev, findNext, findClose).forEach {
            it.imageTintList = iconTint
        }

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark

        refreshTabChipsAppearance()
    }

    private fun applyPrivateLook() {
        val darkBg = getColor(R.color.private_dark)
        val bar = getColor(R.color.private_bar)
        val text = getColor(R.color.pipra_dark_text)
        val textDim = getColor(R.color.pipra_dark_text_dim)
        val border = getColor(R.color.pipra_dark_border)

        rootView.setBackgroundColor(darkBg)
        toolbarRow.setBackgroundColor(bar)
        tabStrip.setBackgroundColor(darkBg)
        findBar.setBackgroundColor(darkBg)
        webContainer.setBackgroundColor(darkBg)

        urlBar.hint = getString(R.string.private_hint)
        urlBar.setTextColor(text)
        urlBar.setHintTextColor(textDim)
        urlBar.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(getColor(R.color.pipra_dark_tab_inactive))
            setStroke(dp(1), border)
        }
        btnHome.setImageResource(R.drawable.ic_private)

        val iconTint = ColorStateList.valueOf(text)
        listOf(btnBack, btnForward, btnHome, btnReload, btnMenu, btnNewTab, findPrev, findNext, findClose).forEach {
            it.imageTintList = iconTint
        }

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        refreshTabChipsAppearance()
    }

    private fun refreshTabChipsAppearance() {
        val dark = isDarkUi()
        val activeBg = getColor(if (dark) R.color.pipra_dark_tab_active else R.color.pipra_light_tab_active)
        val inactiveBg = getColor(if (dark) R.color.pipra_dark_tab_inactive else R.color.pipra_light_tab_inactive)
        val activeText = getColor(if (dark) R.color.pipra_dark_text else R.color.pipra_light_text)
        val inactiveText = getColor(if (dark) R.color.pipra_dark_text_dim else R.color.pipra_light_text_dim)

        for (i in tabs.indices) {
            val active = i == currentIndex
            tabs[i].chip.setBackgroundColor(if (active) activeBg else inactiveBg)
            tabs[i].titleView.setTextColor(if (active) activeText else inactiveText)
            tabs[i].closeView.setTextColor(inactiveText)
        }
    }

    private fun isDeviceOffline(): Boolean {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return true
            val caps = cm.getNetworkCapabilities(net) ?: return true
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    private fun isBlockedDestination(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        if (websiteBlocker.findMatchingRuleForUrl(url) != null) return true
        if (adultBlocker.isBlockedUrl(url, store.adultProtectionEnabled)) return true
        return false
    }

    private fun loadHtml(tab: Tab, html: String, routeTag: String) {
        tab.internalRoute = routeTag
        tab.session.load(GeckoSession.Loader().data(html, "text/html"))
    }

    private fun checkBlockedAndRender(tab: Tab, url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val host = DomainUtils.extractHostFromUrl(url) ?: return false
        val dark = isDarkUi()

        val siteRule = websiteBlocker.findMatchingRuleForHost(host)
        if (siteRule != null) {
            tab.session.stop()
            tab.currentUrl = "about:blank"
            val html = BlockedPageHtml.blockedWebsiteHtml(host, siteRule, websiteBlocker, isDark = dark)
            loadHtml(tab, html, "about:blocked")
            return true
        }

        if (adultBlocker.isBlockedHost(host, store.adultProtectionEnabled)) {
            tab.session.stop()
            tab.currentUrl = "about:blank"
            val html = BlockedPageHtml.adultBlockedHtml(host, isDark = dark)
            loadHtml(tab, html, "about:blocked")
            return true
        }
        return false
    }

    private fun current(): Tab? = tabs.getOrNull(currentIndex)

    private fun createTab(
        url: String?,
        desktopOverride: Boolean? = null,
        openSession: Boolean = true
    ): Tab {
        val desktop = desktopOverride ?: store.desktopByDefault
        val settings = PipraGeckoRuntime.buildSessionSettings(
            isPrivate = isPrivate,
            desktop = desktop,
            store = store,
            desktopUa = DESKTOP_UA
        )
        val session = GeckoSession(settings)
        val chip = layoutInflater.inflate(R.layout.tab_item, tabBar, false)
        val titleView = chip.findViewById<TextView>(R.id.tabTitle)
        val closeView = chip.findViewById<TextView>(R.id.tabClose)
        val tab = Tab(session, chip, titleView, closeView)
        tab.desktop = desktop

        wireSessionDelegates(tab)
        if (openSession) {
            session.open(geckoRuntime)
        }

        tab.chip.setOnClickListener { switchTo(tabs.indexOf(tab)) }
        tab.closeView.setOnClickListener { closeTab(tabs.indexOf(tab)) }
        if (isPrivate) tab.titleView.text = "[P] New Tab"

        tabs.add(tab)
        tabBar.addView(tab.chip)
        switchTo(tabs.size - 1)

        if (url != null) navigate(tab, url)
        return tab
    }

    private fun wireSessionDelegates(tab: Tab) {
        val session = tab.session

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny> {
                val u = Uri.parse(request.uri)
                val isMainFrame = request.target != GeckoSession.NavigationDelegate.TARGET_WINDOW_NONE
                val blockedOrHandled = handleNavigation(
                    tab = tab,
                    u = u,
                    mainFrame = isMainFrame,
                    gesture = request.hasUserGesture
                )
                return if (blockedOrHandled) GeckoResult.deny() else GeckoResult.allow()
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (url == null) return
                if (url.startsWith("data:")) {
                    val internal = tab.internalRoute
                    if (internal == Store.DEFAULT_HOMEPAGE || internal == PRIVATE_HOME || internal == "about:blocked") {
                        tab.currentUrl = if (internal == "about:blocked") "about:blank" else internal
                        if (tab === current()) setUrlBarText("")
                    } else if (internal == "about:error" && tab.lastFailedUrl != null) {
                        tab.currentUrl = tab.lastFailedUrl
                        if (tab === current()) setUrlBarText(tab.lastFailedUrl.orEmpty())
                    }
                    return
                }
                tab.internalRoute = null
                if (checkBlockedAndRender(tab, url)) {
                    if (tab === current()) {
                        setUrlBarText("")
                        updateNavButtons()
                    }
                    return
                }
                if (!url.startsWith("about:") && !url.startsWith("pipra://")) {
                    tab.currentUrl = url
                    tab.lastFailedUrl = null
                }
                if (tab === current()) {
                    if (!urlBar.hasFocus()) setUrlBarText(displayUrl(url))
                    updateNavButtons()
                }
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                tab.canGoBack = canGoBack
                if (tab === current()) updateNavButtons()
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                tab.canGoForward = canGoForward
                if (tab === current()) updateNavButtons()
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
                if (store.blockPopups) {
                    toast("Pop-up blocked")
                    return GeckoResult.fromValue(null)
                }
                val newTab = createTab(null, openSession = false)
                return GeckoResult.fromValue(newTab.session)
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                val failed = uri ?: return null
                val u = Uri.parse(failed)
                val host = u.host
                if (u.scheme == "https" && host != null && host in upgradedHosts) {
                    upgradedHosts.remove(host)
                    runOnUiThread {
                        AlertDialog.Builder(themedContext())
                            .setTitle("Site doesn't support HTTPS")
                            .setMessage("$host couldn't be loaded securely. Continue with an unencrypted HTTP connection?")
                            .setPositiveButton("Continue") { _, _ ->
                                httpAllowed.add(host)
                                val fallbackUrl = u.buildUpon().scheme("http").build().toString()
                                if (!checkBlockedAndRender(tab, fallbackUrl)) {
                                    tab.internalRoute = null
                                    tab.session.loadUri(fallbackUrl)
                                }
                            }
                            .setNegativeButton("Go back", null)
                            .show()
                    }
                    return GeckoResult.fromValue("about:blank")
                }

                if (u.scheme == "http" || u.scheme == "https") {
                    tab.lastFailedUrl = failed
                    tab.currentUrl = failed
                    tab.internalRoute = "about:error"
                    val html = BlockedPageHtml.networkErrorHtml(
                        failedUrl = failed,
                        errorDescription = "Error code ${error.code}",
                        isOffline = isDeviceOffline(),
                        isDark = isDarkUi()
                    )
                    if (tab === current()) {
                        runOnUiThread { setUrlBarText(failed) }
                    }
                    return GeckoResult.fromValue("data:text/html;charset=utf-8," + Uri.encode(html))
                }
                return null
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                tab.blocked.set(0)
                if (!url.startsWith("data:") && !url.startsWith("about:") && !url.startsWith("pipra://")) {
                    if (checkBlockedAndRender(tab, url)) {
                        if (tab === current()) {
                            setUrlBarText("")
                            updateNavButtons()
                        }
                        return
                    }
                    tab.currentUrl = url
                    tab.lastFailedUrl = null
                    if (tab === current() && !urlBar.hasFocus()) {
                        setUrlBarText(displayUrl(url))
                    }
                }
                if (tab === current()) updateNavButtons()
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                val pageUrl = tab.currentUrl
                if (success && pageUrl != null && !pageUrl.startsWith("about:") && !pageUrl.startsWith("data:")) {
                    Uri.parse(pageUrl).host?.let { upgradedHosts.remove(it) }
                    if (!isPrivate && !pageUrl.startsWith("pipra://") && !isBlockedDestination(pageUrl)) {
                        store.addHistory(pageUrl, tab.title)
                    }
                }
                if (tab === current()) {
                    progress.visibility = View.GONE
                    updateNavButtons()
                }
            }

            override fun onProgressChange(session: GeckoSession, progressVal: Int) {
                if (tab !== current()) return
                progress.progress = progressVal
                progress.visibility = if (progressVal in 1..99) View.VISIBLE else View.GONE
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                val clean = title?.takeIf {
                    it.isNotBlank() && it != "about:blank" && !it.startsWith("data:text/html")
                } ?: "New Tab"
                tab.title = clean
                tab.titleView.text = (if (isPrivate) "[P] " else "") + clean
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                if (fullScreen) enterFullscreenUi() else exitFullscreenUi()
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                val disp = response.headers["content-disposition"] ?: response.headers["Content-Disposition"]
                val mime = response.headers["content-type"] ?: response.headers["Content-Type"]
                runOnUiThread {
                    startDownload(response.uri, null, disp, mime)
                }
            }

            override fun onContextMenu(
                session: GeckoSession,
                screenX: Int,
                screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement
            ) {
                val linkUrl = element.linkUri?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                val imgUrl = element.srcUri?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                if (linkUrl != null || imgUrl != null) {
                    runOnUiThread {
                        presentLinkImageDialog(
                            tab = tab,
                            linkUrl = linkUrl,
                            imageUrl = imgUrl,
                            isLink = linkUrl != null,
                            isImage = imgUrl != null
                        )
                    }
                }
            }

            override fun onCrash(session: GeckoSession) = recoverCrashedTab(tab)
            override fun onKill(session: GeckoSession) = recoverCrashedTab(tab)
        }

        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onAndroidPermissionsRequest(
                session: GeckoSession,
                permissions: Array<out String>?,
                callback: GeckoSession.PermissionDelegate.Callback
            ) {
                callback.reject()
            }

            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int> =
                GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback
            ) {
                callback.reject()
            }
        }

        session.historyDelegate = object : GeckoSession.HistoryDelegate {
            override fun onHistoryStateChange(
                session: GeckoSession,
                historyList: GeckoSession.HistoryDelegate.HistoryList
            ) {
                tab.historyCurrentIndex = historyList.currentIndex
                tab.historyEntries.clear()
                for (item in historyList) {
                    val u = item.uri
                    val t = item.title.orEmpty().ifBlank { u }
                    if (!u.startsWith("data:")) tab.historyEntries.add(u to t)
                }
            }
        }

        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onFilePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FilePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                pendingFilePrompt = prompt
                pendingFilePromptResult = result
                val mime = prompt.mimeTypes?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "*/*"
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = mime
                    if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE) {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }
                return try {
                    pickFiles.launch(Intent.createChooser(intent, "Choose file"))
                    result
                } catch (_: Exception) {
                    pendingFilePrompt = null
                    pendingFilePromptResult = null
                    result.complete(prompt.dismiss())
                    result
                }
            }
        }
    }

    private fun recoverCrashedTab(tab: Tab) {
        runOnUiThread {
            val idx = tabs.indexOf(tab)
            if (idx < 0) return@runOnUiThread
            val recoverUrl = tab.currentUrl ?: homeUrl
            val wasDesktop = tab.desktop
            if (tab === current()) {
                try { geckoView.releaseSession() } catch (_: Exception) {}
            }
            tabs.removeAt(idx)
            tabBar.removeView(tab.chip)
            try { tab.session.close() } catch (_: Exception) {}
            if (currentIndex >= tabs.size) currentIndex = tabs.size - 1
            createTab(recoverUrl, desktopOverride = wasDesktop)
            toast("Recovered page renderer")
        }
    }

    private fun applySettingsToAllTabs() {
        PipraGeckoRuntime.applyStoreSettings(store)
        for (t in tabs) {
            t.session.settings.useTrackingProtection = store.shieldsEnabled
            applyUserAgent(t)
        }
    }

    private fun handleNavigation(tab: Tab, u: Uri, mainFrame: Boolean, gesture: Boolean): Boolean {
        val rawStr = u.toString()

        if (u.scheme.equals("pipra", ignoreCase = true)) {
            if (!mainFrame) return true
            when (u.host?.lowercase()) {
                "home" -> loadInto(tab, Store.DEFAULT_HOMEPAGE)
                "search" -> {
                    val q = u.getQueryParameter("q").orEmpty()
                    if (q.isNotBlank()) {
                        val resolved = DomainUtils.resolveOmniboxInput(q, store.searchTemplate(), homeUrl)
                        navigate(tab, resolved)
                    }
                }
                "engine" -> {
                    val eng = u.getQueryParameter("set").orEmpty()
                    if (eng.isNotBlank()) {
                        store.searchEngine = eng
                        toast("Search engine: ${store.searchEngine}")
                        loadInto(tab, Store.DEFAULT_HOMEPAGE)
                    }
                }
                "custom-engine" -> promptCustomSearchEngine(tab)
                "bookmarks" -> if (isPrivate) toast("Bookmarks are disabled in Private mode") else showBookmarks()
                "history" -> if (isPrivate) toast("History is disabled in Private mode") else showHistory()
                "downloads" -> openDownloads()
                "settings" -> {
                    if (isPrivate) toast("Open Settings from normal browser window")
                    else startActivity(Intent(this, SettingsActivity::class.java))
                }
                "game" -> startActivity(Intent(this, OfflineGameActivity::class.java))
                "retry" -> {
                    val target = tab.lastFailedUrl
                    if (!target.isNullOrBlank()) navigate(tab, target)
                    else loadInto(tab, homeUrl)
                }
            }
            return true
        }

        if (mainFrame && isBlockedDestination(rawStr)) {
            checkBlockedAndRender(tab, rawStr)
            return true
        }

        if (!mainFrame) {
            val blockedByAds = adBlocker.shouldBlockSubresource(
                requestUrl = rawStr,
                pageUrl = tab.currentUrl,
                mode = store.adBlockMode,
                enabled = store.shieldsEnabled,
                exceptions = store.adBlockExceptions()
            )
            if (blockedByAds || isBlockedDestination(rawStr)) {
                if (blockedByAds) {
                    tab.blocked.incrementAndGet()
                    store.incrementAdBlocked(1L)
                }
                return true
            }
        }

        if (mainFrame && dev.devToolsEnabled) {
            val matching = dev.requestRules().filter { it.enabled && UrlMatch.matches(it.urlPattern, rawStr) }
            if (matching.any { it.action == RequestRule.ACTION_BLOCK }) {
                return true
            }
            matching.firstOrNull {
                it.action == RequestRule.ACTION_REDIRECT && it.target.isNotBlank() && it.target != rawStr
            }?.let { rule ->
                if (rule.target.startsWith("http://") || rule.target.startsWith("https://")) {
                    if (!checkBlockedAndRender(tab, rule.target)) {
                        tab.internalRoute = null
                        tab.session.loadUri(rule.target)
                    }
                }
                return true
            }
            val urlEdits = matching.filter { it.action == RequestRule.ACTION_QUERY || it.action == RequestRule.ACTION_URLREPLACE }
            if (urlEdits.isNotEmpty()) {
                val rewritten = ruleEngine.rewriteUrl(rawStr, urlEdits)
                if (rewritten != rawStr && (rewritten.startsWith("http://") || rewritten.startsWith("https://"))) {
                    if (!checkBlockedAndRender(tab, rewritten)) {
                        tab.internalRoute = null
                        tab.session.loadUri(rewritten)
                    }
                    return true
                }
            }
        }

        when (u.scheme?.lowercase()) {
            "https" -> {
                if (mainFrame && (store.stripTrackingParams || (store.shieldsEnabled && store.adBlockMode == AdBlockMode.ULTIMATE))) {
                    val stripped = adBlocker.stripTrackingParams(rawStr)
                    if (stripped != rawStr) {
                        tab.internalRoute = null
                        tab.session.loadUri(stripped)
                        return true
                    }
                }
                return false
            }
            "http" -> {
                val host = u.host ?: return false
                if (mainFrame && store.httpsOnly && host !in httpAllowed) {
                    upgradedHosts.add(host)
                    val upgraded = u.buildUpon().scheme("https").build().toString()
                    val finalUrl = if (store.stripTrackingParams) adBlocker.stripTrackingParams(upgraded) else upgraded
                    if (!checkBlockedAndRender(tab, finalUrl)) {
                        tab.internalRoute = null
                        tab.session.loadUri(finalUrl)
                    }
                    return true
                }
                return false
            }
            "about" -> return rawStr != "about:blank"
            "data" -> return tab.internalRoute == null
            "file", "content", "javascript", "blob", "vbscript" -> return true
            "intent" -> { openIntentUri(tab, u, gesture); return true }
            else -> { openExternal(Intent(Intent.ACTION_VIEW, u), gesture); return true }
        }
    }

    private fun promptCustomSearchEngine(tab: Tab) {
        val input = EditText(themedContext()).apply {
            hint = "https://search.example.com/?q=%s"
            setText(store.customSearchUrl)
            setSingleLine(true)
        }
        AlertDialog.Builder(themedContext())
            .setTitle("Custom Search Engine URL")
            .setMessage("Enter an http:// or https:// search URL template. Use %s where your query belongs.")
            .setView(input)
            .setPositiveButton("Save & Use") { _, _ ->
                val valid = DomainUtils.validateCustomSearchUrl(input.text.toString())
                if (valid == null) {
                    toast("Invalid URL template")
                } else {
                    store.customSearchUrl = valid
                    store.searchEngine = Store.CUSTOM_SEARCH_ENGINE
                    loadInto(tab, Store.DEFAULT_HOMEPAGE)
                    toast("Custom search engine active")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openIntentUri(tab: Tab, u: Uri, gesture: Boolean) {
        val intent = try { Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) } catch (_: Exception) { return }
        val fallback = intent.getStringExtra("browser_fallback_url")
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        if (intent.resolveActivity(packageManager) != null) openExternal(intent, gesture)
        else if (fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://"))) {
            if (!checkBlockedAndRender(tab, fallback)) {
                tab.internalRoute = null
                tab.session.loadUri(fallback)
            }
        }
    }

    private fun openExternal(intent: Intent, gesture: Boolean) {
        if (!gesture) return
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        if (intent.resolveActivity(packageManager) == null) { toast("No app can open this link"); return }
        AlertDialog.Builder(themedContext())
            .setMessage("Open this link in another app?")
            .setPositiveButton("Open") { _, _ ->
                try { startActivity(intent) } catch (_: Exception) { toast("Couldn't open link") }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun navigate(tab: Tab, rawUrl: String) {
        val url = if (store.stripTrackingParams) adBlocker.stripTrackingParams(rawUrl) else rawUrl
        if (url == Store.DEFAULT_HOMEPAGE || (url == PRIVATE_HOME && isPrivate)) {
            loadInto(tab, url)
            return
        }
        if (checkBlockedAndRender(tab, url)) return

        val u = Uri.parse(url)
        val host = u.host
        if (u.scheme == "http" && store.httpsOnly && host != null && host !in httpAllowed) {
            upgradedHosts.add(host)
            val httpsUrl = u.buildUpon().scheme("https").build().toString()
            if (!checkBlockedAndRender(tab, httpsUrl)) loadInto(tab, httpsUrl)
        } else {
            loadInto(tab, url)
        }
    }

    private fun loadInto(tab: Tab, url: String) {
        when {
            url == PRIVATE_HOME && isPrivate -> {
                tab.currentUrl = PRIVATE_HOME
                loadHtml(tab, BlockedPageHtml.privateLandingHtml(), PRIVATE_HOME)
                if (tab === current()) setUrlBarText("")
            }
            url == Store.DEFAULT_HOMEPAGE -> {
                tab.currentUrl = Store.DEFAULT_HOMEPAGE
                val html = BlockedPageHtml.pipraHomeHtml(
                    activeEngine = store.searchEngine,
                    availableEngines = store.availableSearchEngines(),
                    bookmarks = if (isPrivate) emptyList() else store.bookmarks(),
                    isDark = isDarkUi()
                )
                loadHtml(tab, html, Store.DEFAULT_HOMEPAGE)
                if (tab === current()) setUrlBarText("")
            }
            else -> {
                if (!checkBlockedAndRender(tab, url)) {
                    tab.internalRoute = null
                    tab.session.loadUri(url)
                }
            }
        }
    }

    private fun displayUrl(url: String): String =
        if (url == PRIVATE_HOME || url == Store.DEFAULT_HOMEPAGE || url == "about:blank" || url.startsWith("data:")) ""
        else url

    private fun switchTo(index: Int) {
        if (index < 0 || index >= tabs.size) return
        currentIndex = index
        val tab = tabs[index]
        geckoView.setSession(tab.session)
        refreshTabChipsAppearance()
        setUrlBarText(displayUrl(tab.currentUrl ?: ""))
        updateNavButtons()
        if (findBar.visibility == View.VISIBLE) closeFind()
    }

    private fun closeTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        val wasCurrent = index == currentIndex
        val tab = tabs.removeAt(index)
        val url = tab.currentUrl ?: homeUrl
        if (url.startsWith("http://") || url.startsWith("https://")) {
            closedTabs.addFirst(ClosedTabRecord(url, tab.title, tab.desktop))
            while (closedTabs.size > MAX_CLOSED_TABS) closedTabs.removeLast()
        }
        if (wasCurrent) {
            try { geckoView.releaseSession() } catch (_: Exception) {}
        }
        tabBar.removeView(tab.chip)
        try { tab.session.close() } catch (_: Exception) {}
        if (tabs.isEmpty()) createTab(homeUrl)
        else switchTo(index.coerceAtMost(tabs.size - 1))
    }

    private fun reopenClosedTab() {
        val last = if (closedTabs.isNotEmpty()) closedTabs.removeFirst() else null
        if (last == null) {
            toast("No recently closed tabs")
            return
        }
        createTab(last.url, desktopOverride = last.desktop)
    }

    private fun loadUserInput(text: String) {
        val tab = current() ?: return
        val resolved = DomainUtils.resolveOmniboxInput(text, store.searchTemplate(), homeUrl)
        navigate(tab, resolved)
        urlBar.clearFocus()
        hideKeyboard(urlBar)
    }

    private fun hideKeyboard(v: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun setUrlBarText(text: String) {
        urlBar.setText(text, false)
    }

    private fun updateNavButtons() {
        val tab = current()
        val back = tab?.canGoBack == true
        val fwd = tab?.canGoForward == true
        btnBack.isEnabled = back; btnBack.alpha = if (back) 1f else 0.35f
        btnForward.isEnabled = fwd; btnForward.alpha = if (fwd) 1f else 0.35f
    }

    private fun setupSuggestions() {
        val adapter = SuggestAdapter(this, isDarkTheme = { isDarkUi() }) { suggestionSource() }
        urlBar.setAdapter(adapter)
        urlBar.threshold = 1
        urlBar.setOnItemClickListener { _, _, position, _ ->
            val picked = adapter.getItem(position)
            loadUserInput(picked.url)
        }
    }

    private fun suggestionSource(): List<Suggestion> {
        if (isPrivate) return SuggestAdapter.TOP_SITES
        val out = ArrayList<Suggestion>()
        for (h in store.history()) out.add(Suggestion(h.title.ifBlank { h.url }, h.url, Suggestion.Kind.HISTORY))
        for (b in store.bookmarks()) out.add(Suggestion(b.title.ifBlank { b.url }, b.url, Suggestion.Kind.BOOKMARK))
        out.addAll(SuggestAdapter.TOP_SITES)
        return out
    }

    private fun showNavHistory() {
        val tab = current() ?: return
        val entries = tab.historyEntries
        if (entries.isEmpty()) { toast("Nothing to go back to yet"); return }
        val items = ArrayList<Pair<String, Int>>()
        for (i in entries.size - 1 downTo 0) {
            val (u, t) = entries[i]
            val marker = when {
                i == tab.historyCurrentIndex -> "• "
                i < tab.historyCurrentIndex -> "← "
                else -> "→ "
            }
            items.add("$marker${t.ifBlank { u }}" to i)
        }
        AlertDialog.Builder(themedContext())
            .setTitle("Pages in this tab")
            .setItems(items.map { it.first }.toTypedArray()) { _, which ->
                val targetIdx = items[which].second
                if (targetIdx != tab.historyCurrentIndex) {
                    tab.session.gotoHistoryIndex(targetIdx)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun applyUserAgent(tab: Tab) {
        val s = tab.session.settings
        if (tab.desktop) {
            s.userAgentOverride = DESKTOP_UA
            s.userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            s.viewportMode = GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
        } else {
            s.userAgentOverride = null
            s.userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            s.viewportMode = GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        }
    }

    private fun toggleDesktopCurrent() {
        val tab = current() ?: return
        tab.desktop = !tab.desktop
        applyUserAgent(tab)
        tab.session.reload()
        toast(if (tab.desktop) "Desktop site on" else "Desktop site off")
    }

    private fun enterFullscreenUi() {
        if (isFullscreen) return
        isFullscreen = true
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun exitFullscreenUi() {
        if (!isFullscreen) return
        isFullscreen = false
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun presentLinkImageDialog(
        tab: Tab,
        linkUrl: String?,
        imageUrl: String?,
        isLink: Boolean,
        isImage: Boolean
    ) {
        val primaryUrl = linkUrl ?: imageUrl ?: return
        val ua = if (tab.desktop) DESKTOP_UA else null
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (isLink && linkUrl != null) {
            options += "Open link" to { navigate(tab, linkUrl) }
            options += "Open in new tab" to { createTab(linkUrl); Unit }
            if (!isPrivate) options += "Open in private window" to { openPrivateWindow(linkUrl) }
            options += "Download link" to { startDownload(linkUrl, ua, null, null) }
            options += "Copy link address" to { copyText(linkUrl, "Link copied") }
            options += "Share link" to { shareText(linkUrl) }
        }
        if (isImage && imageUrl != null) {
            options += "Open image in new tab" to { createTab(imageUrl); Unit }
            options += "Download image" to { startDownload(imageUrl, ua, null, "image/*") }
            if (!isLink) {
                options += "Copy image URL" to { copyText(imageUrl, "Image URL copied") }
                options += "Share image URL" to { shareText(imageUrl) }
            }
        }
        if (options.isEmpty()) return

        AlertDialog.Builder(themedContext())
            .setTitle(primaryUrl.take(80))
            .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
            .show()
    }

    private fun openPrivateWindow(url: String? = null) {
        val i = Intent(this, PrivateActivity::class.java)
        if (url != null) i.data = Uri.parse(url)
        startActivity(i)
    }

    private fun showMenu() {
        val dark = isDarkUi()
        val barColor = if (isPrivate) getColor(R.color.private_bar) else getColor(if (dark) R.color.pipra_dark_toolbar else R.color.pipra_light_toolbar)
        val textColor = getColor(if (dark) R.color.pipra_dark_text else R.color.pipra_light_text)
        val accentColor = getColor(if (dark) R.color.pipra_dark_accent else R.color.pipra_light_accent)
        val dividerColor = getColor(if (dark) R.color.pipra_dark_border else R.color.pipra_light_border)

        val sheet = BottomSheetDialog(themedContext())
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(barColor)
            setPadding(0, dp(8), 0, dp(16))
        }
        val scroll = ScrollView(this).apply { addView(col) }

        val tab = current()
        val url = tab?.currentUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val host = url?.let { DomainUtils.extractHostFromUrl(it) }
        val bookmarked = url != null && store.isBookmarked(url)

        fun item(label: String, action: () -> Unit) {
            col.addView(menuRow(label, textColor) { sheet.dismiss(); action() })
        }

        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), 0, dp(8), dp(8)) }
        fun quickIcon(res: Int, desc: String, enabled: Boolean = true, tintColor: Int = textColor, action: () -> Unit) {
            quick.addView(ImageButton(this).apply {
                setImageResource(res)
                contentDescription = desc
                imageTintList = ColorStateList.valueOf(tintColor)
                background = rippleBg()
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.35f
                layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
                setOnClickListener { sheet.dismiss(); action() }
            })
        }
        quickIcon(R.drawable.ic_back, "Back", tab?.canGoBack == true) { tab?.session?.goBack() }
        quickIcon(R.drawable.ic_forward, "Forward", tab?.canGoForward == true) { tab?.session?.goForward() }
        if (!isPrivate) {
            quickIcon(
                if (bookmarked) R.drawable.ic_star else R.drawable.ic_star_border,
                "Bookmark",
                enabled = url != null,
                tintColor = if (bookmarked) accentColor else textColor
            ) { toggleBookmarkCurrent() }
        }
        quickIcon(R.drawable.ic_refresh, "Reload") { tab?.session?.reload() }
        quickIcon(R.drawable.ic_home, "Home") { tab?.let { loadInto(it, homeUrl) } }
        col.addView(quick)
        col.addView(View(this).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })

        val blockedHere = tab?.blocked?.get() ?: 0
        val adStatus = if (store.shieldsEnabled) "Ad Blocker (${store.adBlockMode.label}) · $blockedHere blocked here" else "Ad Blocker off (tap to enable)"
        item(adStatus) { toggleShields() }
        if (host != null && !isPrivate) {
            val isException = store.isAdBlockException(host)
            item(if (isException) "Resume Ad Blocker on $host" else "Pause Ad Blocker on $host") {
                if (isException) store.removeAdBlockException(host) else store.addAdBlockException(host)
                tab?.session?.reload()
                toast(if (isException) "Ad Blocker resumed on $host" else "Ad Blocker paused on $host")
            }
        }
        item(if (isPrivate) "New private tab" else "New tab") { createTab(homeUrl) }
        if (closedTabs.isNotEmpty()) {
            item("Reopen closed tab (${closedTabs.size})") { reopenClosedTab() }
        }
        if (isPrivate) {
            item("Erase & close private window") { closeWindow() }
        } else {
            item("New private window") { openPrivateWindow() }
            if (url != null) {
                item("Save page for offline reading") { saveCurrentPageOffline() }
            }
            item("History") { showHistory() }
            item("Bookmarks") { showBookmarks() }
        }
        item("Downloads & Local Files") { openDownloads() }
        item("Cookie Manager") { cookieDialogs.showCookieManager() }
        item("Find in page") { openFind() }
        item(if (tab?.desktop == true) "Desktop site (On)" else "Desktop site") { toggleDesktopCurrent() }
        item("Developer tools") { devDialogs.showDevTools() }
        item("Play Pipra Ant Dash (Offline Game)") {
            startActivity(Intent(this, OfflineGameActivity::class.java))
        }
        item("Share page") { url?.let { shareText(it) } ?: toast("Open a page first") }
        item("Copy link") { url?.let { copyText(it, "Link copied") } ?: toast("Open a page first") }
        if (!isPrivate) {
            item("Settings") { startActivity(Intent(this, SettingsActivity::class.java)) }
        }
        item("Donate to Developer") { createTab(DONATE_URL) }
        item("About Pipra") {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SECTION, "ABOUT"))
        }

        sheet.setContentView(scroll)
        sheet.show()
    }

    private fun menuRow(label: String, textColor: Int, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            minimumHeight = dp(48)
            isClickable = true
            background = rippleBg()
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(textColor)
        })
        return row
    }

    private fun toggleShields() {
        if (isPrivate) {
            toast(if (store.shieldsEnabled) "Ad Blocker is on (${store.adBlockMode.label})" else "Enable Ad Blocker in normal Settings")
            return
        }
        store.shieldsEnabled = !store.shieldsEnabled
        applySettingsToAllTabs()
        current()?.session?.reload()
        toast(if (store.shieldsEnabled) "Ad Blocker on (${store.adBlockMode.label})" else "Ad Blocker off")
    }

    private fun saveCurrentPageOffline() {
        val tab = current() ?: return
        val url = tab.currentUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: run {
            toast("Open a webpage before saving offline")
            return
        }
        if (isBlockedDestination(url)) {
            toast("Cannot save blocked page")
            return
        }
        val title = tab.title
        val savedDir = File(filesDir, "saved_pages").apply { mkdirs() }
        store.bookmarks().firstOrNull { it.url == url }?.offlineArchivePath?.takeIf { it.isNotBlank() }?.let { prev ->
            try { File(prev).delete() } catch (_: Exception) {}
        }
        val target = File(savedDir, "pipra_${url.hashCode().absoluteValue}_${System.currentTimeMillis()}.html")
        toast("Saving offline copy…")
        Thread {
            val saved = try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 15000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", if (tab.desktop) DESKTOP_UA else "Mozilla/5.0 (Android; Mobile) Gecko/144.0 Firefox/144.0")
                }
                val code = conn.responseCode
                if (code in 200..299) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    target.writeBytes(bytes)
                    bytes.isNotEmpty()
                } else false
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                if (saved && target.exists()) {
                    store.saveOfflineBookmark(url, title, target.absolutePath)
                    if (tabs.firstOrNull()?.currentUrl == Store.DEFAULT_HOMEPAGE) {
                        loadInto(tabs.first(), Store.DEFAULT_HOMEPAGE)
                    }
                    toast("Saved offline copy")
                } else {
                    try { target.delete() } catch (_: Exception) {}
                    toast("Could not capture offline copy of this page")
                }
            }
        }.start()
    }

    private fun openOfflineSavedPage(bookmark: Bookmark) {
        val file = File(bookmark.offlineArchivePath)
        if (!file.exists()) {
            toast("Offline copy is no longer available on device")
            return
        }
        val isHtml = file.name.endsWith(".html", ignoreCase = true) || file.name.endsWith(".htm", ignoreCase = true)
        val mime = if (isHtml) "text/html" else "multipart/related"
        val ext = if (isHtml) ".html" else ".mht"
        val uri = PipraDownloadManager.shareUriForFile(this, file) ?: Uri.fromFile(file)
        val intent = Intent(this, LocalFileViewerActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(uri, mime)
            putExtra(LocalFileViewerActivity.EXTRA_DISPLAY_NAME, "${bookmark.title.ifBlank { "Saved Page" }}$ext")
            putExtra(LocalFileViewerActivity.EXTRA_MIME_TYPE, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    private fun showHistory() {
        val all = store.history()
        if (all.isEmpty()) { toast("No history yet"); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), 0) }
        val search = EditText(themedContext()).apply { hint = "Search history"; setSingleLine(true) }
        val list = ListView(themedContext())
        root.addView(search); root.addView(list)

        var shown = all
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        fun render(items: List<HistoryEntry>) {
            shown = items
            val labels = items.map { "${it.title.ifBlank { it.url }}\n${it.url}  ·  ${df.format(Date(it.time))}" }
            list.adapter = ArrayAdapter(themedContext(), android.R.layout.simple_list_item_1, labels)
        }
        render(all)
        search.addTextChangedListener(simpleWatcher {
            val q = it.lowercase()
            render(if (q.isBlank()) all else all.filter { e -> e.url.lowercase().contains(q) || e.title.lowercase().contains(q) })
        })

        val dialog = AlertDialog.Builder(themedContext())
            .setTitle("History")
            .setView(root)
            .setPositiveButton("Close", null)
            .setNegativeButton("Clear all") { _, _ -> confirm("Clear all history?") { store.clearHistory(); toast("History cleared") } }
            .create()
        list.setOnItemClickListener { _, _, pos, _ ->
            shown.getOrNull(pos)?.let { e ->
                val tab = current()
                if (tab != null) navigate(tab, e.url) else createTab(e.url)
            }
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showBookmarks() {
        val all = store.bookmarks()
        if (all.isEmpty()) { toast("No bookmarks yet"); return }
        val df = DateFormat.getDateInstance(DateFormat.MEDIUM)
        val labels = all.map { b ->
            val offlineTag = if (b.hasOfflineCopy) " · [Offline Ready ${df.format(Date(b.offlineSavedAt))}]" else ""
            "${b.title.ifBlank { b.url }}$offlineTag\n${b.url}"
        }
        val list = ListView(themedContext()).apply {
            adapter = ArrayAdapter(themedContext(), android.R.layout.simple_list_item_1, labels)
        }
        val dialog = AlertDialog.Builder(themedContext()).setTitle("Bookmarks & Saved Pages").setView(list)
            .setPositiveButton("Close", null).create()

        list.setOnItemClickListener { _, _, pos, _ ->
            val b = all.getOrNull(pos) ?: return@setOnItemClickListener
            if (b.hasOfflineCopy && isDeviceOffline()) {
                dialog.dismiss()
                openOfflineSavedPage(b)
            } else if (b.hasOfflineCopy) {
                dialog.dismiss()
                val choices = arrayOf("Open Live Page", "Open Offline Copy", "Remove Offline Copy", "Delete Bookmark")
                AlertDialog.Builder(themedContext())
                    .setTitle(b.title.ifBlank { b.url })
                    .setItems(choices) { _, idx ->
                        when (idx) {
                            0 -> current()?.let { navigate(it, b.url) } ?: createTab(b.url)
                            1 -> openOfflineSavedPage(b)
                            2 -> { store.removeOfflineCopy(b.url); toast("Offline copy deleted") }
                            3 -> { store.removeBookmark(b.url); toast("Bookmark removed") }
                        }
                    }
                    .show()
            } else {
                val tab = current()
                if (tab != null) navigate(tab, b.url) else createTab(b.url)
                dialog.dismiss()
            }
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            all.getOrNull(pos)?.let { b ->
                confirm("Remove bookmark?\n${b.url}") {
                    store.removeBookmark(b.url)
                    dialog.dismiss()
                    toast("Removed")
                }
            }
            true
        }
        dialog.show()
    }

    private fun toggleBookmarkCurrent() {
        val tab = current() ?: return
        val url = tab.currentUrl?.takeIf { it.startsWith("http") } ?: run { toast("Open a page first"); return }
        toast(if (store.toggleBookmark(url, tab.title)) "Bookmarked" else "Bookmark removed")
    }

    private fun importCookiesFrom(uri: Uri, format: String) {
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (e: Exception) { toast("Could not read file: ${e.message}"); return }
        cookieDialogs.injectCookieText(text, format)
    }

    private fun writeTextTo(uri: Uri, text: String) {
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            toast("Cookies exported")
        } catch (e: Exception) { toast("Could not write file: ${e.message}") }
    }

    private fun setupFindBar() {
        findPrev.setOnClickListener { runFind(findInput.text.toString(), backwards = true) }
        findNext.setOnClickListener { runFind(findInput.text.toString(), backwards = false) }
        findClose.setOnClickListener { closeFind() }
        findInput.addTextChangedListener(simpleWatcher { q ->
            if (q.isEmpty()) {
                current()?.session?.finder?.clear()
                findCount.text = "0/0"
            } else {
                runFind(q, backwards = false)
            }
        })
        findInput.setOnEditorActionListener { _, _, _ ->
            runFind(findInput.text.toString(), backwards = false); true
        }
    }

    private fun runFind(query: String, backwards: Boolean) {
        val finder = current()?.session?.finder ?: return
        if (query.isEmpty()) {
            finder.clear()
            findCount.text = "0/0"
            return
        }
        val flags = if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else 0
        finder.find(query, flags).accept { res ->
            runOnUiThread {
                if (res == null || res.total == 0) {
                    findCount.text = "0/0"
                } else {
                    findCount.text = "${res.current}/${res.total}"
                }
            }
        }
    }

    private fun openFind() {
        if (current() == null) return
        findBar.visibility = View.VISIBLE
        findCount.text = "0/0"
        findInput.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeFind() {
        findBar.visibility = View.GONE
        findInput.setText("")
        current()?.session?.finder?.clear()
        hideKeyboard(findInput)
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        startActivity(Intent.createChooser(send, "Share link"))
    }

    private fun copyText(text: String, msg: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", text))
        toast(msg)
    }

    private fun openDownloads() {
        startActivity(Intent(this, DownloadFileCenterActivity::class.java))
    }

    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (!(url.startsWith("https://") || url.startsWith("http://"))) {
            toast("This download type isn't supported")
            return
        }
        if (isBlockedDestination(url)) {
            toast("Download blocked by Pipra rules")
            return
        }
        val resolvedMime = PipraDownloadManager.resolveMimeType(url, mimeType, contentDisposition)
        val rawName = URLUtil.guessFileName(url, contentDisposition, resolvedMime)
        val fileName = DomainUtils.sanitizeDownloadFileName(rawName)
        val host = DomainUtils.extractHostFromUrl(url) ?: "unknown"
        val cookieHeader = if (isPrivate) null else CookieManager.getInstance().getCookie(url)

        val perform = {
            when (
                val res = PipraDownloadManager.enqueue(
                    context = this,
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    hintedMimeType = resolvedMime,
                    cookieHeader = cookieHeader,
                    recordInHistory = !isPrivate
                )
            ) {
                is PipraDownloadManager.EnqueueResult.Started ->
                    toast("Downloading ${res.item.fileName}")
                is PipraDownloadManager.EnqueueResult.DuplicateActive ->
                    toast("Already downloading ${res.existing.fileName}")
                is PipraDownloadManager.EnqueueResult.Error ->
                    toast(res.message)
            }
        }

        if (store.askBeforeDownload) {
            confirm("Download \"$fileName\" from $host?") { perform() }
        } else {
            perform()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun confirm(message: String, onYes: () -> Unit) {
        AlertDialog.Builder(themedContext()).setMessage(message)
            .setPositiveButton("Yes") { _, _ -> onYes() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun rippleBg(): android.graphics.drawable.Drawable {
        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        return getDrawable(outValue.resourceId) ?: android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
    }

    internal fun simpleWatcher(onChange: (String) -> Unit): TextWatcher = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    }
}
