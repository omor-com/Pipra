package com.pipra.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

open class MainActivity : AppCompatActivity() {

    companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"
        private const val PRIVATE_HOME = "about:blank"

        const val DESKTOP_SPOOF_JS = """
            (function(){try{
              Object.defineProperty(navigator,'maxTouchPoints',{get:function(){return 0;},configurable:true});
              Object.defineProperty(navigator,'platform',{get:function(){return 'Win32';},configurable:true});
              Object.defineProperty(navigator,'userAgentData',{get:function(){return undefined;},configurable:true});
              try{delete window.ontouchstart;}catch(e){}
              Object.defineProperty(window,'ontouchstart',{get:function(){return undefined;},configurable:true});
              var mm=window.matchMedia;
              window.matchMedia=function(q){var r=mm(q);if(/pointer:\s*coarse|hover:\s*none|any-pointer:\s*coarse/.test(q)){try{Object.defineProperty(r,'matches',{get:function(){return false;}});}catch(e){}}return r;};
            }catch(e){}})();
        """
    }

    protected open val isPrivate: Boolean = false

    private class Tab(val webView: WebView, val chip: View, val titleView: TextView) {
        var title: String = "New Tab"
        var desktop: Boolean = false
        var spoofScript: androidx.webkit.ScriptHandler? = null
        val blocked = AtomicInteger(0)
        @Volatile var currentUrl: String? = null
    }

    private val tabs = mutableListOf<Tab>()
    private var currentIndex = -1

    private lateinit var store: Store
    private lateinit var dev: DevStore
    private lateinit var websiteBlocker: WebsiteBlocker
    private lateinit var adBlocker: AdBlockerEngine
    private lateinit var adultBlocker: AdultBlockerEngine
    private lateinit var ruleEngine: RuleEngine
    private lateinit var cookieDialogs: BrowserCookieDialogs
    private lateinit var devDialogs: BrowserDevDialogs

    private lateinit var tabStrip: View
    private lateinit var webContainer: FrameLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var urlBar: AutoCompleteTextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView

    private val httpAllowed = mutableSetOf<String>()
    private val upgradedHosts = mutableSetOf<String>()

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
            fileCallback = null
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

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
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

        cookieDialogs = BrowserCookieDialogs(
            activity = this,
            isPrivate = isPrivate,
            currentWebView = { current()?.webView },
            navigateTo = { wv, u -> navigate(wv, u) },
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
            currentWebView = { current()?.webView },
            applySettingsToTabs = ::applySettingsToAllTabs,
            copyText = ::copyText,
            toast = ::toast,
            confirm = ::confirm,
            isBlockedDestination = ::isBlockedDestination
        )

        tabStrip = findViewById(R.id.tabStrip)
        webContainer = findViewById(R.id.webContainer)
        tabBar = findViewById(R.id.tabBar)
        urlBar = findViewById(R.id.urlBar)
        progress = findViewById(R.id.progress)
        findBar = findViewById(R.id.findBar)
        findInput = findViewById(R.id.findInput)
        findCount = findViewById(R.id.findCount)

        if (isPrivate) applyPrivateLook()
        tabStrip.visibility = if (store.showTabStrip) View.VISIBLE else View.GONE

        CookieManager.getInstance().setAcceptCookie(true)

        findViewById<ImageButton>(R.id.btnReload).setOnClickListener { current()?.webView?.reload() }
        findViewById<ImageButton>(R.id.btnNewTab).setOnClickListener { createTab(homeUrl) }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { showMenu() }
        findViewById<ImageButton>(R.id.btnHome).setOnClickListener { current()?.webView?.let { loadInto(it, homeUrl) } }

        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        btnBack.setOnClickListener { current()?.webView?.let { if (it.canGoBack()) it.goBack() } }
        btnForward.setOnClickListener { current()?.webView?.let { if (it.canGoForward()) it.goForward() } }
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
                val wv = current()?.webView
                when {
                    customView != null -> exitFullscreen()
                    findBar.visibility == View.VISIBLE -> closeFind()
                    wv != null && wv.canGoBack() -> wv.goBack()
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
            applySettingsToAllTabs()
            // Re-enforce blocking immediately if user added a rule in Settings and returned.
            current()?.let { tab ->
                val url = tab.webView.url
                if (url != null && checkBlockedAndRender(tab.webView, url)) {
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
        for (t in tabs) t.webView.destroy()
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
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        for (t in tabs) { t.webView.clearCache(true); t.webView.clearHistory(); t.webView.clearFormData() }
    }

    private fun applyPrivateLook() {
        val dark = getColor(R.color.private_dark)
        findViewById<View>(R.id.root).setBackgroundColor(dark)
        findViewById<View>(R.id.toolbarRow).setBackgroundColor(getColor(R.color.private_bar))
        findViewById<View>(R.id.tabStrip).setBackgroundColor(dark)
        findViewById<View>(R.id.findBar).setBackgroundColor(dark)
        urlBar.hint = getString(R.string.private_hint)
        findViewById<ImageButton>(R.id.btnHome).setImageResource(R.drawable.ic_private)
    }

    // ---- Blocking gate -----------------------------------------------------

    private fun isBlockedDestination(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        if (websiteBlocker.findMatchingRuleForUrl(url) != null) return true
        if (adultBlocker.isBlockedUrl(url, store.adultProtectionEnabled)) return true
        return false
    }

    private fun checkBlockedAndRender(webView: WebView, url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val host = DomainUtils.extractHostFromUrl(url) ?: return false

        val siteRule = websiteBlocker.findMatchingRuleForHost(host)
        if (siteRule != null) {
            webView.stopLoading()
            val html = BlockedPageHtml.blockedWebsiteHtml(host, siteRule, websiteBlocker)
            webView.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", "about:blank")
            return true
        }

        if (adultBlocker.isBlockedHost(host, store.adultProtectionEnabled)) {
            webView.stopLoading()
            val html = BlockedPageHtml.adultBlockedHtml(host)
            webView.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", "about:blank")
            return true
        }
        return false
    }

    // ---- Tabs --------------------------------------------------------------

    private fun current(): Tab? = tabs.getOrNull(currentIndex)

    @SuppressLint("SetJavaScriptEnabled")
    private fun createTab(url: String?): Tab {
        val webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        hardenSettings(webView.settings)

        val chip = layoutInflater.inflate(R.layout.tab_item, tabBar, false)
        val titleView = chip.findViewById<TextView>(R.id.tabTitle)
        val tab = Tab(webView, chip, titleView)
        tab.desktop = store.desktopByDefault
        applyUserAgent(tab)

        CookieManager.getInstance().setAcceptThirdPartyCookies(
            webView, !(isPrivate || store.blockThirdPartyCookies)
        )

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleNavigation(view, request.url, request.isForMainFrame, request.hasGesture())

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val reqUrl = request.url?.toString() ?: return null
                // Enforce Website Blocking and Adult Protection on subresources too.
                if (!request.isForMainFrame && isBlockedDestination(reqUrl)) {
                    return adBlocker.emptyResponse()
                }
                if (!request.isForMainFrame) {
                    val blockedByAds = adBlocker.shouldBlockSubresource(
                        requestUrl = reqUrl,
                        pageUrl = tab.currentUrl,
                        mode = store.adBlockMode,
                        enabled = store.shieldsEnabled,
                        exceptions = store.adBlockExceptions()
                    )
                    if (blockedByAds) {
                        tab.blocked.incrementAndGet()
                        store.incrementAdBlocked(1L)
                        return adBlocker.emptyResponse()
                    }
                }
                return ruleEngine.intercept(request, isPrivateSession = isPrivate)
            }

            override fun onPageStarted(view: WebView, pageUrl: String?, favicon: Bitmap?) {
                tab.blocked.set(0)
                if (pageUrl != null && checkBlockedAndRender(view, pageUrl)) {
                    tab.currentUrl = "about:blank"
                    if (view === current()?.webView) {
                        setUrlBarText("")
                        updateNavButtons()
                    }
                    return
                }
                tab.currentUrl = pageUrl
                if (view === current()?.webView) {
                    if (pageUrl != null && !urlBar.hasFocus()) setUrlBarText(displayUrl(pageUrl))
                    updateNavButtons()
                }
            }

            override fun onPageFinished(view: WebView, pageUrl: String?) {
                tab.currentUrl = pageUrl
                pageUrl?.let { u -> Uri.parse(u).host?.let { upgradedHosts.remove(it) } }
                if (pageUrl != null && !isPrivate && !pageUrl.startsWith("about:") && !pageUrl.startsWith("pipra://") && !isBlockedDestination(pageUrl)) {
                    store.addHistory(pageUrl, tab.title)
                }
                if (!isPrivate) CookieManager.getInstance().flush()
                if (tab.desktop) view.evaluateJavascript(DESKTOP_SPOOF_JS, null)
                if (pageUrl != null && (pageUrl.startsWith("http://") || pageUrl.startsWith("https://"))) {
                    applyUltimateCosmeticFilter(view, pageUrl)
                    applyInsertRules(view, pageUrl)
                }
                if (view === current()?.webView) updateNavButtons()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                val u = request.url
                val host = u.host ?: return
                if (request.isForMainFrame && u.scheme == "https" && host in upgradedHosts) {
                    upgradedHosts.remove(host)
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Site doesn't support HTTPS")
                        .setMessage("$host couldn't be loaded securely. Continue with an unencrypted HTTP connection?")
                        .setPositiveButton("Continue") { _, _ ->
                            httpAllowed.add(host)
                            val fallbackUrl = u.buildUpon().scheme("http").build().toString()
                            if (!checkBlockedAndRender(view, fallbackUrl)) {
                                view.loadUrl(fallbackUrl)
                            }
                        }
                        .setNegativeButton("Go back", null)
                        .show()
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                // Never bypass TLS certificate validation.
                handler.cancel()
                toast("Connection refused: invalid TLS certificate")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view !== current()?.webView) return
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                tab.title = title?.takeIf { it.isNotBlank() && it != "about:blank" } ?: "New Tab"
                tab.titleView.text = (if (isPrivate) "[P] " else "") + tab.title
            }

            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
            ): Boolean {
                if (store.blockPopups && !isUserGesture) {
                    toast("Pop-up blocked")
                    return false
                }
                val newTab = createTab(null)
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = newTab.webView
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) = enterFullscreen(view, callback)
            override fun onHideCustomView() = exitFullscreen()

            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try { pickFiles.launch(params.createIntent()); true }
                catch (_: Exception) { fileCallback = null; toast("No file picker available"); false }
            }

            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
                callback.invoke(origin, false, false)
        }

        webView.setDownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, _ ->
            startDownload(downloadUrl, userAgent, contentDisposition, mimeType)
        }
        webView.setOnLongClickListener { showLinkMenu(webView) }

        tab.chip.setOnClickListener { switchTo(tabs.indexOf(tab)) }
        tab.chip.findViewById<TextView>(R.id.tabClose).setOnClickListener { closeTab(tabs.indexOf(tab)) }
        if (isPrivate) tab.titleView.text = "[P] New Tab"

        tabs.add(tab)
        tabBar.addView(tab.chip)
        webContainer.addView(webView)
        switchTo(tabs.size - 1)

        if (url != null) navigate(webView, url)
        return tab
    }

    private fun applySettingsToAllTabs() {
        for (t in tabs) {
            hardenSettings(t.webView.settings)
            applyUserAgent(t)
            CookieManager.getInstance().setAcceptThirdPartyCookies(
                t.webView, !(isPrivate || store.blockThirdPartyCookies)
            )
        }
    }

    private fun applyUltimateCosmeticFilter(view: WebView, pageUrl: String) {
        if (!store.shieldsEnabled || store.adBlockMode != AdBlockMode.ULTIMATE) return
        val host = DomainUtils.extractHostFromUrl(pageUrl) ?: return
        if (store.isAdBlockException(host)) return
        val css = AdBlockerEngine.ULTIMATE_COSMETIC_CSS.replace("\\", "\\\\").replace("`", "\\`")
        val js = "(function(){if(document.getElementById('pipra-cosmetic'))return;var s=document.createElement('style');s.id='pipra-cosmetic';s.textContent=`$css`;(document.head||document.documentElement).appendChild(s);})();"
        try { view.evaluateJavascript(js, null) } catch (_: Exception) {}
    }

    private fun applyInsertRules(view: WebView, url: String) {
        if (!dev.devToolsEnabled) return
        for (r in dev.insertRulesFor(url)) {
            if (r.target.isBlank()) continue
            val js = if (r.isCss) {
                val css = r.target.replace("\\", "\\\\").replace("`", "\\`")
                "(function(){var s=document.createElement('style');s.textContent=`$css`;document.head.appendChild(s);})();"
            } else r.target
            try { view.evaluateJavascript(js, null) } catch (_: Exception) {}
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun hardenSettings(s: WebSettings) {
        s.javaScriptEnabled = store.javaScriptEnabled
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportZoom(true)
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = !store.blockPopups
        s.mediaPlaybackRequiresUserGesture = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        @Suppress("DEPRECATION")
        run {
            s.allowFileAccessFromFileURLs = false
            s.allowUniversalAccessFromFileURLs = false
            s.saveFormData = !isPrivate
        }
        s.mixedContentMode =
            if (store.httpsOnly) WebSettings.MIXED_CONTENT_NEVER_ALLOW
            else WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            s.safeBrowsingEnabled = true
        }
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.blockNetworkImage = store.blockImages
        s.loadsImagesAutomatically = !store.blockImages
    }

    private fun handleNavigation(view: WebView, u: Uri, mainFrame: Boolean, gesture: Boolean): Boolean {
        val rawStr = u.toString()
        if (rawStr.equals("pipra://home", ignoreCase = true)) {
            loadInto(view, Store.DEFAULT_HOMEPAGE)
            return true
        }

        // 1. Always enforce Website Blocking & Adult Protection first on main-frame links & redirects.
        if (mainFrame && checkBlockedAndRender(view, rawStr)) {
            return true
        }

        // 2. Developer Tool main-frame rules (must still pass blocking gate on target!).
        if (mainFrame && dev.devToolsEnabled) {
            val matching = dev.requestRules().filter { it.enabled && UrlMatch.matches(it.urlPattern, rawStr) }
            matching.firstOrNull {
                it.action == RequestRule.ACTION_REDIRECT && it.target.isNotBlank() && it.target != rawStr
            }?.let { rule ->
                if (rule.target.startsWith("http://") || rule.target.startsWith("https://")) {
                    if (!checkBlockedAndRender(view, rule.target)) view.loadUrl(rule.target)
                }
                return true
            }
            val urlEdits = matching.filter { it.action == RequestRule.ACTION_QUERY || it.action == RequestRule.ACTION_URLREPLACE }
            if (urlEdits.isNotEmpty()) {
                val rewritten = ruleEngine.rewriteUrl(rawStr, urlEdits)
                if (rewritten != rawStr && (rewritten.startsWith("http://") || rewritten.startsWith("https://"))) {
                    if (!checkBlockedAndRender(view, rewritten)) view.loadUrl(rewritten)
                    return true
                }
            }
        }

        when (u.scheme?.lowercase()) {
            "https" -> {
                if (mainFrame && (store.stripTrackingParams || (store.shieldsEnabled && store.adBlockMode == AdBlockMode.ULTIMATE))) {
                    val stripped = adBlocker.stripTrackingParams(rawStr)
                    if (stripped != rawStr) {
                        view.loadUrl(stripped)
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
                    if (!checkBlockedAndRender(view, finalUrl)) view.loadUrl(finalUrl)
                    return true
                }
                return false
            }
            "about" -> return rawStr != "about:blank"
            "file", "content", "javascript", "data", "blob", "vbscript" -> return true
            "intent" -> { openIntentUri(view, u, gesture); return true }
            else -> { openExternal(Intent(Intent.ACTION_VIEW, u), gesture); return true }
        }
    }

    private fun openIntentUri(view: WebView, u: Uri, gesture: Boolean) {
        val intent = try { Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) } catch (_: Exception) { return }
        val fallback = intent.getStringExtra("browser_fallback_url")
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        val canHandle = intent.resolveActivity(packageManager) != null
        if (canHandle) openExternal(intent, gesture)
        else if (fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://"))) {
            if (!checkBlockedAndRender(view, fallback)) view.loadUrl(fallback)
        }
    }

    private fun openExternal(intent: Intent, gesture: Boolean) {
        if (!gesture) return
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        if (intent.resolveActivity(packageManager) == null) { toast("No app can open this link"); return }
        AlertDialog.Builder(this)
            .setMessage("Open this link in another app?")
            .setPositiveButton("Open") { _, _ ->
                try { startActivity(intent) } catch (_: Exception) { toast("Couldn't open link") }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun navigate(webView: WebView, rawUrl: String) {
        val url = if (store.stripTrackingParams) adBlocker.stripTrackingParams(rawUrl) else rawUrl
        if (url == Store.DEFAULT_HOMEPAGE || (url == PRIVATE_HOME && isPrivate)) {
            loadInto(webView, url)
            return
        }
        if (checkBlockedAndRender(webView, url)) return

        val u = Uri.parse(url)
        val host = u.host
        if (u.scheme == "http" && store.httpsOnly && host != null && host !in httpAllowed) {
            upgradedHosts.add(host)
            val httpsUrl = u.buildUpon().scheme("https").build().toString()
            if (!checkBlockedAndRender(webView, httpsUrl)) loadInto(webView, httpsUrl)
        } else {
            loadInto(webView, url)
        }
    }

    private fun loadInto(webView: WebView, url: String) {
        when {
            url == PRIVATE_HOME && isPrivate -> {
                webView.loadDataWithBaseURL(null, BlockedPageHtml.privateLandingHtml(), "text/html", "utf-8", PRIVATE_HOME)
            }
            url == Store.DEFAULT_HOMEPAGE -> {
                val html = BlockedPageHtml.pipraHomeHtml(
                    adBlockEnabled = store.shieldsEnabled,
                    adBlockMode = store.adBlockMode,
                    totalBlocked = store.adBlockTotalBlocked,
                    lockedRuleCount = websiteBlocker.rules().size,
                    adultProtectionEnabled = store.adultProtectionEnabled,
                    bookmarks = if (isPrivate) emptyList() else store.bookmarks()
                )
                webView.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", Store.DEFAULT_HOMEPAGE)
            }
            else -> {
                if (!checkBlockedAndRender(webView, url)) webView.loadUrl(url)
            }
        }
    }

    private fun displayUrl(url: String): String =
        if (url == PRIVATE_HOME || url == Store.DEFAULT_HOMEPAGE || url == "about:blank") "" else url

    private fun switchTo(index: Int) {
        if (index < 0 || index >= tabs.size) return
        currentIndex = index
        for (i in tabs.indices) {
            val visible = i == index
            tabs[i].webView.visibility = if (visible) View.VISIBLE else View.GONE
            tabs[i].chip.setBackgroundResource(if (visible) R.color.tab_active else R.color.tab_inactive)
            tabs[i].titleView.setTextColor(getColor(if (visible) R.color.text else R.color.text_dim))
        }
        setUrlBarText(displayUrl(tabs[index].webView.url ?: ""))
        updateNavButtons()
        if (findBar.visibility == View.VISIBLE) closeFind()
    }

    private fun closeTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        val tab = tabs.removeAt(index)
        webContainer.removeView(tab.webView)
        tabBar.removeView(tab.chip)
        tab.webView.destroy()
        if (tabs.isEmpty()) createTab(homeUrl)
        else switchTo(index.coerceAtMost(tabs.size - 1))
    }

    private fun loadUserInput(text: String) {
        val wv = current()?.webView ?: return
        val resolved = DomainUtils.resolveOmniboxInput(text, store.searchTemplate(), homeUrl)
        navigate(wv, resolved)
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
        val wv = current()?.webView
        val back = wv?.canGoBack() == true
        val fwd = wv?.canGoForward() == true
        btnBack.isEnabled = back; btnBack.alpha = if (back) 1f else 0.35f
        btnForward.isEnabled = fwd; btnForward.alpha = if (fwd) 1f else 0.35f
    }

    private fun setupSuggestions() {
        val adapter = SuggestAdapter(this) { suggestionSource() }
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
        val wv = current()?.webView ?: return
        val list = wv.copyBackForwardList()
        if (list.size == 0) { toast("Nothing to go back to yet"); return }
        val items = ArrayList<Pair<String, Int>>()
        for (i in list.size - 1 downTo 0) {
            val it = list.getItemAtIndex(i) ?: continue
            val marker = when {
                i == list.currentIndex -> "• "
                i < list.currentIndex -> "← "
                else -> "→ "
            }
            val title = it.title?.takeIf { t -> t.isNotBlank() } ?: it.url
            items.add("$marker$title" to i)
        }
        AlertDialog.Builder(this)
            .setTitle("Pages in this tab")
            .setItems(items.map { it.first }.toTypedArray()) { _, which ->
                val target = items[which].second
                val steps = target - list.currentIndex
                if (steps != 0 && wv.canGoBackOrForward(steps)) wv.goBackOrForward(steps)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun applyUserAgent(tab: Tab) {
        val s = tab.webView.settings
        s.userAgentString = if (tab.desktop) DESKTOP_UA else WebSettings.getDefaultUserAgent(this)
        s.useWideViewPort = tab.desktop
        s.loadWithOverviewMode = tab.desktop
        tab.webView.setInitialScale(if (tab.desktop) 1 else 0)
        installDesktopSpoof(tab)
    }

    private fun installDesktopSpoof(tab: Tab) {
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) return
        tab.spoofScript?.let { try { it.remove() } catch (_: Exception) {} }
        tab.spoofScript = null
        if (tab.desktop) {
            tab.spoofScript = try {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(tab.webView, DESKTOP_SPOOF_JS, setOf("*"))
            } catch (_: Exception) { null }
        }
    }

    private fun toggleDesktopCurrent() {
        val tab = current() ?: return
        tab.desktop = !tab.desktop
        applyUserAgent(tab)
        tab.webView.reload()
        toast(if (tab.desktop) "Desktop site on" else "Desktop site off")
    }

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) { callback.onCustomViewHidden(); return }
        customView = view
        customViewCallback = callback
        (window.decorView as FrameLayout).addView(
            view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun exitFullscreen() {
        val v = customView ?: return
        (window.decorView as FrameLayout).removeView(v)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun showLinkMenu(webView: WebView): Boolean {
        val hit = webView.hitTestResult
        val link = hit.extra ?: return false
        val isLink = hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
            hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        val isImage = hit.type == WebView.HitTestResult.IMAGE_TYPE ||
            hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        if (!isLink && !isImage) return false
        if (!(link.startsWith("http://") || link.startsWith("https://"))) return false

        val options = mutableListOf<Pair<String, () -> Unit>>()
        options += "Open in new tab" to { createTab(link); Unit }
        if (!isPrivate) options += "Open in private window" to { openPrivateWindow(link) }
        options += "Copy link address" to { copyText(link, "Link copied") }
        options += "Share link" to { shareText(link) }
        if (isImage) options += "Download image" to { startDownload(link, webView.settings.userAgentString, null, null) }

        AlertDialog.Builder(this)
            .setTitle(link.take(80))
            .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
            .show()
        return true
    }

    private fun openPrivateWindow(url: String? = null) {
        val i = Intent(this, PrivateActivity::class.java)
        if (url != null) i.data = Uri.parse(url)
        startActivity(i)
    }

    private fun showMenu() {
        val sheet = BottomSheetDialog(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(if (isPrivate) R.color.private_bar else R.color.toolbar))
            setPadding(0, dp(8), 0, dp(16))
        }
        val scroll = ScrollView(this).apply { addView(col) }

        val tab = current()
        val wv = tab?.webView
        val url = wv?.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val host = url?.let { DomainUtils.extractHostFromUrl(it) }
        val bookmarked = url != null && store.isBookmarked(url)

        fun item(label: String, action: () -> Unit) {
            col.addView(menuRow(label) { sheet.dismiss(); action() })
        }

        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), 0, dp(8), dp(8)) }
        fun quickIcon(res: Int, desc: String, enabled: Boolean = true, tint: Int = R.color.text, action: () -> Unit) {
            quick.addView(ImageButton(this).apply {
                setImageResource(res)
                contentDescription = desc
                imageTintList = android.content.res.ColorStateList.valueOf(getColor(tint))
                background = rippleBg()
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.35f
                layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
                setOnClickListener { sheet.dismiss(); action() }
            })
        }
        quickIcon(R.drawable.ic_back, "Back", wv?.canGoBack() == true) { wv?.goBack() }
        quickIcon(R.drawable.ic_forward, "Forward", wv?.canGoForward() == true) { wv?.goForward() }
        if (!isPrivate) {
            quickIcon(
                if (bookmarked) R.drawable.ic_star else R.drawable.ic_star_border, "Bookmark",
                enabled = url != null, tint = if (bookmarked) R.color.accent else R.color.text
            ) { toggleBookmarkCurrent() }
        }
        quickIcon(R.drawable.ic_refresh, "Reload") { wv?.reload() }
        quickIcon(R.drawable.ic_home, "Home") { wv?.let { loadInto(it, homeUrl) } }
        col.addView(quick)
        col.addView(View(this).apply {
            setBackgroundColor(getColor(R.color.tab_active))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })

        val blockedHere = tab?.blocked?.get() ?: 0
        val adStatus = if (store.shieldsEnabled) "Ad Blocker (${store.adBlockMode.label}) · $blockedHere blocked here" else "Ad Blocker off (tap to enable)"
        item(adStatus) { toggleShields() }
        if (host != null && !isPrivate) {
            val isException = store.isAdBlockException(host)
            item(if (isException) "Resume Ad Blocker on $host" else "Pause Ad Blocker on $host") {
                if (isException) store.removeAdBlockException(host) else store.addAdBlockException(host)
                wv.reload()
                toast(if (isException) "Ad Blocker resumed on $host" else "Ad Blocker paused on $host")
            }
        }
        item(if (isPrivate) "New private tab" else "New tab") { createTab(homeUrl) }
        if (isPrivate) {
            item("Erase & close private window") { closeWindow() }
        } else {
            item("New private window") { openPrivateWindow() }
            item("History") { showHistory() }
            item("Bookmarks") { showBookmarks() }
        }
        item("Cookie Manager") { cookieDialogs.showCookieManager() }
        item("Find in page") { openFind() }
        item(if (tab?.desktop == true) "Desktop site (On)" else "Desktop site") { toggleDesktopCurrent() }
        item("Developer tools") { devDialogs.showDevTools() }
        item("Downloads") { openDownloads() }
        item("Share page") { url?.let { shareText(it) } ?: toast("Open a page first") }
        item("Copy link") { url?.let { copyText(it, "Link copied") } ?: toast("Open a page first") }
        if (!isPrivate) {
            item("Settings") { startActivity(Intent(this, SettingsActivity::class.java)) }
        }
        item("About Pipra") {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SECTION, "ABOUT"))
        }

        sheet.setContentView(scroll)
        sheet.show()
    }

    private fun menuRow(label: String, onClick: () -> Unit): View {
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
            setTextColor(getColor(R.color.text))
        })
        return row
    }

    private fun toggleShields() {
        if (isPrivate) {
            toast(if (store.shieldsEnabled) "Ad Blocker is on (${store.adBlockMode.label})" else "Enable Ad Blocker in normal Settings")
            return
        }
        store.shieldsEnabled = !store.shieldsEnabled
        current()?.webView?.reload()
        toast(if (store.shieldsEnabled) "Ad Blocker on (${store.adBlockMode.label})" else "Ad Blocker off")
    }

    private fun showHistory() {
        val all = store.history()
        if (all.isEmpty()) { toast("No history yet"); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), 0) }
        val search = EditText(this).apply { hint = "Search history"; setSingleLine(true) }
        val list = ListView(this)
        root.addView(search); root.addView(list)

        var shown = all
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        fun render(items: List<HistoryEntry>) {
            shown = items
            val labels = items.map { "${it.title.ifBlank { it.url }}\n${it.url}  ·  ${df.format(Date(it.time))}" }
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        }
        render(all)
        search.addTextChangedListener(simpleWatcher {
            val q = it.lowercase()
            render(if (q.isBlank()) all else all.filter { e -> e.url.lowercase().contains(q) || e.title.lowercase().contains(q) })
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle("History")
            .setView(root)
            .setPositiveButton("Close", null)
            .setNegativeButton("Clear all") { _, _ -> confirm("Clear all history?") { store.clearHistory(); toast("History cleared") } }
            .create()
        list.setOnItemClickListener { _, _, pos, _ ->
            shown.getOrNull(pos)?.let { e ->
                val wv = current()?.webView
                if (wv != null) navigate(wv, e.url) else createTab(e.url)
            }
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showBookmarks() {
        val all = store.bookmarks()
        if (all.isEmpty()) { toast("No bookmarks yet"); return }
        val list = ListView(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_list_item_1,
                all.map { "${it.title.ifBlank { it.url }}\n${it.url}" }
            )
        }
        val dialog = AlertDialog.Builder(this).setTitle("Bookmarks").setView(list)
            .setPositiveButton("Close", null).create()
        list.setOnItemClickListener { _, _, pos, _ ->
            all.getOrNull(pos)?.let { b ->
                val wv = current()?.webView
                if (wv != null) navigate(wv, b.url) else createTab(b.url)
            }
            dialog.dismiss()
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            all.getOrNull(pos)?.let { b ->
                confirm("Remove bookmark?\n${b.url}") { store.removeBookmark(b.url); dialog.dismiss(); toast("Removed") }
            }
            true
        }
        dialog.show()
    }

    private fun toggleBookmarkCurrent() {
        val tab = current() ?: return
        val url = tab.webView.url?.takeIf { it.startsWith("http") } ?: run { toast("Open a page first"); return }
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
        findViewById<ImageButton>(R.id.findPrev).setOnClickListener { current()?.webView?.findNext(false) }
        findViewById<ImageButton>(R.id.findNext).setOnClickListener { current()?.webView?.findNext(true) }
        findViewById<ImageButton>(R.id.findClose).setOnClickListener { closeFind() }
        findInput.addTextChangedListener(simpleWatcher { q -> current()?.webView?.findAllAsync(q) })
        findInput.setOnEditorActionListener { _, _, _ -> current()?.webView?.findNext(true); true }
    }

    private fun openFind() {
        val wv = current()?.webView ?: return
        wv.setFindListener { active, count, _ -> findCount.text = if (count == 0) "0/0" else "${active + 1}/$count" }
        findBar.visibility = View.VISIBLE
        findInput.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeFind() {
        findBar.visibility = View.GONE
        findInput.setText("")
        current()?.webView?.clearMatches()
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
        try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } catch (_: Exception) { toast("No downloads app found") }
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
        val rawName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val fileName = DomainUtils.sanitizeDownloadFileName(rawName)
        val host = DomainUtils.extractHostFromUrl(url) ?: "unknown"

        val perform = {
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimeType)
                if (!isPrivate) {
                    CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("cookie", it) }
                }
                if (userAgent != null) request.addRequestHeader("User-Agent", userAgent)
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                if (!isPrivate) store.addDownload(fileName, url, mimeType ?: "application/octet-stream")
                toast("Downloading $fileName")
            } catch (e: Exception) {
                toast("Download failed: ${e.message}")
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
        AlertDialog.Builder(this).setMessage(message)
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
