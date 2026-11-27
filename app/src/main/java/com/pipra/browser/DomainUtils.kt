package com.pipra.browser

import android.net.Uri
import java.net.IDN
import java.util.Locale

/**
 * Hostname normalization, validation, and safe URL/filename helpers for Pipra.
 * Designed to prevent partial-domain false positives (e.g., "example.com" must
 * never match "notexample.com") and reject malformed or unsafe scheme inputs.
 */
object DomainUtils {

    private val UNSAFE_SCHEMES = setOf(
        "javascript", "file", "content", "data", "blob", "vbscript", "about", "intent"
    )

    private val DNS_LABEL_REGEX = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
    private val IPV4_REGEX = Regex(
        "^(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$"
    )

    /**
     * Parses a user-supplied hostname or URL and returns a normalized lowercase
     * ASCII host (e.g., "m.youtube.com"), or null if the entry is malformed.
     */
    fun normalizeHost(rawInput: String): String? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it.isISOControl() }) return null
        if (trimmed.contains('*')) return null
        if (trimmed.contains('?') && !trimmed.contains("://") && !trimmed.contains('/')) return null

        // Reject explicit unsafe schemes before URI parsing.
        val colonBeforeSlash = trimmed.indexOf(':').takeIf { it > 0 }
        val firstSlash = trimmed.indexOf('/')
        if (colonBeforeSlash != null && (firstSlash < 0 || colonBeforeSlash < firstSlash)) {
            val possibleScheme = trimmed.substring(0, colonBeforeSlash).lowercase(Locale.ROOT)
            if (possibleScheme in UNSAFE_SCHEMES) return null
            if (possibleScheme != "http" && possibleScheme != "https") {
                // Could be host:port (e.g. example.com:8080) — verify port part is digits.
                val afterColon = trimmed.substring(colonBeforeSlash + 1).substringBefore('/').substringBefore('?').substringBefore('#')
                val port = afterColon.toIntOrNull()
                if (port == null || port !in 1..65535) return null
            }
        }

        val withScheme = when {
            trimmed.startsWith("https://", ignoreCase = true) -> "https://" + trimmed.substring(8)
            trimmed.startsWith("http://", ignoreCase = true) -> "http://" + trimmed.substring(7)
            trimmed.contains("://") -> trimmed
            else -> "https://$trimmed"
        }
        val candidateHost = try {
            val uri = Uri.parse(withScheme)
            if (!uri.userInfo.isNullOrEmpty()) return null
            uri.host
        } catch (_: Exception) {
            null
        } ?: return null

        val stripped = candidateHost.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (stripped.isEmpty() || stripped.length > 253) return null

        // Convert internationalized domain names to ASCII punycode safely.
        val asciiHost = try {
            IDN.toASCII(stripped, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
        } catch (_: Exception) {
            return null
        }

        if (asciiHost.isEmpty() || asciiHost.length > 253 || asciiHost.contains("..")) return null
        if (IPV4_REGEX.matches(asciiHost)) return asciiHost

        val labels = asciiHost.split('.')
        if (labels.size < 2) return null
        if (labels.any { !DNS_LABEL_REGEX.matches(it) }) return null
        // Require non-all-numeric TLD for domain names to avoid malformed entries.
        if (labels.last().all { it.isDigit() }) return null

        return asciiHost
    }

    /**
     * Extracts and normalizes the host from an http(s) URL without rejecting
     * valid localhost or IPv4 addresses used during browsing.
     */
    fun extractHostFromUrl(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val normalizedScheme = when {
                trimmed.startsWith("https://", ignoreCase = true) -> "https://" + trimmed.substring(8)
                trimmed.startsWith("http://", ignoreCase = true) -> "http://" + trimmed.substring(7)
                else -> return null
            }
            val uri = Uri.parse(normalizedScheme)
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            if (scheme != "http" && scheme != "https") return null
            val rawHost = uri.host?.trim()?.trimEnd('.')?.lowercase(Locale.ROOT) ?: return null
            if (rawHost.isEmpty()) return null
            IDN.toASCII(rawHost, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Matches [targetHost] against [ruleHost].
     * - When [includeSubdomains] is false: exact host match only.
     * - When [includeSubdomains] is true: exact match OR dot-bounded subdomain match.
     * "example.com" never matches "notexample.com" or "example.com.evil.org".
     */
    fun matchesHost(targetHost: String, ruleHost: String, includeSubdomains: Boolean): Boolean {
        val target = targetHost.trim().trimEnd('.').lowercase(Locale.ROOT)
        val rule = ruleHost.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (target.isEmpty() || rule.isEmpty()) return false
        if (target == rule) return true
        if (!includeSubdomains) return false
        return target.endsWith(".$rule")
    }

    /**
     * Validates a user-entered custom search engine URL template.
     * Requires https:// or http:// with a valid host. Supports optional "%s" query placeholder.
     */
    fun validateCustomSearchUrl(rawInput: String): String? {
        val t = rawInput.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() || it.isISOControl() }) return null
        val lower = t.lowercase(Locale.ROOT)
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return null
        val canonical = if (lower.startsWith("https://")) "https://" + t.substring(8) else "http://" + t.substring(7)
        val probe = canonical.replace("%s", "query")
        val host = extractHostFromUrl(probe) ?: return null
        if (host.isBlank()) return null
        return canonical
    }

    /** Formats a search URL from a template that may contain %s or act as a query prefix. */
    fun formatSearchUrl(searchTemplate: String, query: String): String {
        val encoded = Uri.encode(query)
        return if (searchTemplate.contains("%s")) {
            searchTemplate.replace("%s", encoded)
        } else {
            searchTemplate + encoded
        }
    }

    /**
     * Resolves typed address-bar input into a safe URL or search engine query.
     * Never returns javascript:, file:, content:, data:, or blob: URLs.
     */
    fun resolveOmniboxInput(
        input: String,
        searchTemplate: String,
        homeUrl: String
    ): String {
        val t = input.trim()
        if (t.isEmpty()) return homeUrl
        val lower = t.lowercase(Locale.ROOT)
        if (lower == "pipra://home") return "pipra://home"
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            val canonical = if (lower.startsWith("https://")) "https://" + t.substring(8) else "http://" + t.substring(7)
            val host = extractHostFromUrl(canonical)
            return if (host != null) canonical else formatSearchUrl(searchTemplate, t)
        }
        // Any explicit non-http(s) scheme (javascript:, file:, content:, data:, etc.) is treated as search text.
        val colonIdx = t.indexOf(':')
        if (colonIdx > 0) {
            val beforeColon = lower.substring(0, colonIdx)
            val afterColon = t.substring(colonIdx + 1).substringBefore('/').substringBefore('?').substringBefore('#')
            val isPort = afterColon.toIntOrNull()?.let { it in 1..65535 } == true
            if (!isPort || beforeColon in UNSAFE_SCHEMES) {
                return formatSearchUrl(searchTemplate, t)
            }
        }
        if (!t.contains(' ')) {
            val hostPart = t.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
            if (hostPart.equals("localhost", ignoreCase = true) || normalizeHost(t) != null) {
                return "https://$t"
            }
        }
        return formatSearchUrl(searchTemplate, t)
    }

    /** Sanitizes a suggested download filename to prevent path traversal or hidden tricks. */
    fun sanitizeDownloadFileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace("..", "_")
            .replace(Regex("[\\x00-\\x1F\\x7F:*?\"<>|]"), "_")
            .trim()
        return base.ifEmpty { "download_${System.currentTimeMillis()}" }.take(180)
    }
}

