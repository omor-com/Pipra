package com.pipra.browser

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import okhttp3.OkHttpClient
import okhttp3.Request
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class TabGroupPreset(val label: String, val colorHex: Int) {
    NONE("No Group", 0x00000000),
    WORK("Work", 0xFF2563EB.toInt()),
    RESEARCH("Research", 0xFF16A34A.toInt()),
    SHOPPING("Shopping", 0xFFD97706.toInt()),
    READING("Reading", 0xFF9333EA.toInt())
}

data class BrowserTabSummary(
    val index: Int,
    val title: String,
    val url: String,
    val isCurrent: Boolean,
    val blockedCount: Int,
    val groupName: String,
    val groupColorHex: Int
)

object BrowserV4Dialogs {

    private val readerHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun showTabSwitcherSheet(
        activity: Activity,
        themedCtx: Context,
        tabs: List<BrowserTabSummary>,
        closedCount: Int,
        isPrivate: Boolean,
        isDark: Boolean,
        onSelectTab: (Int) -> Unit,
        onCloseTab: (Int) -> Unit,
        onSetTabGroup: (Int, TabGroupPreset) -> Unit,
        onNewTab: () -> Unit,
        onNewPrivateWindow: () -> Unit,
        onReopenClosed: () -> Unit,
        onCloseOthers: () -> Unit
    ) {
        val bg = activity.getColor(if (isPrivate) R.color.private_dark else if (isDark) R.color.pipra_dark_bg else R.color.pipra_light_bg)
        val cardBg = activity.getColor(if (isPrivate) R.color.private_bar else if (isDark) R.color.pipra_dark_card else R.color.pipra_light_card)
        val text = activity.getColor(if (isDark || isPrivate) R.color.pipra_dark_text else R.color.pipra_light_text)
        val textDim = activity.getColor(if (isDark || isPrivate) R.color.pipra_dark_text_dim else R.color.pipra_light_text_dim)
        val border = activity.getColor(if (isDark || isPrivate) R.color.pipra_dark_border else R.color.pipra_light_border)
        val accent = activity.getColor(if (isDark || isPrivate) R.color.pipra_dark_accent else R.color.pipra_light_accent)

        val sheet = BottomSheetDialog(themedCtx)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 20))
        }

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(activity, 10))
        }
        headerRow.addView(TextView(activity).apply {
            this.text = if (isPrivate) "Private Tabs (${tabs.size})" else "Open Tabs (${tabs.size})"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(text)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (closedCount > 0) {
            headerRow.addView(chipButton(activity, "Reopen ($closedCount)", cardBg, accent, border) {
                sheet.dismiss()
                onReopenClosed()
            }.apply {
                (layoutParams as? ViewGroup.MarginLayoutParams)?.marginEnd = dp(activity, 6)
            })
        }
        if (tabs.size > 1) {
            headerRow.addView(chipButton(activity, "Close Others", cardBg, textDim, border) {
                sheet.dismiss()
                onCloseOthers()
            })
        }
        root.addView(headerRow)

        val actionRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(activity, 12))
        }
        actionRow.addView(Button(themedCtx).apply {
            this.text = if (isPrivate) "+ New Private Tab" else "+ New Tab"
            layoutParams = LinearLayout.LayoutParams(0, dp(activity, 48), 1f).apply {
                marginEnd = if (!isPrivate) dp(activity, 8) else 0
            }
            setOnClickListener {
                sheet.dismiss()
                onNewTab()
            }
        })
        if (!isPrivate) {
            actionRow.addView(Button(themedCtx).apply {
                this.text = "Private Window"
                layoutParams = LinearLayout.LayoutParams(0, dp(activity, 48), 1f)
                setOnClickListener {
                    sheet.dismiss()
                    onNewPrivateWindow()
                }
            })
        }
        root.addView(actionRow)

        var selectedGroupFilter = "All"
        val activeGroups = tabs.map { it.groupName }.filter { it.isNotBlank() }.distinct()
        val cardsHost = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        fun rebuildGrid() {
            cardsHost.removeAllViews()
            val visible = if (selectedGroupFilter == "All") tabs else tabs.filter { it.groupName == selectedGroupFilter }
            for (i in visible.indices step 2) {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(activity, 10) }
                }
                for (colIdx in 0..1) {
                    val item = visible.getOrNull(i + colIdx)
                    if (item == null) {
                        row.addView(View(activity).apply {
                            layoutParams = LinearLayout.LayoutParams(0, dp(activity, 1), 1f)
                        })
                    } else {
                        val card = buildTabCard(
                            activity = activity,
                            themedCtx = themedCtx,
                            item = item,
                            cardBg = cardBg,
                            text = text,
                            textDim = textDim,
                            border = border,
                            accent = accent,
                            onSelect = {
                                sheet.dismiss()
                                onSelectTab(item.index)
                            },
                            onClose = {
                                sheet.dismiss()
                                onCloseTab(item.index)
                            },
                            onChangeGroup = { preset ->
                                onSetTabGroup(item.index, preset)
                                sheet.dismiss()
                            }
                        )
                        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        if (colIdx == 0) lp.marginEnd = dp(activity, 8)
                        card.layoutParams = lp
                        row.addView(card)
                    }
                }
                cardsHost.addView(row)
            }
        }

        if (activeGroups.isNotEmpty()) {
            val filterScroll = HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(0, 0, 0, dp(activity, 10))
            }
            val filterRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val choices = listOf("All") + activeGroups
            choices.forEach { grp ->
                filterRow.addView(chipButton(activity, grp, cardBg, text, border) {
                    selectedGroupFilter = grp
                    rebuildGrid()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(activity, 36)
                    ).apply { marginEnd = dp(activity, 6) }
                })
            }
            filterScroll.addView(filterRow)
            root.addView(filterScroll)
        }

        rebuildGrid()
        val scroll = ScrollView(activity).apply {
            addView(cardsHost)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(scroll)

        sheet.setContentView(root)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.show()
    }

    private fun buildTabCard(
        activity: Activity,
        themedCtx: Context,
        item: BrowserTabSummary,
        cardBg: Int,
        text: Int,
        textDim: Int,
        border: Int,
        accent: Int,
        onSelect: () -> Unit,
        onClose: () -> Unit,
        onChangeGroup: (TabGroupPreset) -> Unit
    ): View {
        val strokeColor = when {
            item.isCurrent -> accent
            item.groupColorHex != 0 -> item.groupColorHex
            else -> border
        }
        val strokeWidth = if (item.isCurrent || item.groupColorHex != 0) dp(activity, 2) else dp(activity, 1)

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 8), dp(activity, 10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(activity, 14).toFloat()
                setColor(cardBg)
                setStroke(strokeWidth, strokeColor)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onSelect() }
        }

        val topRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val groupLabel = item.groupName.ifBlank { "+ Group" }
        val groupTint = if (item.groupColorHex != 0) item.groupColorHex else textDim
        topRow.addView(TextView(activity).apply {
            this.text = groupLabel
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(groupTint)
            setPadding(dp(activity, 6), dp(activity, 4), dp(activity, 6), dp(activity, 4))
            minHeight = dp(activity, 32)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                val presets = TabGroupPreset.entries
                AlertDialog.Builder(themedCtx)
                    .setTitle("Assign Tab Group")
                    .setItems(presets.map { it.label }.toTypedArray()) { _, which ->
                        onChangeGroup(presets[which])
                    }
                    .show()
            }
        })
        topRow.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        topRow.addView(TextView(activity).apply {
            this.text = "✕"
            textSize = 14f
            setTextColor(textDim)
            gravity = Gravity.CENTER
            minimumWidth = dp(activity, 48)
            minimumHeight = dp(activity, 48)
            contentDescription = "Close tab"
            setOnClickListener { onClose() }
        })
        card.addView(topRow)

        card.addView(TextView(activity).apply {
            this.text = item.title.ifBlank { "New Tab" }
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(text)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(activity, 2), 0, dp(activity, 4))
        })

        val hostLabel = DomainUtils.extractHostFromUrl(item.url)
            ?: if (item.url == Store.DEFAULT_HOMEPAGE) "pipra://home" else item.url.ifBlank { "Start Page" }
        card.addView(TextView(activity).apply {
            this.text = hostLabel
            textSize = 12f
            setTextColor(textDim)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })

        if (item.blockedCount > 0 || item.isCurrent) {
            val statusText = buildString {
                if (item.isCurrent) append("Active")
                if (item.isCurrent && item.blockedCount > 0) append(" · ")
                if (item.blockedCount > 0) append("🛡 ${item.blockedCount}")
            }
            card.addView(TextView(activity).apply {
                this.text = statusText
                textSize = 11f
                setTextColor(if (item.isCurrent) accent else textDim)
                setPadding(0, dp(activity, 6), 0, 0)
            })
        }

        return card
    }

    fun showExtensionInstallConsentDialog(
        activity: Activity,
        themedCtx: Context,
        extension: WebExtension,
        permissions: List<String>,
        origins: List<String>,
        onDecision: (granted: Boolean, allowPrivate: Boolean) -> Unit
    ) {
        val completed = AtomicBoolean(false)
        fun completeOnce(granted: Boolean, allowPrivate: Boolean) {
            if (completed.compareAndSet(false, true)) {
                onDecision(granted, allowPrivate)
            }
        }

        val name = extension.metaData.name?.takeIf { it.isNotBlank() } ?: extension.id
        val version = extension.metaData.version?.takeIf { it.isNotBlank() } ?: "1.0"
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 4))
        }
        val permSummary = buildString {
            append("Version $version\n\n")
            if (permissions.isEmpty() && origins.isEmpty()) {
                append("No special site permissions requested.")
            } else {
                if (permissions.isNotEmpty()) {
                    append("Permissions:\n")
                    permissions.take(8).forEach { append("• $it\n") }
                }
                if (origins.isNotEmpty()) {
                    append("\nWebsite access:\n")
                    origins.take(6).forEach { append("• $it\n") }
                }
            }
        }
        container.addView(TextView(activity).apply {
            text = permSummary.trim()
            textSize = 13f
        })
        val allowPrivateCheck = CheckBox(themedCtx).apply {
            text = "Allow extension in Private Windows"
            isChecked = false
            setPadding(0, dp(activity, 10), 0, 0)
        }
        container.addView(allowPrivateCheck)

        val dialog = AlertDialog.Builder(themedCtx)
            .setTitle("Install \"$name\"?")
            .setView(container)
            .setPositiveButton("Install") { _, _ ->
                completeOnce(true, allowPrivateCheck.isChecked)
            }
            .setNegativeButton("Cancel") { _, _ ->
                completeOnce(false, false)
            }
            .create()
        dialog.setOnDismissListener {
            completeOnce(false, false)
        }
        dialog.show()
    }

    fun showExtensionPopupDialog(
        activity: Activity,
        themedCtx: Context,
        extension: WebExtension,
        popupSession: GeckoSession
    ) {
        val title = extension.metaData.name?.takeIf { it.isNotBlank() } ?: extension.id
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val gv = GeckoView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 420)
            )
            setSession(popupSession)
        }
        container.addView(gv)

        val dialog = AlertDialog.Builder(themedCtx)
            .setTitle(title)
            .setView(container)
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnDismissListener {
            try { gv.releaseSession() } catch (_: Exception) {}
            try { popupSession.close() } catch (_: Exception) {}
        }
        dialog.show()
    }

    fun showReaderModeSheet(
        activity: Activity,
        themedCtx: Context,
        url: String,
        fallbackTitle: String,
        userAgent: String,
        cookieHeader: String?,
        store: Store,
        onSavePdf: () -> Unit,
        toast: (String) -> Unit
    ) {
        val sheet = BottomSheetDialog(themedCtx)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 8))
        }
        val titleLabel = TextView(activity).apply {
            text = "Reader Mode"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(titleLabel)

        val btnFontMinus = Button(themedCtx).apply { text = "A-" }
        val btnFontPlus = Button(themedCtx).apply { text = "A+" }
        val btnThemeCycle = Button(themedCtx)
        val btnPdf = Button(themedCtx).apply { text = "PDF" }
        listOf(btnFontMinus, btnFontPlus, btnThemeCycle, btnPdf).forEach { btn ->
            btn.minWidth = dp(activity, 48)
            btn.minimumHeight = dp(activity, 48)
            btn.setPadding(dp(activity, 8), 0, dp(activity, 8), 0)
            topBar.addView(btn)
        }
        root.addView(topBar)

        val spinner = ProgressBar(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(activity, 32)
                bottomMargin = dp(activity, 32)
            }
        }
        root.addView(spinner)

        val scroll = ScrollView(activity).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 520)
            )
        }
        val articleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 28))
        }
        scroll.addView(articleCol)
        root.addView(scroll)

        var currentArticle: ReaderArticle? = null

        fun applyReaderAppearance() {
            val article = currentArticle ?: return
            val theme = store.readerTheme
            val fontSp = store.readerFontSizeSp
            val (bgColor, fgColor, mutedColor, accentColor) = when (theme) {
                "dark" -> listOf(Color.parseColor("#0F1115"), Color.parseColor("#E5E7EB"), Color.parseColor("#9CA3AF"), Color.parseColor("#60A5FA"))
                "light" -> listOf(Color.parseColor("#FFFFFF"), Color.parseColor("#111827"), Color.parseColor("#4B5563"), Color.parseColor("#2563EB"))
                else -> listOf(Color.parseColor("#F5EEDC"), Color.parseColor("#3B2F20"), Color.parseColor("#6B5B45"), Color.parseColor("#92400E"))
            }
            root.setBackgroundColor(bgColor)
            titleLabel.setTextColor(fgColor)
            btnThemeCycle.text = theme.replaceFirstChar { it.uppercase() }

            articleCol.removeAllViews()
            articleCol.addView(TextView(activity).apply {
                text = "${article.host} · ${article.estimatedMinutes} min read (${article.wordCount} words)"
                textSize = 12f
                setTextColor(mutedColor)
                setPadding(0, 0, 0, dp(activity, 10))
            })
            articleCol.addView(TextView(activity).apply {
                text = article.title
                textSize = (fontSp + 6).toFloat()
                setTypeface(null, Typeface.BOLD)
                setTextColor(fgColor)
                setPadding(0, 0, 0, dp(activity, 14))
            })
            article.paragraphs.forEach { p ->
                val isHeading = p.startsWith("## ")
                articleCol.addView(TextView(activity).apply {
                    text = if (isHeading) p.removePrefix("## ") else p
                    textSize = if (isHeading) (fontSp + 2).toFloat() else fontSp.toFloat()
                    typeface = if (isHeading) Typeface.DEFAULT_BOLD else Typeface.SERIF
                    setTextColor(if (isHeading) accentColor else fgColor)
                    setLineSpacing(0f, 1.55f)
                    setPadding(0, if (isHeading) dp(activity, 10) else 0, 0, dp(activity, 12))
                })
            }
        }

        btnThemeCycle.text = store.readerTheme.replaceFirstChar { it.uppercase() }
        btnThemeCycle.setOnClickListener {
            store.readerTheme = when (store.readerTheme) {
                "sepia" -> "light"
                "light" -> "dark"
                else -> "sepia"
            }
            applyReaderAppearance()
        }
        btnFontMinus.setOnClickListener {
            store.readerFontSizeSp = (store.readerFontSizeSp - 2).coerceAtLeast(14)
            applyReaderAppearance()
        }
        btnFontPlus.setOnClickListener {
            store.readerFontSizeSp = (store.readerFontSizeSp + 2).coerceAtMost(28)
            applyReaderAppearance()
        }
        btnPdf.setOnClickListener {
            sheet.dismiss()
            onSavePdf()
        }

        sheet.setContentView(root)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.show()

        Thread {
            val html = try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .apply { if (!cookieHeader.isNullOrBlank()) header("Cookie", cookieHeader) }
                    .build()
                readerHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null
                    else resp.body?.string()?.take(1_500_000)
                }
            } catch (_: Exception) {
                null
            }
            activity.runOnUiThread {
                if (!sheet.isShowing) return@runOnUiThread
                spinner.visibility = View.GONE
                scroll.visibility = View.VISIBLE
                if (html.isNullOrBlank()) {
                    toast("Could not load article text")
                    currentArticle = ReaderArticle(
                        title = fallbackTitle.ifBlank { url },
                        host = DomainUtils.extractHostFromUrl(url) ?: "Webpage",
                        url = url,
                        paragraphs = listOf("Unable to extract article text for this page offline or behind a protected endpoint."),
                        wordCount = 14
                    )
                } else {
                    currentArticle = ReaderModeHelper.extractFromHtml(html, url, fallbackTitle)
                }
                applyReaderAppearance()
            }
        }.start()
    }

    fun showAppLockGateDialog(
        activity: Activity,
        themedCtx: Context,
        appLock: AppLockManager,
        scopeTitle: String,
        onUnlocked: () -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        val cfg = appLock.config()
        if (!cfg.hasPin) {
            onUnlocked()
            return
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 6))
        }
        val statusView = TextView(activity).apply {
            text = "Enter your 4–8 digit Pipra PIN to continue."
            textSize = 13f
            setPadding(0, 0, 0, dp(activity, 10))
        }
        val pinInput = EditText(themedCtx).apply {
            hint = "PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine(true)
        }
        container.addView(statusView)
        container.addView(pinInput)

        val builder = AlertDialog.Builder(themedCtx)
            .setTitle(scopeTitle)
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Unlock", null)
        if (onCancel != null) {
            builder.setNegativeButton("Cancel") { _, _ -> onCancel() }
        }
        if (cfg.allowDeviceCredential && Build.VERSION.SDK_INT >= 29) {
            val km = activity.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (km?.isDeviceSecure == true) {
                builder.setNeutralButton("Device Lock", null)
            }
        }

        val dialog = builder.create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()

        dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
            when (val res = appLock.verifyPin(pinInput.text.toString())) {
                is PinVerifyResult.Success -> {
                    dialog.dismiss()
                    onUnlocked()
                }
                is PinVerifyResult.Invalid -> {
                    pinInput.setText("")
                    statusView.setTextColor(Color.parseColor("#DC2626"))
                    statusView.text = "Incorrect PIN. ${res.remainingAttempts} attempt(s) remaining before 30s lockout."
                }
                is PinVerifyResult.LockedOut -> {
                    pinInput.setText("")
                    statusView.setTextColor(Color.parseColor("#DC2626"))
                    statusView.text = "Too many attempts. Try again in ${res.remainingSeconds}s."
                }
            }
        }

        if (cfg.allowDeviceCredential && Build.VERSION.SDK_INT >= 29) {
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
                launchDeviceBiometric(activity, scopeTitle, onSuccess = {
                    if (dialog.isShowing) dialog.dismiss()
                    onUnlocked()
                }, onError = { msg ->
                    statusView.setTextColor(Color.parseColor("#DC2626"))
                    statusView.text = msg
                })
            }
        }
    }

    private fun launchDeviceBiometric(
        activity: Activity,
        title: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < 29) {
            onError("Device authentication requires Android 10 or newer.")
            return
        }
        try {
            val promptBuilder = BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setSubtitle("Authenticate to unlock Pipra")
            if (Build.VERSION.SDK_INT >= 30) {
                promptBuilder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
            } else {
                @Suppress("DEPRECATION")
                promptBuilder.setDeviceCredentialAllowed(true)
            }
            val prompt = promptBuilder.build()
            prompt.authenticate(
                CancellationSignal(),
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        activity.runOnUiThread { onSuccess() }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        activity.runOnUiThread {
                            onError(errString?.toString() ?: "Authentication canceled.")
                        }
                    }
                }
            )
        } catch (e: Exception) {
            onError(e.message ?: "Device unlock unavailable.")
        }
    }

    fun showSitePermissionPrompt(
        activity: Activity,
        themedCtx: Context,
        host: String,
        type: SitePermissionType,
        isPrivate: Boolean,
        onResult: (allowed: Boolean, remember: Boolean) -> Unit
    ) {
        val completed = AtomicBoolean(false)
        fun finishOnce(allowed: Boolean, remember: Boolean) {
            if (completed.compareAndSet(false, true)) {
                onResult(allowed, remember && !isPrivate)
            }
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 4))
        }
        container.addView(TextView(activity).apply {
            text = "$host wants access to ${type.label}."
            textSize = 14f
        })
        val rememberBox = CheckBox(themedCtx).apply {
            text = "Remember decision for $host"
            isChecked = !isPrivate
            visibility = if (isPrivate) View.GONE else View.VISIBLE
            setPadding(0, dp(activity, 10), 0, 0)
        }
        container.addView(rememberBox)

        val dialog = AlertDialog.Builder(themedCtx)
            .setTitle("${type.label} Permission")
            .setView(container)
            .setPositiveButton("Allow") { _, _ -> finishOnce(true, rememberBox.isChecked) }
            .setNegativeButton("Block") { _, _ -> finishOnce(false, rememberBox.isChecked) }
            .create()
        dialog.setOnDismissListener { finishOnce(false, false) }
        dialog.show()
    }

    private fun chipButton(
        activity: Activity,
        label: String,
        bgColor: Int,
        textColor: Int,
        borderColor: Int,
        onClick: () -> Unit
    ): TextView {
        return TextView(activity).apply {
            text = label
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            gravity = Gravity.CENTER
            minHeight = dp(activity, 36)
            setPadding(dp(activity, 12), dp(activity, 6), dp(activity, 12), dp(activity, 6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(activity, 999).toFloat()
                setColor(bgColor)
                setStroke(dp(activity, 1), borderColor)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
}
