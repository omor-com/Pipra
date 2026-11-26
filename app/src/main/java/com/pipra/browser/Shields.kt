package com.pipra.browser

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Compatibility facade preserving Injectra's `Shields` entry points while
 * delegating to Pipra's tiered [AdBlockerEngine].
 */
object Shields {
    fun isBlocked(host: String): Boolean {
        val norm = DomainUtils.normalizeHost(host) ?: return false
        var h = norm
        while (true) {
            if (h in AdBlockerEngine.MEDIUM_DOMAINS || h in AdBlockerEngine.HIGH_EXTRA_DOMAINS) return true
            val dot = h.indexOf('.')
            if (dot < 0 || dot == h.lastIndexOf('.')) return false
            h = h.substring(dot + 1)
        }
    }

    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
