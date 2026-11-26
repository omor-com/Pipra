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
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.R
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Modular Developer Tools & Burp-style Workbench dialogs extracted from MainActivity.
 * Preserves ModHeader-style header rules, Requestly-style request rules, Network Inspector,
 * HAR/cURL export, Repeater, Passive Scanner, Target & Scope, Encoder/Decoder, Comparer,
 * Sequencer, Storage Editor, View Source, and Quick Toggles.
 */
internal class BrowserDevDialogs(
    private val activity: MainActivity,
    private val isPrivate: Boolean,
    private val store: Store,
    private val dev: DevStore,
    private val currentWebView: () -> WebView?,
    private val applySettingsToTabs: () -> Unit,
    private val copyText: (String, String) -> Unit,
    private val toast: (String) -> Unit,
    private val confirm: (String, () -> Unit) -> Unit,
    private val isBlockedDestination: (String) -> Boolean = { false }
) {

    fun showDevTools() {
        val sheet = BottomSheetDialog(activity)
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(activity.getColor(if (isPrivate) R.color.private_bar else R.color.toolbar))
            setPadding(0, dp(8), 0, dp(16))
        }
        col.addView(TextView(activity).apply {
            text = "Developer tools"
            textSize = 18f
            setTextColor(activity.getColor(R.color.text))
            setPadding(dp(20), dp(12), dp(20), dp(6))
        })
        val masterRow = switchRow("Rules enabled (master switch)", dev.devToolsEnabled)
        (masterRow.first as LinearLayout).setPadding(dp(20), dp(4), dp(20), dp(8))
        masterRow.second.setOnCheckedChangeListener { _, c -> dev.devToolsEnabled = c }
        col.addView(masterRow.first)
        col.addView(sep())

        fun row(label: String, sub: String, action: () -> Unit) {
            col.addView(menuRow2(label, sub) { sheet.dismiss(); action() })
        }
        row("Header rules", "Add, change or remove request/response headers") { showHeaderRules() }
        row("Request rules", "Block, redirect, rewrite, query/URL edits, status, map-local, delay") { showRequestRules() }
        row("Security workbench", "HTTP history, Repeater, Passive scanner, Encoder/Decoder, Comparer") { showWorkbench() }
        row("Network inspector", "Inspect requests captured in this session") { showNetworkInspector() }
        row("Storage editor", "View & edit localStorage / sessionStorage") { showStorageEditor() }
        row("View page source", "Rendered HTML of the current page") { viewSource() }
        row("Quick toggles", "JavaScript, images, cookies on/off") { showQuickToggles() }

        sheet.setContentView(ScrollView(activity).apply { addView(col) })
        sheet.show()
    }

    private fun showWorkbench() {
        val sheet = BottomSheetDialog(activity)
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(activity.getColor(if (isPrivate) R.color.private_bar else R.color.toolbar))
            setPadding(0, dp(8), 0, dp(16))
        }
        col.addView(TextView(activity).apply {
            text = "Security workbench"
            textSize = 18f
            setTextColor(activity.getColor(R.color.text))
            setPadding(dp(20), dp(12), dp(20), dp(2))
        })
        col.addView(TextView(activity).apply {
            text = "Manual inspection of traffic from this browser. Not a system-wide proxy."
            textSize = 12f
            setTextColor(activity.getColor(R.color.text_dim))
            setPadding(dp(20), 0, dp(20), dp(6))
        })
        col.addView(sep())
        fun row(label: String, sub: String, action: () -> Unit) {
            col.addView(menuRow2(label, sub) { sheet.dismiss(); action() })
        }
        row("HTTP history", "Requests made by this browser — send to Repeater, export HAR / cURL") { showNetworkInspector() }
        row("Repeater", "Edit method, URL, headers, or body and resend") { showRepeater("GET", currentWebView()?.url ?: "https://", "", "") }
        row("Passive scanner", "Read-only checks: security headers, cookie flags, mixed content") { showScanner() }
        row("Target & scope", "Limit tools to authorized hosts") { showScope() }
        row("Encoder / Decoder", "Base64, URL, HTML, hex, JWT, hashes, JSON, unicode, timestamp") { showDecoder() }
        row("Comparer", "Diff two blocks of text") { showComparer() }
        row("Sequencer", "Rough randomness stats for sample tokens") { showSequencer() }
        sheet.setContentView(ScrollView(activity).apply { addView(col) })
        sheet.show()
    }

    private fun showHeaderRules() {
        val rules = dev.headerRules()
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), 0) }
        val list = ListView(activity)
        fun labels() = rules.map {
            val dir = if (it.onResponse) "response" else "request"
            val op = if (it.remove) "remove" else "set"
            "${if (it.enabled) "[ON]" else "[OFF]"} $op $dir: ${it.header.ifBlank { "(name?)" }}" +
                (if (!it.remove) " = ${it.value}" else "") + "\n   url: ${it.urlPattern}"
        }
        fun refresh() { list.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, labels()) }
        refresh()
        root.addView(list)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Header rules")
            .setView(root)
            .setPositiveButton("Close", null)
            .setNeutralButton("Add rule", null)
            .create()
        list.setOnItemClickListener { _, _, pos, _ -> editHeaderRule(rules[pos]) { rules[pos] = it; dev.saveHeaderRules(rules); refresh() } }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            confirm("Delete this header rule?") { rules.removeAt(pos); dev.saveHeaderRules(rules); refresh() }; true
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                editHeaderRule(HeaderRule()) { rules.add(0, it); dev.saveHeaderRules(rules); refresh() }
            }
        }
        dialog.show()
    }

    private fun editHeaderRule(rule: HeaderRule, onSave: (HeaderRule) -> Unit) {
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val enabled = switchRow("Enabled", rule.enabled); col.addView(enabled.first)
        val onResp = switchRow("Apply to response (off = request)", rule.onResponse); col.addView(onResp.first)
        val remove = switchRow("Remove header (off = set)", rule.remove); col.addView(remove.first)
        col.addView(label("URL pattern (* wildcards, blank = all)"))
        val pat = EditText(activity).apply { setText(rule.urlPattern); setSingleLine(true) }; col.addView(pat)
        col.addView(label("Header name"))
        val name = EditText(activity).apply { setText(rule.header); setSingleLine(true); hint = "e.g. Authorization" }; col.addView(name)
        col.addView(label("Header value"))
        val value = EditText(activity).apply { setText(rule.value); setSingleLine(true); hint = "e.g. Bearer token" }; col.addView(value)
        AlertDialog.Builder(activity)
            .setTitle("Header rule")
            .setView(ScrollView(activity).apply { addView(col) })
            .setPositiveButton("Save") { _, _ ->
                rule.enabled = enabled.second.isChecked
                rule.onResponse = onResp.second.isChecked
                rule.remove = remove.second.isChecked
                rule.urlPattern = pat.text.toString().ifBlank { "*" }
                rule.header = name.text.toString().trim()
                rule.value = value.text.toString()
                if (rule.header.isBlank()) { toast("Header name is required"); return@setPositiveButton }
                onSave(rule)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRequestRules() {
        val rules = dev.requestRules()
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), 0) }
        val list = ListView(activity)
        fun labels() = rules.map {
            "${if (it.enabled) "[ON]" else "[OFF]"} ${it.action.uppercase()} ${it.urlPattern}"
        }
        fun refresh() { list.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, labels()) }
        refresh(); root.addView(list)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Request rules")
            .setView(root)
            .setPositiveButton("Close", null)
            .setNeutralButton("Add rule", null)
            .create()
        list.setOnItemClickListener { _, _, pos, _ -> editRequestRule(rules[pos]) { rules[pos] = it; dev.saveRequestRules(rules); refresh() } }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            confirm("Delete this request rule?") { rules.removeAt(pos); dev.saveRequestRules(rules); refresh() }; true
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                editRequestRule(RequestRule()) { rules.add(0, it); dev.saveRequestRules(rules); refresh() }
            }
        }
        dialog.show()
    }

    private fun editRequestRule(rule: RequestRule, onSave: (RequestRule) -> Unit) {
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val enabled = switchRow("Enabled", rule.enabled); col.addView(enabled.first)
        col.addView(label("Action"))
        val actions = listOf(
            RequestRule.ACTION_BLOCK, RequestRule.ACTION_REDIRECT, RequestRule.ACTION_REPLACE,
            RequestRule.ACTION_INSERT, RequestRule.ACTION_QUERY, RequestRule.ACTION_URLREPLACE,
            RequestRule.ACTION_STATUS, RequestRule.ACTION_MAPLOCAL, RequestRule.ACTION_DELAY
        )
        val actionNames = listOf(
            "Block request", "Redirect to URL", "Replace text in response", "Inject JS/CSS",
            "Modify query params", "Replace text in URL", "Override status code",
            "Map local (custom response body)", "Delay request"
        )
        val spinner = android.widget.Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, actionNames)
            setSelection(actions.indexOf(rule.action).coerceAtLeast(0))
        }
        col.addView(spinner)
        col.addView(label("URL pattern (* wildcards)"))
        val pat = EditText(activity).apply { setText(rule.urlPattern); setSingleLine(true) }; col.addView(pat)
        col.addView(label("Target / find text / code"))
        val target = EditText(activity).apply { setText(rule.target); minLines = 1; maxLines = 6 }; col.addView(target)
        col.addView(label("Replace with"))
        val repl = EditText(activity).apply { setText(rule.replaceWith); minLines = 1; maxLines = 4 }; col.addView(repl)
        val css = switchRow("Inject as CSS (off = JS)", rule.isCss); col.addView(css.first)
        AlertDialog.Builder(activity)
            .setTitle("Request rule")
            .setView(ScrollView(activity).apply { addView(col) })
            .setPositiveButton("Save") { _, _ ->
                rule.enabled = enabled.second.isChecked
                rule.action = actions[spinner.selectedItemPosition]
                rule.urlPattern = pat.text.toString().trim()
                rule.target = target.text.toString()
                rule.replaceWith = repl.text.toString()
                rule.isCss = css.second.isChecked
                if (rule.urlPattern.isBlank()) { toast("URL pattern is required"); return@setPositiveButton }
                onSave(rule)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNetworkInspector() {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), 0) }
        val search = EditText(activity).apply { setSingleLine(true); hint = "filter: host, method, status or tag"; textSize = 13f }
        root.addView(search)
        val list = ListView(activity)
        var filtered = NetLog.snapshot()
        fun render() {
            val q = search.text.toString().trim().lowercase()
            filtered = NetLog.snapshot().filter {
                q.isEmpty() || it.url.lowercase().contains(q) || it.method.lowercase().contains(q) ||
                    it.status.toString() == q || it.note.lowercase().contains(q)
            }
            list.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, filtered.map {
                val tag = if (it.note.isBlank()) "" else "  [${it.note}]"
                "${it.method} ${if (it.status > 0) it.status else ""}$tag\n${it.url}"
            })
        }
        render(); root.addView(list)
        search.addTextChangedListener(activity.simpleWatcher { render() })
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Network / HTTP history")
            .setView(root)
            .setPositiveButton("Close", null)
            .setNeutralButton("Export HAR", null)
            .setNegativeButton("Clear") { _, _ -> NetLog.clear(); toast("Cleared") }
            .create()
        list.setOnItemClickListener { _, _, pos, _ -> filtered.getOrNull(pos)?.let { showNetEntry(it) } }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                copyText(HarExport.toHar(NetLog.snapshot()), "HAR copied to clipboard")
            }
        }
        dialog.show()
    }

    private fun showNetEntry(e: NetEntry) {
        val headers = e.reqHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        val body = "${e.method} ${e.url}\n" +
            (if (e.status > 0) "Status: ${e.status}\n" else "") +
            (if (e.note.isNotBlank()) "Note: ${e.note}\n" else "") +
            "\nRequest headers:\n${headers.ifBlank { "(none captured)" }}" +
            "\n\nAs cURL:\n${HarExport.curl(e)}"
        val tv = TextView(activity).apply {
            text = body; setTextIsSelectable(true); setPadding(dp(16), dp(12), dp(16), dp(12))
            textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE
        }
        AlertDialog.Builder(activity)
            .setTitle("Request detail")
            .setView(ScrollView(activity).apply { addView(tv) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Send to Repeater") { _, _ -> showRepeater(e.method, e.url, headers, "") }
            .setNegativeButton("Copy cURL") { _, _ -> copyText(HarExport.curl(e), "cURL copied") }
            .show()
    }

    private fun showScanner() {
        val url = currentWebView()?.url
        if (url?.startsWith("http") != true) { toast("Open a website first"); return }
        if (isBlockedDestination(url)) { toast("Blocked by Pipra rules"); return }
        if (!dev.inScope(url)) { toast("This host is out of scope — add it in Target & scope"); return }
        val out = TextView(activity).apply {
            text = "Scanning $url …"; setTextIsSelectable(true); textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE; setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextColor(activity.getColor(R.color.text_dim))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Passive scan")
            .setView(ScrollView(activity).apply { addView(out) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy", null)
            .create()
        Thread {
            val findings = Scanner.scan(url)
            val text = findings.joinToString("\n\n") {
                "[${it.severity}] ${it.title}\n  ${it.evidence}\n  Fix: ${it.remediation}"
            }
            activity.runOnUiThread {
                out.setTextColor(activity.getColor(R.color.text)); out.text = text
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { copyText(text, "Scan copied") }
            }
        }.start()
        dialog.show()
    }

    private fun showScope() {
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        col.addView(label("In-scope hosts (one * pattern per line, blank = all)"))
        val inc = EditText(activity).apply { setText(dev.scopeInclude); minLines = 3; maxLines = 6; typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f }
        col.addView(inc)
        col.addView(label("Excluded hosts (always out of scope)"))
        val exc = EditText(activity).apply { setText(dev.scopeExclude); minLines = 2; maxLines = 5; typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f }
        col.addView(exc)
        AlertDialog.Builder(activity)
            .setTitle("Target & scope")
            .setView(ScrollView(activity).apply { addView(col) })
            .setPositiveButton("Save") { _, _ -> dev.scopeInclude = inc.text.toString(); dev.scopeExclude = exc.text.toString(); toast("Scope saved") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSequencer() {
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        col.addView(label("Paste tokens, one per line"))
        val input = EditText(activity).apply { minLines = 5; maxLines = 12; typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f }
        col.addView(input)
        val out = TextView(activity).apply {
            setTextIsSelectable(true); textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(activity.getColor(R.color.text)); setPadding(0, dp(10), 0, 0)
        }
        col.addView(out)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Sequencer")
            .setView(ScrollView(activity).apply { addView(col) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Analyse", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                out.text = Sequencer.analyse(input.text.toString().lines())
            }
        }
        dialog.show()
    }

    private fun showRepeater(method: String, url: String, headers: String, body: String) {
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val methodSp = android.widget.Spinner(activity).apply {
            val ms = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, ms)
            setSelection(ms.indexOf(method.uppercase()).coerceAtLeast(0))
        }
        col.addView(label("Method")); col.addView(methodSp)
        col.addView(label("URL"))
        val urlField = EditText(activity).apply { setText(url); setSingleLine(true) }; col.addView(urlField)
        col.addView(label("Headers (one per line, Name: value)"))
        val hdrField = EditText(activity).apply { setText(headers); minLines = 3; maxLines = 8; typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f }
        col.addView(hdrField)
        col.addView(label("Body"))
        val bodyField = EditText(activity).apply { setText(body); minLines = 2; maxLines = 8; typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f }
        col.addView(bodyField)
        val respView = TextView(activity).apply {
            setTextIsSelectable(true); textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(activity.getColor(R.color.text_dim)); setPadding(0, dp(10), 0, 0)
        }
        col.addView(respView)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Repeater")
            .setView(ScrollView(activity).apply { addView(col) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Send", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val m = methodSp.selectedItem as String
                val u = urlField.text.toString().trim()
                if (!u.startsWith("http://") && !u.startsWith("https://")) { toast("Enter an http(s) URL"); return@setOnClickListener }
                if (isBlockedDestination(u)) { toast("Blocked by Pipra rules"); return@setOnClickListener }
                respView.text = "Sending…"
                val h = hdrField.text.toString(); val b = bodyField.text.toString()
                Thread {
                    val r = Repeater.send(m, u, h, b)
                    activity.runOnUiThread {
                        respView.setTextColor(activity.getColor(if (r.ok) R.color.text else R.color.accent))
                        respView.text = "${r.statusLine}   (${r.millis} ms)\n\n${r.headers}\n\n${r.body}"
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun showDecoder() {
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        col.addView(label("Input"))
        val input = EditText(activity).apply { minLines = 3; maxLines = 8; typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f }
        col.addView(input)
        val schemes = listOf("Base64", "URL", "HTML", "Hex", "Unicode", "JSON", "JWT", "MD5", "SHA-1", "SHA-256", "Timestamp")
        col.addView(label("Scheme"))
        val sp = android.widget.Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, schemes)
        }
        col.addView(sp)
        val out = TextView(activity).apply {
            setTextIsSelectable(true); textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(activity.getColor(R.color.text)); setPadding(0, dp(10), 0, 0)
        }
        col.addView(out)
        fun run(encode: Boolean) {
            val s = input.text.toString()
            out.text = when (sp.selectedItem as String) {
                "Base64" -> if (encode) Codec.base64Encode(s) else Codec.base64Decode(s)
                "URL" -> if (encode) Codec.urlEncode(s) else Codec.urlDecode(s)
                "HTML" -> if (encode) Codec.htmlEncode(s) else Codec.htmlDecode(s)
                "Hex" -> if (encode) Codec.hexEncode(s) else Codec.hexDecode(s)
                "Unicode" -> if (encode) Codec.unicodeEscape(s) else Codec.unicodeUnescape(s)
                "JSON" -> if (encode) Codec.minifyJson(s) else Codec.prettyJson(s)
                "JWT" -> Codec.jwtDecode(s)
                "MD5" -> Codec.hash("MD5", s)
                "SHA-1" -> Codec.hash("SHA-1", s)
                "SHA-256" -> Codec.hash("SHA-256", s)
                "Timestamp" -> Codec.timestamp(s)
                else -> s
            }
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Encoder / Decoder")
            .setView(ScrollView(activity).apply { addView(col) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Encode", null)
            .setNegativeButton("Decode", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { run(true) }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { run(false) }
        }
        dialog.show()
    }

    private fun showComparer() {
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        col.addView(label("A"))
        val a = EditText(activity).apply { minLines = 3; maxLines = 7; typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f }
        col.addView(a)
        col.addView(label("B"))
        val b = EditText(activity).apply { minLines = 3; maxLines = 7; typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f }
        col.addView(b)
        val out = TextView(activity).apply {
            setTextIsSelectable(true); textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(activity.getColor(R.color.text)); setPadding(0, dp(10), 0, 0)
        }
        col.addView(out)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Comparer")
            .setView(ScrollView(activity).apply { addView(col) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Compare", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                out.text = TextDiff.diff(a.text.toString(), b.text.toString())
            }
        }
        dialog.show()
    }

    private fun showStorageEditor() {
        val wv = currentWebView() ?: return
        if (wv.url?.startsWith("http") != true) { toast("Open a website first"); return }
        val js = """(function(){function d(s){var o={};try{for(var i=0;i<s.length;i++){var k=s.key(i);o[k]=s.getItem(k);}}catch(e){}return o;}
            return JSON.stringify({local:d(localStorage),session:d(sessionStorage)});})();"""
        wv.evaluateJavascript(js) { raw ->
            val json = try { org.json.JSONObject(org.json.JSONTokener(raw).nextValue() as String) } catch (_: Exception) { null }
            if (json == null) { toast("Couldn't read storage (is JS enabled?)"); return@evaluateJavascript }
            renderStorage(wv, json)
        }
    }

    private fun renderStorage(wv: WebView, json: org.json.JSONObject) {
        val local = json.optJSONObject("local") ?: org.json.JSONObject()
        val session = json.optJSONObject("session") ?: org.json.JSONObject()
        data class Item(val area: String, val key: String, val value: String)
        val items = mutableListOf<Item>()
        for (k in local.keys()) items.add(Item("local", k, local.optString(k)))
        for (k in session.keys()) items.add(Item("session", k, session.optString(k)))

        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), 0) }
        val list = ListView(activity).apply {
            adapter = ArrayAdapter(
                activity, android.R.layout.simple_list_item_1,
                if (items.isEmpty()) listOf("(no storage items)") else items.map { "[${it.area}] ${it.key}\n   ${it.value.take(120)}" }
            )
        }
        root.addView(list)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Storage: ${Uri.parse(wv.url).host}")
            .setView(root)
            .setPositiveButton("Close", null)
            .setNeutralButton("Add item", null)
            .create()
        list.setOnItemClickListener { _, _, pos, _ -> items.getOrNull(pos)?.let { editStorageItem(wv, it.area, it.key, it.value) } }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            items.getOrNull(pos)?.let { it0 ->
                confirm("Delete ${it0.area} \"${it0.key}\"?") {
                    wv.evaluateJavascript("${it0.area}Storage.removeItem(${jsStr(it0.key)});", null)
                    dialog.dismiss(); toast("Deleted"); showStorageEditor()
                }
            }; true
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { editStorageItem(wv, "local", "", "") }
        }
        dialog.show()
    }

    private fun editStorageItem(wv: WebView, area: String, key: String, value: String) {
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val areas = listOf("local", "session")
        val spin = android.widget.Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, areas)
            setSelection(areas.indexOf(area).coerceAtLeast(0))
        }
        col.addView(label("Area")); col.addView(spin)
        col.addView(label("Key"))
        val k = EditText(activity).apply { setText(key); setSingleLine(true) }; col.addView(k)
        col.addView(label("Value"))
        val v = EditText(activity).apply { setText(value); minLines = 1; maxLines = 8 }; col.addView(v)
        AlertDialog.Builder(activity)
            .setTitle("Storage item")
            .setView(ScrollView(activity).apply { addView(col) })
            .setPositiveButton("Save") { _, _ ->
                val kk = k.text.toString()
                if (kk.isBlank()) { toast("Key required"); return@setPositiveButton }
                wv.evaluateJavascript("${areas[spin.selectedItemPosition]}Storage.setItem(${jsStr(kk)},${jsStr(v.text.toString())});", null)
                toast("Saved"); showStorageEditor()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun jsStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""

    private fun viewSource() {
        val wv = currentWebView() ?: return
        if (wv.url?.startsWith("http") != true) { toast("Open a website first"); return }
        wv.evaluateJavascript("document.documentElement.outerHTML") { raw ->
            val html = try { org.json.JSONTokener(raw).nextValue() as? String ?: raw } catch (_: Exception) { raw }
            val tv = TextView(activity).apply {
                text = html; setTextIsSelectable(true); setPadding(dp(16), dp(12), dp(16), dp(12))
                textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE
            }
            AlertDialog.Builder(activity)
                .setTitle("Page source")
                .setView(ScrollView(activity).apply { addView(tv) })
                .setPositiveButton("Close", null)
                .setNeutralButton("Copy") { _, _ -> copyText(html, "Source copied") }
                .show()
        }
    }

    private fun showQuickToggles() {
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val js = switchRow("JavaScript", store.javaScriptEnabled)
        val img = switchRow("Load images", !store.blockImages)
        val cookies = switchRow("Accept cookies", CookieManager.getInstance().acceptCookie())
        col.addView(js.first); col.addView(img.first); col.addView(cookies.first)
        AlertDialog.Builder(activity)
            .setTitle("Quick toggles")
            .setView(col)
            .setPositiveButton("Apply") { _, _ ->
                store.javaScriptEnabled = js.second.isChecked
                store.blockImages = !img.second.isChecked
                CookieManager.getInstance().setAcceptCookie(cookies.second.isChecked)
                applySettingsToTabs()
                currentWebView()?.reload()
                toast("Applied")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun switchRow(text: String, checked: Boolean): Pair<View, Switch> {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(4))
        }
        row.addView(TextView(activity).apply {
            this.text = text; setTextColor(activity.getColor(R.color.text)); textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sw = Switch(activity).apply { isChecked = checked }
        row.addView(sw)
        return row to sw
    }

    private fun menuRow2(label: String, sub: String, onClick: () -> Unit): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12)); isClickable = true
            setOnClickListener { onClick() }
        }
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(activity).apply { text = label; textSize = 16f; setTextColor(activity.getColor(R.color.text)) })
        texts.addView(TextView(activity).apply { text = sub; textSize = 12f; setTextColor(activity.getColor(R.color.text_dim)) })
        row.addView(texts)
        return row
    }

    private fun label(text: String): TextView = TextView(activity).apply {
        this.text = text; setTextColor(activity.getColor(R.color.text_dim)); textSize = 13f
        setPadding(0, dp(14), 0, dp(2))
    }

    private fun sep() = View(activity).apply {
        setBackgroundColor(activity.getColor(R.color.tab_active))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
}
