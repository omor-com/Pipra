package com.pipra.browser

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Local, offline-first Adult Website Protection engine.
 *
 * - Never sends browsing history or visited URLs to any external classification API.
 * - Ships with a curated offline baseline domain list in res/raw/adult_domains_baseline.txt.
 * - Supports manual updates from reputable public hosts lists, validated and cached atomically.
 * - Integrates seamlessly alongside WebsiteBlocker in navigation & subresource checks.
 */
class AdultBlockerEngine(private val context: Context) {

    private val cacheFile = File(context.filesDir, "pipra_adult_cache.txt")

    @Volatile
    private var baselineLoaded = false

    @Volatile
    private var baselineDomains: HashSet<String> = HashSet()

    @Volatile
    private var cachedMtime: Long = -1L

    @Volatile
    private var cachedDomains: HashSet<String> = HashSet()

    companion object {
        const val DEFAULT_ADULT_LIST_URL =
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts"

        val PRESET_SOURCES = linkedMapOf(
            "StevenBlack Unified Hosts (Adult)" to DEFAULT_ADULT_LIST_URL,
            "OISD NSFW Big List (Domains)" to "https://nsfw.oisd.nl/domainswild2"
        )

        private const val MAX_ADULT_BYTES = 16 * 1024 * 1024
    }

    fun activeDomainCount(): Int {
        ensureLoaded()
        return baselineDomains.size + cachedDomains.size
    }

    fun cachedDomainCount(): Int {
        ensureLoaded()
        return cachedDomains.size
    }

    fun isBlockedUrl(url: String, enabled: Boolean): Boolean {
        if (!enabled) return false
        val host = DomainUtils.extractHostFromUrl(url) ?: return false
        return isBlockedHost(host, enabled)
    }

    fun isBlockedHost(rawHost: String, enabled: Boolean): Boolean {
        if (!enabled) return false
        val host = DomainUtils.normalizeHost(rawHost) ?: return false
        ensureLoaded()
        return matchesSet(host, baselineDomains) ||
            (cachedDomains.isNotEmpty() && matchesSet(host, cachedDomains))
    }

    /**
     * Downloads, parses, and validates adult domain rules off the UI thread.
     * Existing cached rules remain intact if the update fails or returns invalid data.
     */
    fun updateBlocklist(sourceUrl: String = DEFAULT_ADULT_LIST_URL): FilterUpdateResult {
        val cleanUrl = sourceUrl.trim()
        if (!cleanUrl.startsWith("https://")) {
            return FilterUpdateResult(false, cachedDomainCount(), "List source URL must use HTTPS.")
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        return try {
            val req = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", "Pipra-AdultProtection/2.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return FilterUpdateResult(false, cachedDomainCount(), "Update failed: HTTP ${resp.code}")
                }
                val body = resp.body ?: return FilterUpdateResult(false, cachedDomainCount(), "Empty response.")
                val bytes = body.byteStream().use { input ->
                    val buf = ByteArray(8192)
                    val out = java.io.ByteArrayOutputStream()
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_ADULT_BYTES) break
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
                val parsed = parseDomainList(String(bytes, Charsets.UTF_8))
                if (parsed.size < 50) {
                    return FilterUpdateResult(
                        false,
                        cachedDomainCount(),
                        "List validation failed (${parsed.size} domains parsed). Previous list kept."
                    )
                }
                AtomicFiles.writeText(cacheFile, parsed.sorted().joinToString("\n"))
                cachedDomains = parsed
                cachedMtime = cacheFile.lastModified()
                val now = System.currentTimeMillis()
                FilterUpdateResult(true, parsed.size, "Updated ${parsed.size} adult domains.", now)
            }
        } catch (e: Exception) {
            FilterUpdateResult(
                false,
                cachedDomainCount(),
                "Offline or update error (${e.javaClass.simpleName}). Cached list preserved."
            )
        }
    }

    internal fun parseDomainList(rawText: String): HashSet<String> {
        val out = HashSet<String>(4096)
        val head = rawText.trimStart().take(200).lowercase(Locale.ROOT)
        if (head.startsWith("<!doctype html") || head.startsWith("<html")) return out

        for (rawLine in rawText.lineSequence()) {
            var line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue
            val hashIdx = line.indexOf('#')
            if (hashIdx > 0) line = line.substring(0, hashIdx).trim()

            val candidate = when {
                line.startsWith("*.") -> line.removePrefix("*.")
                line.startsWith("||") -> line.removePrefix("||").substringBefore('^').substringBefore('/')
                line.contains(' ') || line.contains('\t') -> {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) parts[1] else ""
                }
                else -> line
            }
            if (candidate.isEmpty() || candidate == "localhost" || candidate == "0.0.0.0") continue
            val norm = DomainUtils.normalizeHost(candidate) ?: continue
            out.add(norm)
        }
        return out
    }

    @Synchronized
    private fun ensureLoaded() {
        if (!baselineLoaded) {
            val loadedBase = HashSet<String>(512)
            try {
                context.resources.openRawResource(com.example.R.raw.adult_domains_baseline)
                    .bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { raw ->
                            val line = raw.trim()
                            if (line.isNotEmpty() && !line.startsWith("#")) {
                                DomainUtils.normalizeHost(line)?.let { loadedBase.add(it) }
                            }
                        }
                    }
            } catch (_: Exception) {
                // Fallback if resource unavailable in unit test context
            }
            baselineDomains = loadedBase
            baselineLoaded = true
        }
        if (cacheFile.exists()) {
            val mtime = cacheFile.lastModified()
            if (mtime != cachedMtime) {
                val loaded = HashSet<String>(2048)
                try {
                    cacheFile.forEachLine(Charsets.UTF_8) { line ->
                        val h = line.trim()
                        if (h.isNotEmpty()) loaded.add(h)
                    }
                } catch (_: Exception) {
                }
                cachedDomains = loaded
                cachedMtime = mtime
            }
        } else if (cachedMtime != -1L) {
            cachedDomains = HashSet()
            cachedMtime = -1L
        }
    }

    private fun matchesSet(host: String, set: HashSet<String>): Boolean {
        var h = host.lowercase(Locale.ROOT)
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0 || dot == h.lastIndexOf('.')) return false
            h = h.substring(dot + 1)
        }
    }
}
