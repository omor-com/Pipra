package com.pipra.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class HeaderRule(
    val id: String = UUID.randomUUID().toString(),
    var enabled: Boolean = true,
    var name: String = "",
    var urlPattern: String = "*",
    var onResponse: Boolean = false,
    var remove: Boolean = false,
    var header: String = "",
    var value: String = ""
)

data class RequestRule(
    val id: String = UUID.randomUUID().toString(),
    var enabled: Boolean = true,
    var name: String = "",
    var urlPattern: String = "",
    var action: String = ACTION_BLOCK,
    var target: String = "",
    var replaceWith: String = "",
    var isCss: Boolean = false
) {
    companion object {
        const val ACTION_BLOCK = "block"
        const val ACTION_REDIRECT = "redirect"
        const val ACTION_REPLACE = "replace"
        const val ACTION_INSERT = "insert"
        const val ACTION_QUERY = "query"
        const val ACTION_URLREPLACE = "urlreplace"
        const val ACTION_STATUS = "status"
        const val ACTION_MAPLOCAL = "maplocal"
        const val ACTION_DELAY = "delay"
    }
}

data class NetEntry(
    val method: String,
    val url: String,
    var status: Int = 0,
    var note: String = "",
    val reqHeaders: Map<String, String> = emptyMap(),
    val time: Long = System.currentTimeMillis()
)

object NetLog {
    private const val CAP = 500
    private val entries = ArrayDeque<NetEntry>()

    @Synchronized fun add(e: NetEntry) {
        entries.addFirst(e)
        while (entries.size > CAP) entries.removeLast()
    }

    @Synchronized fun snapshot(): List<NetEntry> = entries.toList()
    @Synchronized fun clear() = entries.clear()
}

object UrlMatch {
    fun matches(pattern: String, url: String): Boolean {
        val p = pattern.trim()
        if (p.isEmpty() || p == "*") return true
        val u = url.lowercase()
        val pl = p.lowercase()
        if (!pl.contains("*")) return u.contains(pl)
        val regex = buildString {
            append("^.*")
            for (part in pl.split("*")) append(Regex.escape(part)).append(".*")
        }.let { it.dropLast(2) }
        return try { Regex(regex).containsMatchIn(u) } catch (_: Exception) { u.contains(pl.replace("*", "")) }
    }
}

class DevStore(context: Context) {
    private val prefs = context.getSharedPreferences("pipra_dev", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_HEADER_RULES = "header_rules"
        private const val KEY_REQUEST_RULES = "request_rules"
        const val KEY_DEVTOOLS = "devtools_enabled"
        private const val KEY_SCOPE_INCLUDE = "scope_include"
        private const val KEY_SCOPE_EXCLUDE = "scope_exclude"
    }

    var devToolsEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEVTOOLS, true)
        set(v) = prefs.edit().putBoolean(KEY_DEVTOOLS, v).apply()

    var scopeInclude: String
        get() = prefs.getString(KEY_SCOPE_INCLUDE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SCOPE_INCLUDE, v).apply()

    var scopeExclude: String
        get() = prefs.getString(KEY_SCOPE_EXCLUDE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SCOPE_EXCLUDE, v).apply()

    fun inScope(url: String): Boolean {
        val inc = scopeInclude.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val exc = scopeExclude.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (exc.any { UrlMatch.matches(it, url) }) return false
        if (inc.isEmpty()) return true
        return inc.any { UrlMatch.matches(it, url) }
    }

    fun headerRules(): MutableList<HeaderRule> {
        val out = mutableListOf<HeaderRule>()
        val arr = read(KEY_HEADER_RULES)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                HeaderRule(
                    o.optString("id", UUID.randomUUID().toString()),
                    o.optBoolean("enabled", true), o.optString("name"),
                    o.optString("urlPattern", "*"), o.optBoolean("onResponse"),
                    o.optBoolean("remove"), o.optString("header"), o.optString("value")
                )
            )
        }
        return out
    }

    fun saveHeaderRules(list: List<HeaderRule>) {
        val arr = JSONArray()
        for (r in list) arr.put(
            JSONObject()
                .put("id", r.id).put("enabled", r.enabled).put("name", r.name)
                .put("urlPattern", r.urlPattern).put("onResponse", r.onResponse)
                .put("remove", r.remove).put("header", r.header).put("value", r.value)
        )
        prefs.edit().putString(KEY_HEADER_RULES, arr.toString()).apply()
    }

    fun requestRules(): MutableList<RequestRule> {
        val out = mutableListOf<RequestRule>()
        val arr = read(KEY_REQUEST_RULES)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                RequestRule(
                    o.optString("id", UUID.randomUUID().toString()),
                    o.optBoolean("enabled", true), o.optString("name"),
                    o.optString("urlPattern"), o.optString("action", RequestRule.ACTION_BLOCK),
                    o.optString("target"), o.optString("replaceWith"), o.optBoolean("isCss")
                )
            )
        }
        return out
    }

    fun saveRequestRules(list: List<RequestRule>) {
        val arr = JSONArray()
        for (r in list) arr.put(
            JSONObject()
                .put("id", r.id).put("enabled", r.enabled).put("name", r.name)
                .put("urlPattern", r.urlPattern).put("action", r.action)
                .put("target", r.target).put("replaceWith", r.replaceWith).put("isCss", r.isCss)
        )
        prefs.edit().putString(KEY_REQUEST_RULES, arr.toString()).apply()
    }

    fun insertRulesFor(url: String): List<RequestRule> =
        requestRules().filter { it.enabled && it.action == RequestRule.ACTION_INSERT && UrlMatch.matches(it.urlPattern, url) }

    private fun read(key: String): JSONArray =
        try { JSONArray(prefs.getString(key, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
}
