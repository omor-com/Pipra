package com.pipra.browser

import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.R

/**
 * Modular Cookie Manager dialogs extracted from MainActivity to keep files clean
 * and maintainable while preserving every CookieIO / CookieManager capability.
 */
internal class BrowserCookieDialogs(
    private val activity: MainActivity,
    private val isPrivate: Boolean,
    private val currentWebView: () -> WebView?,
    private val navigateTo: (WebView, String) -> Unit,
    private val resolveUrl: (String) -> String,
    private val openFileImport: (format: String) -> Unit,
    private val exportFile: (format: String, text: String) -> Unit,
    private val copyText: (String, String) -> Unit,
    private val toast: (String) -> Unit,
    private val confirm: (String, () -> Unit) -> Unit
) {

    fun showCookieManager() {
        val actions = listOf(
            "View cookies for this site",
            "Inject for a site (no visit needed)",
            "Inject from file (JSON)",
            "Inject from file (Netscape)",
            "Paste & inject (current site)",
            "Export as text (copy)",
            "Export to file (JSON)",
            "Export to file (Netscape)",
            "Clear cookies for this site",
            if (isPrivate) "Clear ALL private cookies" else "Clear ALL cookies"
        )
        AlertDialog.Builder(activity)
            .setTitle(if (isPrivate) "Cookie Manager (private jar)" else "Cookie Manager")
            .setItems(actions.toTypedArray()) { _, which ->
                when (which) {
                    0 -> viewCookies()
                    1 -> injectForSite()
                    2 -> openFileImport("json")
                    3 -> openFileImport("netscape")
                    4 -> pasteAndInject()
                    5 -> exportCookiesAsText()
                    6 -> exportCookies("json")
                    7 -> exportCookies("netscape")
                    8 -> clearSiteCookies()
                    9 -> confirm("Clear all cookies in this ${if (isPrivate) "private window" else "browser"}?") {
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                        toast("Cookies cleared")
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun currentHttpUrl(): String? =
        currentWebView()?.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: run { toast("Open a website first"); null }

    private fun viewCookies() {
        val url = currentHttpUrl() ?: return
        val raw = CookieManager.getInstance().getCookie(url)
        val host = Uri.parse(url).host ?: url
        val body = if (raw.isNullOrBlank()) "No cookies for $host" else raw.split(";").joinToString("\n") { it.trim() }
        val tv = TextView(activity).apply {
            text = body
            setTextIsSelectable(true)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            textSize = 13f
        }
        AlertDialog.Builder(activity)
            .setTitle(host)
            .setView(ScrollView(activity).apply { addView(tv) })
            .setPositiveButton("Close", null)
            .show()
    }

    private fun injectForSite() {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val urlField = EditText(activity).apply {
            hint = "Target site, e.g. example.com or https://example.com"
            setSingleLine(true)
            currentWebView()?.url?.takeIf { it.startsWith("http") }?.let { setText(it) }
        }
        val cookieField = EditText(activity).apply {
            hint = "Paste cookies: JSON array, Netscape cookies.txt, or name=value; name2=value2"
            gravity = Gravity.TOP
            minLines = 5
            maxLines = 12
        }
        box.addView(urlField)
        box.addView(cookieField)
        AlertDialog.Builder(activity)
            .setTitle("Inject for a site")
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton("Inject") { _, _ ->
                val target = resolveUrl(urlField.text.toString().trim())
                val host = DomainUtils.extractHostFromUrl(target)
                if (host.isNullOrBlank()) { toast("Enter a valid site"); return@setPositiveButton }
                val text = cookieField.text.toString().trim()
                if (text.isEmpty()) { toast("Nothing to inject"); return@setPositiveButton }
                val fmt = when {
                    text.startsWith("[") || text.startsWith("{") -> "json"
                    text.contains("\t") -> "netscape"
                    else -> "pairs"
                }
                val n = injectCookieText(text, fmt, fallbackDomain = host, secure = target.startsWith("https"))
                if (n > 0) confirm("Injected $n cookie(s) for $host. Open the site now?") {
                    currentWebView()?.let { navigateTo(it, target) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pasteAndInject() {
        val input = EditText(activity).apply {
            hint = "Paste cookies (JSON array or Netscape cookies.txt)"
            gravity = Gravity.TOP
            minLines = 6
            maxLines = 12
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(activity)
            .setTitle("Paste & inject")
            .setView(input)
            .setPositiveButton("Inject") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) { toast("Nothing to inject"); return@setPositiveButton }
                injectCookieText(text, if (text.startsWith("[") || text.startsWith("{")) "json" else "netscape")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun injectCookieText(
        text: String,
        format: String,
        fallbackDomain: String? = null,
        secure: Boolean = false,
        reload: Boolean = fallbackDomain == null
    ): Int {
        val fallback = fallbackDomain
            ?: currentWebView()?.url?.takeIf { it.startsWith("http") }?.let { Uri.parse(it).host }
        val cookies = try {
            when (format) {
                "netscape" -> CookieIO.parseNetscape(text)
                "pairs" -> parsePairs(text, fallback, secure)
                else -> CookieIO.parseJson(text)
            }
        } catch (e: Exception) {
            toast("Could not parse: ${e.message}")
            return 0
        }
        val resolved = cookies.map {
            if (it.domain.isBlank() && fallback != null) it.copy(domain = fallback, secure = it.secure || secure) else it
        }.filter { it.domain.isNotBlank() }
        if (resolved.isEmpty()) { toast("No cookies found"); return 0 }
        val cm = CookieManager.getInstance()
        var ok = 0
        for (c in resolved) {
            try { cm.setCookie(CookieIO.urlFor(c), CookieIO.setCookieString(c)); ok++ } catch (_: Exception) {}
        }
        cm.flush()
        if (reload) currentWebView()?.reload()
        toast("Injected $ok of ${resolved.size} cookie(s)" + if (isPrivate) " into private window" else "")
        return ok
    }

    private fun parsePairs(text: String, domain: String?, secure: Boolean): List<ParsedCookie> {
        if (domain.isNullOrBlank()) return emptyList()
        return text.split("\n", ";").mapNotNull { part ->
            val eq = part.indexOf('='); if (eq <= 0) return@mapNotNull null
            val name = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim()
            if (name.isEmpty()) null else ParsedCookie(name, value, domain, "/", secure, false, null)
        }
    }

    private fun clearSiteCookies() {
        val url = currentHttpUrl() ?: return
        val host = Uri.parse(url).host ?: return
        val raw = CookieManager.getInstance().getCookie(url)
        if (raw.isNullOrBlank()) { toast("No cookies for this site"); return }
        val names = raw.split(";").mapNotNull { it.substringBefore("=").trim().takeIf { n -> n.isNotEmpty() } }.distinct()
        confirm("Clear ${names.size} cookie(s) for $host?") {
            val cm = CookieManager.getInstance()
            val expired = "Thu, 01 Jan 1970 00:00:00 GMT"
            val parts = host.split(".")
            val parent = if (parts.size > 2) parts.drop(1).joinToString(".") else null
            val domains = buildList {
                add(null)
                add(host); add(".$host")
                parent?.let { add(it); add(".$it") }
            }
            val path = Uri.parse(url).path?.substringBeforeLast("/")?.ifBlank { "/" } ?: "/"
            val paths = listOf("/", path).distinct()
            for (name in names) for (dom in domains) for (p in paths) {
                val sb = StringBuilder("$name=; Path=$p; Expires=$expired; Max-Age=0")
                if (dom != null) sb.append("; Domain=$dom")
                if (url.startsWith("https")) sb.append("; Secure")
                try { cm.setCookie(url, sb.toString()) } catch (_: Exception) {}
            }
            cm.flush()
            val remaining = CookieManager.getInstance().getCookie(url)
                ?.split(";")?.count { it.substringBefore("=").trim().isNotEmpty() } ?: 0
            currentWebView()?.reload()
            if (remaining == 0) toast("Cleared all cookies for $host")
            else toast("Cleared ${names.size - remaining} of ${names.size}; $remaining couldn't be removed")
        }
    }

    private fun currentSiteCookies(): List<ParsedCookie> {
        val pageUrl = currentHttpUrl() ?: return emptyList()
        val raw = CookieManager.getInstance().getCookie(pageUrl)
        if (raw.isNullOrBlank()) return emptyList()
        val host = Uri.parse(pageUrl).host ?: ""
        val secure = pageUrl.startsWith("https")
        return raw.split(";").mapNotNull { part ->
            val eq = part.indexOf('='); if (eq <= 0) return@mapNotNull null
            val name = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim()
            if (name.isEmpty()) null else ParsedCookie(name, value, host, "/", secure, false, null)
        }
    }

    private fun exportCookies(format: String) {
        if (currentHttpUrl() == null) return
        val cookies = currentSiteCookies()
        if (cookies.isEmpty()) { toast("No cookies for this site"); return }
        if (format == "netscape") exportFile("netscape", CookieIO.toNetscape(cookies))
        else exportFile("json", CookieIO.toJson(cookies))
    }

    private fun exportCookiesAsText() {
        if (currentHttpUrl() == null) return
        val cookies = currentSiteCookies()
        if (cookies.isEmpty()) { toast("No cookies for this site"); return }
        val formats = listOf("JSON", "Netscape (cookies.txt)")
        AlertDialog.Builder(activity)
            .setTitle("Export as text")
            .setItems(formats.toTypedArray()) { _, which ->
                val text = if (which == 0) CookieIO.toJson(cookies) else CookieIO.toNetscape(cookies)
                val tv = TextView(activity).apply {
                    setText(text)
                    setTextIsSelectable(true)
                    textSize = 12f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setPadding(dp(18), dp(12), dp(18), dp(12))
                }
                AlertDialog.Builder(activity)
                    .setTitle("${cookies.size} cookie(s) · ${formats[which]}")
                    .setView(ScrollView(activity).apply { addView(tv) })
                    .setPositiveButton("Copy") { _, _ -> copyText(text, "Cookies copied to clipboard") }
                    .setNegativeButton("Close", null)
                    .show()
            }
            .show()
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
}
