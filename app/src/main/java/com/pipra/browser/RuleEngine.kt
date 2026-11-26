package com.pipra.browser

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

/**
 * Applies developer-tool request/header rules while respecting Pipra's
 * Website Blocking and Adult Protection gates (so redirects or rule rewrites
 * can never bypass blocked domains).
 */
class RuleEngine(
    private val dev: DevStore,
    private val isBlockedDestination: (String) -> Boolean = { false }
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    fun rewriteUrl(url: String, rules: List<RequestRule>): String {
        var out = url
        for (r in rules) {
            when (r.action) {
                RequestRule.ACTION_URLREPLACE ->
                    if (r.target.isNotEmpty()) out = out.replace(r.target, r.replaceWith)
                RequestRule.ACTION_QUERY -> out = applyQuery(out, r)
            }
        }
        return out
    }

    private fun applyQuery(url: String, r: RequestRule): String {
        return try {
            val uri = Uri.parse(url)
            val params = LinkedHashMap<String, String>()
            for (k in uri.queryParameterNames) params[k] = uri.getQueryParameter(k) ?: ""
            if (r.target.isNotBlank()) for (pair in r.target.split("&")) {
                val i = pair.indexOf('='); if (i < 0) continue
                params[pair.substring(0, i).trim()] = pair.substring(i + 1)
            }
            if (r.replaceWith.isNotBlank()) for (k in r.replaceWith.split(",")) params.remove(k.trim())
            val b = uri.buildUpon().clearQuery()
            for ((k, v) in params) b.appendQueryParameter(k, v)
            b.build().toString()
        } catch (_: Exception) { url }
    }

    fun intercept(request: WebResourceRequest, isPrivateSession: Boolean = false): WebResourceResponse? {
        if (!dev.devToolsEnabled) return null
        val url = request.url?.toString() ?: return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val method = request.method ?: "GET"
        val reqHeaders = if (isPrivateSession) emptyMap() else (request.requestHeaders ?: emptyMap())

        if (!dev.inScope(url)) {
            if (!isPrivateSession) NetLog.add(NetEntry(method, url, 0, "out-of-scope", reqHeaders))
            return null
        }

        val reqRules = dev.requestRules().filter { it.enabled && UrlMatch.matches(it.urlPattern, url) }

        reqRules.firstOrNull { it.action == RequestRule.ACTION_BLOCK }?.let {
            if (!isPrivateSession) NetLog.add(NetEntry(method, url, 0, "blocked", reqHeaders))
            return emptyResponse()
        }

        reqRules.firstOrNull { it.action == RequestRule.ACTION_MAPLOCAL }?.let { r ->
            val ctype = r.replaceWith.ifBlank { "text/html" }
            val mime = ctype.substringBefore(";").trim()
            if (!isPrivateSession) NetLog.add(NetEntry(method, url, 200, "map-local", reqHeaders))
            return WebResourceResponse(
                mime, "utf-8", 200, "OK",
                emptyMap(),
                ByteArrayInputStream(r.target.toByteArray(Charsets.UTF_8))
            )
        }

        reqRules.firstOrNull { it.action == RequestRule.ACTION_DELAY }?.let { r ->
            val ms = r.target.trim().toLongOrNull()?.coerceIn(0, 60_000) ?: 0
            if (ms > 0) try { Thread.sleep(ms) } catch (_: InterruptedException) {}
        }

        val headerReq = dev.headerRules().filter { it.enabled && !it.onResponse && UrlMatch.matches(it.urlPattern, url) }
        val headerResp = dev.headerRules().filter { it.enabled && it.onResponse && UrlMatch.matches(it.urlPattern, url) }
        val replaceRules = reqRules.filter { it.action == RequestRule.ACTION_REPLACE }
        val statusRule = reqRules.firstOrNull { it.action == RequestRule.ACTION_STATUS }
        val urlRules = if (!request.isForMainFrame)
            reqRules.filter { it.action == RequestRule.ACTION_QUERY || it.action == RequestRule.ACTION_URLREPLACE } else emptyList()
        val subRedirect = if (!request.isForMainFrame)
            reqRules.firstOrNull { it.action == RequestRule.ACTION_REDIRECT && it.target.isNotBlank() } else null

        val newUrl = rewriteUrl(url, urlRules)
        val fetchUrl = subRedirect?.target ?: newUrl
        if (fetchUrl != url && (isBlockedDestination(fetchUrl) || !(fetchUrl.startsWith("http://") || fetchUrl.startsWith("https://")))) {
            return emptyResponse()
        }

        val needsRewrite = headerReq.isNotEmpty() || headerResp.isNotEmpty() ||
            replaceRules.isNotEmpty() || subRedirect != null || statusRule != null || newUrl != url
        if (!needsRewrite) {
            if (!isPrivateSession) NetLog.add(NetEntry(method, url, 0, "", reqHeaders))
            return null
        }
        if (!method.equals("GET", true)) {
            if (!isPrivateSession) NetLog.add(NetEntry(method, url, 0, "skipped (non-GET)", reqHeaders))
            return null
        }

        val note = when {
            subRedirect != null -> "redirected"
            newUrl != url -> "url-edited"
            statusRule != null -> "status"
            replaceRules.isNotEmpty() -> "modified"
            else -> "headers"
        }
        return try {
            runFetch(
                fetchUrl, url, method, reqHeaders, headerReq, headerResp, replaceRules,
                statusRule?.target?.trim()?.toIntOrNull(), note, isPrivateSession
            )
        } catch (e: Exception) {
            if (!isPrivateSession) NetLog.add(NetEntry(method, url, 0, "error: ${e.message?.take(40)}", reqHeaders))
            null
        }
    }

    private fun runFetch(
        fetchUrl: String, originalUrl: String, method: String,
        original: Map<String, String>,
        headerReq: List<HeaderRule>, headerResp: List<HeaderRule>, replace: List<RequestRule>,
        statusOverride: Int?, noteIn: String, isPrivateSession: Boolean
    ): WebResourceResponse {
        var currentUrl = fetchUrl
        var redirects = 0
        while (true) {
            if (isBlockedDestination(currentUrl)) return emptyResponse()
            val builder = Request.Builder().url(currentUrl)
            val outHeaders = LinkedHashMap<String, String>()
            for ((k, v) in original) outHeaders[k] = v
            CookieManager.getInstance().getCookie(currentUrl)?.let { if (it.isNotBlank()) outHeaders["Cookie"] = it }
            for (r in headerReq) {
                if (r.header.isBlank()) continue
                val key = outHeaders.keys.firstOrNull { it.equals(r.header, true) } ?: r.header
                if (r.remove) outHeaders.remove(key) else outHeaders[r.header] = r.value
            }
            outHeaders.remove("Accept-Encoding")
            for ((k, v) in outHeaders) { try { builder.header(k, v) } catch (_: Exception) {} }

            client.newCall(builder.get().build()).execute().use { resp ->
                if (resp.code in 300..399 && redirects < 5) {
                    val loc = resp.header("Location")
                    if (!loc.isNullOrBlank()) {
                        val resolved = resp.request.url.resolve(loc)?.toString()
                        if (resolved != null && (resolved.startsWith("http://") || resolved.startsWith("https://"))) {
                            if (isBlockedDestination(resolved)) return emptyResponse()
                            currentUrl = resolved
                            redirects++
                            continue
                        }
                    }
                }
                for (sc in resp.headers("Set-Cookie")) CookieManager.getInstance().setCookie(originalUrl, sc)
                if (!isPrivateSession) CookieManager.getInstance().flush()

                val ctype = resp.header("Content-Type") ?: "text/html"
                val mime = ctype.substringBefore(";").trim().ifEmpty { "text/html" }
                val charset = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE)
                    .find(ctype)?.groupValues?.get(1)?.trim() ?: "utf-8"

                val respHeaders = LinkedHashMap<String, String>()
                for (name in resp.headers.names()) {
                    if (name.equals("Content-Encoding", true) || name.equals("Content-Length", true) ||
                        name.equals("Transfer-Encoding", true)) continue
                    respHeaders[name] = resp.headers(name).joinToString(", ")
                }
                for (r in headerResp) {
                    if (r.header.isBlank()) continue
                    if (r.remove) respHeaders.keys.filter { it.equals(r.header, true) }.forEach { respHeaders.remove(it) }
                    else respHeaders[r.header] = r.value
                }

                var bytes = resp.body?.bytes() ?: ByteArray(0)
                val textLike = mime.startsWith("text/") || mime.contains("json") ||
                    mime.contains("javascript") || mime.contains("xml") || mime.contains("css") || mime.contains("html")
                if (replace.isNotEmpty() && textLike) {
                    var body = String(bytes, charset(charset))
                    for (r in replace) if (r.target.isNotEmpty()) body = body.replace(r.target, r.replaceWith)
                    bytes = body.toByteArray(charset(charset))
                }

                val code = statusOverride ?: resp.code
                if (!isPrivateSession) NetLog.add(NetEntry(method, originalUrl, code, noteIn, original))
                val reason = if (statusOverride != null) "Overridden" else resp.message.ifBlank { "OK" }
                return WebResourceResponse(mime, charset, code, reason, respHeaders, ByteArrayInputStream(bytes))
            }
        }
    }

    private fun charset(name: String): java.nio.charset.Charset =
        try { java.nio.charset.Charset.forName(name) } catch (_: Exception) { Charsets.UTF_8 }

    private fun emptyResponse() =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
