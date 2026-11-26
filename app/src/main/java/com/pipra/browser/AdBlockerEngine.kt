package com.pipra.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

data class FilterUpdateResult(
    val success: Boolean,
    val ruleCount: Int,
    val message: String,
    val updatedAtMillis: Long = 0L
)

/**
 * Pipra's tiered Ad Blocker engine (upgrading Injectra's Shields).
 *
 * Modes have genuinely distinct filtering behavior:
 * - MEDIUM: Core advertising networks and major third-party trackers (low breakage).
 * - HIGH: Adds broader telemetry, analytics, fingerprinting, attribution networks,
 *   and third-party ad URL path/subdomain heuristics.
 * - ULTIMATE: Adds expanded ad/popunder/malvertising domains, cached external filter
 *   lists, tracking query-parameter stripping, and cosmetic CSS element hiding.
 *
 * All filter-list updates run off the main thread, validate downloaded rules before
 * replacing the local cache, and never log visited or blocked URLs.
 */
class AdBlockerEngine(context: Context) {

    private val cacheFile = File(context.filesDir, "pipra_adblock_cache.txt")

    @Volatile
    private var cachedMtime: Long = -1L

    @Volatile
    private var cachedExternalDomains: HashSet<String> = HashSet()

    companion object {
        const val DEFAULT_FILTER_URL =
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext"

        val PRESET_SOURCES = linkedMapOf(
            "Peter Lowe Ad & Tracking List" to DEFAULT_FILTER_URL,
            "StevenBlack Adware + Malware Hosts" to "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            "AdGuard DNS Filter (Domains)" to "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"
        )

        private const val MAX_FILTER_BYTES = 12 * 1024 * 1024 // 12 MB safety bound

        // Medium tier: core ad exchanges, ad servers, and major behavioral trackers (conservative).
        internal val MEDIUM_DOMAINS: HashSet<String> = hashSetOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "google-analytics.com", "googletagmanager.com", "googletagservices.com",
            "adservice.google.com", "pagead2.googlesyndication.com", "imasdk.googleapis.com",
            "app-measurement.com", "2mdn.net",
            "connect.facebook.net", "pixel.facebook.com", "an.facebook.com",
            "ads.linkedin.com", "px.ads.linkedin.com", "analytics.twitter.com",
            "ads-twitter.com", "static.ads-twitter.com", "ads.pinterest.com",
            "ct.pinterest.com", "analytics.tiktok.com", "ads.tiktok.com",
            "sc-static.net", "tr.snapchat.com", "ads.reddit.com", "alb.reddit.com",
            "amazon-adsystem.com", "aax.amazon-adsystem.com", "assoc-amazon.com",
            "bat.bing.com", "ads.microsoft.com",
            "adnxs.com", "adsrvr.org", "rubiconproject.com", "pubmatic.com",
            "openx.net", "casalemedia.com", "criteo.com", "criteo.net",
            "taboola.com", "outbrain.com", "revcontent.com", "mgid.com",
            "smartadserver.com", "adform.net", "yieldmo.com", "sharethrough.com",
            "triplelift.com", "33across.com", "indexww.com", "contextweb.com",
            "sovrn.com", "lijit.com", "gumgum.com", "teads.tv", "media.net",
            "advertising.com", "adtechus.com", "bidswitch.net", "spotxchange.com",
            "springserve.com", "smaato.net", "inmobi.com", "mopub.com",
            "applovin.com", "unityads.unity3d.com", "adcolony.com", "vungle.com",
            "chartboost.com", "ironsrc.com", "supersonicads.com", "zedo.com",
            "propellerads.com", "popads.net", "popcash.net", "adsterra.com",
            "exoclick.com", "juicyads.com", "trafficjunky.net", "hilltopads.net",
            "adcash.com", "admaven.com", "onclickads.net", "clickadu.com",
            "yllix.com", "a-ads.com", "bidvertiser.com", "infolinks.com",
            "adroll.com", "quantcast.com", "quantserve.com", "lotame.com",
            "crwdcntrl.net", "bluekai.com", "demdex.net", "omtrdc.net",
            "everesttech.net", "rlcdn.com", "agkn.com", "adsymptotic.com",
            "tapad.com", "eyeota.net", "exelator.com", "mathtag.com",
            "turn.com", "serving-sys.com", "flashtalking.com", "innovid.com",
            "moatads.com", "doubleverify.com", "adsafeprotected.com",
            "iasds01.com", "zemanta.com", "nativo.com", "adblade.com",
            "scorecardresearch.com", "comscore.com"
        )

        // High tier: additional analytics, session-replay, fingerprinting, attribution & push-ad networks.
        internal val HIGH_EXTRA_DOMAINS: HashSet<String> = hashSetOf(
            "clarity.ms", "chartbeat.com", "chartbeat.net", "hotjar.com", "hotjar.io",
            "mouseflow.com", "fullstory.com", "crazyegg.com", "luckyorange.com",
            "inspectlet.com", "mixpanel.com", "amplitude.com", "heapanalytics.com",
            "segment.io", "segment.com", "kissmetrics.com", "newrelic.com", "nr-data.net",
            "optimizely.com", "branch.io", "app.link", "adjust.com", "appsflyer.com",
            "kochava.com", "singular.net", "statcounter.com", "histats.com",
            "mc.yandex.ru", "top-fwz1.mail.ru", "counter.yadro.ru",
            "fingerprintjs.com", "fpjs.io", "openfpcdn.io", "iovation.com",
            "threatmetrix.com", "parsely.com", "parse.ly", "cxense.com",
            "permutive.com", "permutive.app", "id5-sync.com", "liveramp.com",
            "pippio.com", "addthis.com", "sharethis.com", "disqusads.com",
            "onesignal.com", "pushcrew.com", "pushengage.com", "subscribers.com",
            "bugsnag.com", "sentry-cdn.com", "logrocket.com", "smartlook.com",
            "quantummetric.com", "contentsquare.net", "pingdom.net", "rudderstack.com",
            "snowplowanalytics.com", "mxpnl.com", "hubspot.com", "hs-analytics.net",
            "hs-scripts.com", "mktoresp.com", "marketo.net", "pardot.com",
            "krxd.net", "liadm.com", "bidr.io", "3lift.com", "rtbhouse.com"
        )

        // Ultimate tier: popunder, malvertising, ad-mediation, crypto-miner, and CNAME-tracker domains.
        internal val ULTIMATE_EXTRA_DOMAINS: HashSet<String> = hashSetOf(
            "coinhive.com", "coin-hive.com", "authedmine.com", "cryptoloot.pro",
            "jsecoin.com", "minero.cc", "webminepool.com", "moneroocean.stream",
            "adsco.re", "adtelligent.com", "adxpremium.services", "buysellads.com",
            "carbonads.net", "servedby-buysellads.com", "sponsorads.de",
            "trafficfactory.biz", "trafficstars.com", "ero-advertising.com",
            "reklamstore.com", "adreactor.com", "adskeeper.co.uk", "mgid.io",
            "bidswitch.com", "zemanta.net", "spotx.tv", "stickyadstv.com",
            "lkqd.net", "tremorhub.com", "adap.tv", "tidaltv.com",
            "sonobi.com", "districtm.io", "kargo.com", "connatix.com",
            "primis.tech", "playbuzz.com", "nielsen.com", "imrworldwide.com",
            "kantar.com", "effectivemeasure.net", "tns-counter.ru",
            "eulerian.net", "at-o.net", "xiti.com", "atinternet.com",
            "webtrekk.net", "wt-eu02.net", "ensighten.com", "tealiumiq.com",
            "tiqcdn.com", "tagcommander.com", "commandersact.com",
            "bounceexchange.com", "bouncex.net", "yoptima.com", "zeotap.com",
            "tapjoy.com", "fyber.com", "voodoo-ads.io", "byteoversea.com"
        )

        private val HIGH_HOST_PREFIXES = listOf(
            "ads.", "adserver.", "adservice.", "adtrack.", "pixel.",
            "telemetry.", "metrics.", "beacon.", "analytics.", "tracker."
        )

        private val HIGH_PATH_SNIPPETS = listOf(
            "/pagead/", "/doubleclick/", "/adsystem/", "/adservice/",
            "/ads/banner", "/ads/prebid", "/prebid.", "/gtag/js", "/fbevents.js"
        )

        private val ULTIMATE_PATH_SNIPPETS = listOf(
            "/adframe", "/adunit", "/adclick", "/popunder", "/sponsored_ads",
            "/tracking.js", "/tracker.js", "/fingerprint.js", "/fp.js",
            "/collect?", "/pixel?", "/beacon?"
        )

        val TRACKING_QUERY_PARAMS = setOf(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
            "utm_id", "fbclid", "gclid", "gbraid", "wbraid", "msclkid",
            "mc_eid", "mc_cid", "_hsenc", "_hsmi", "yclid", "igshid", "spm"
        )

        const val ULTIMATE_COSMETIC_CSS =
            "ins.adsbygoogle, [id^='div-gpt-ad'], [data-ad-slot], [data-ad-client], " +
                ".ad-banner, .ad-slot, .ad-container, .sponsored-banner, " +
                "iframe[src*='doubleclick.net'], iframe[src*='googlesyndication.com'] " +
                "{ display: none !important; visibility: hidden !important; }"
    }

    fun cachedRuleCount(): Int {
        ensureCacheLoaded()
        return cachedExternalDomains.size
    }

    fun totalBuiltinCount(mode: AdBlockMode): Int = when (mode) {
        AdBlockMode.MEDIUM -> MEDIUM_DOMAINS.size
        AdBlockMode.HIGH -> MEDIUM_DOMAINS.size + HIGH_EXTRA_DOMAINS.size
        AdBlockMode.ULTIMATE -> MEDIUM_DOMAINS.size + HIGH_EXTRA_DOMAINS.size + ULTIMATE_EXTRA_DOMAINS.size
    }

    /**
     * Evaluates whether a subresource request should be blocked.
     * Note: Medium also checks cached external list only if cached; High adds
     * broader tracker domains + third-party path/prefix heuristics; Ultimate adds
     * full external list + aggressive path heuristics.
     */
    fun shouldBlockSubresource(
        requestUrl: String,
        pageUrl: String?,
        mode: AdBlockMode,
        enabled: Boolean,
        exceptions: Set<String>
    ): Boolean {
        if (!enabled) return false

        val reqHost = DomainUtils.extractHostFromUrl(requestUrl) ?: return false
        val pageHost = pageUrl?.let { DomainUtils.extractHostFromUrl(it) }

        if (pageHost != null && exceptions.any { DomainUtils.matchesHost(pageHost, it, includeSubdomains = true) }) {
            return false
        }

        // 1. Domain-based matching (tiered)
        if (matchesDomainSet(reqHost, MEDIUM_DOMAINS)) return true

        if (mode == AdBlockMode.HIGH || mode == AdBlockMode.ULTIMATE) {
            if (matchesDomainSet(reqHost, HIGH_EXTRA_DOMAINS)) return true
        }

        if (mode == AdBlockMode.ULTIMATE) {
            if (matchesDomainSet(reqHost, ULTIMATE_EXTRA_DOMAINS)) return true
            ensureCacheLoaded()
            if (cachedExternalDomains.isNotEmpty() && matchesDomainSet(reqHost, cachedExternalDomains)) {
                return true
            }
        } else if (mode == AdBlockMode.HIGH) {
            ensureCacheLoaded()
            if (cachedExternalDomains.isNotEmpty() && matchesDomainSet(reqHost, cachedExternalDomains)) {
                return true
            }
        }

        // 2. Third-party host prefix & URL path heuristics (only in HIGH and ULTIMATE to avoid first-party breakage in MEDIUM)
        val isThirdParty = pageHost != null && !isSameSite(reqHost, pageHost)
        if (isThirdParty && (mode == AdBlockMode.HIGH || mode == AdBlockMode.ULTIMATE)) {
            if (HIGH_HOST_PREFIXES.any { reqHost.startsWith(it) }) return true
            val lowerUrl = requestUrl.lowercase(Locale.ROOT)
            if (HIGH_PATH_SNIPPETS.any { lowerUrl.contains(it) }) return true
            if (mode == AdBlockMode.ULTIMATE && ULTIMATE_PATH_SNIPPETS.any { lowerUrl.contains(it) }) return true
        }

        return false
    }

    /** Strips known cross-site tracking query parameters while keeping functional parameters intact. */
    fun stripTrackingParams(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if (scheme != "http" && scheme != "https") return url
            val names = uri.queryParameterNames
            if (names.isEmpty() || names.none { it.lowercase(Locale.ROOT) in TRACKING_QUERY_PARAMS }) {
                return url
            }
            val builder = uri.buildUpon().clearQuery()
            for (name in names) {
                if (name.lowercase(Locale.ROOT) !in TRACKING_QUERY_PARAMS) {
                    for (value in uri.getQueryParameters(name)) {
                        builder.appendQueryParameter(name, value)
                    }
                }
            }
            builder.build().toString()
        } catch (_: Exception) {
            url
        }
    }

    /**
     * Downloads, parses, and validates filter rules off the UI thread.
     * On any network, format, or validation failure, the previous valid cache stays untouched.
     */
    fun updateFilterLists(sourceUrl: String = DEFAULT_FILTER_URL): FilterUpdateResult {
        val cleanUrl = sourceUrl.trim()
        if (!cleanUrl.startsWith("https://")) {
            return FilterUpdateResult(false, cachedRuleCount(), "Filter source URL must use HTTPS.")
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()

        return try {
            val req = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", "Pipra-FilterUpdater/2.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return FilterUpdateResult(false, cachedRuleCount(), "Update failed: HTTP ${resp.code}")
                }
                val body = resp.body ?: return FilterUpdateResult(false, cachedRuleCount(), "Empty response body.")
                val bytes = body.byteStream().use { input ->
                    val buf = ByteArray(8192)
                    val out = java.io.ByteArrayOutputStream()
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_FILTER_BYTES) break
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
                val text = String(bytes, Charsets.UTF_8)
                val parsed = parseFilterList(text)
                if (parsed.size < 25) {
                    return FilterUpdateResult(
                        false,
                        cachedRuleCount(),
                        "Downloaded list failed validation (only ${parsed.size} valid domains found)."
                    )
                }
                AtomicFiles.writeText(cacheFile, parsed.sorted().joinToString("\n"))
                cachedExternalDomains = parsed
                cachedMtime = cacheFile.lastModified()
                val now = System.currentTimeMillis()
                FilterUpdateResult(true, parsed.size, "Updated ${parsed.size} filter domains.", now)
            }
        } catch (e: Exception) {
            FilterUpdateResult(
                false,
                cachedRuleCount(),
                "Offline or update error (${e.javaClass.simpleName}). Previous rules preserved."
            )
        }
    }

    /** Parses hosts-format (`0.0.0.0 domain`), AdBlock (`||domain^`), or plain domain lines. */
    internal fun parseFilterList(rawText: String): HashSet<String> {
        val result = HashSet<String>(4096)
        val trimmedHead = rawText.trimStart().take(200).lowercase(Locale.ROOT)
        if (trimmedHead.startsWith("<!doctype html") || trimmedHead.startsWith("<html")) {
            return result
        }
        for (rawLine in rawText.lineSequence()) {
            var line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!") || line.startsWith("[")) continue
            // Strip inline comments
            val hashIdx = line.indexOf('#')
            if (hashIdx > 0) line = line.substring(0, hashIdx).trim()

            val candidate = when {
                line.startsWith("||") && line.endsWith("^") -> line.removePrefix("||").removeSuffix("^")
                line.startsWith("||") -> line.removePrefix("||").substringBefore('^').substringBefore('/')
                line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ") ->
                    line.substringAfter(' ').trim().substringBefore(' ').substringBefore('\t')
                line.startsWith("0.0.0.0\t") || line.startsWith("127.0.0.1\t") ->
                    line.substringAfter('\t').trim().substringBefore(' ').substringBefore('\t')
                line.contains(' ') || line.contains('\t') -> {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) parts[1] else ""
                }
                else -> line
            }
            if (candidate.isEmpty() || candidate == "localhost" || candidate == "localhost.localdomain") continue
            val norm = DomainUtils.normalizeHost(candidate) ?: continue
            result.add(norm)
        }
        return result
    }

    @Synchronized
    private fun ensureCacheLoaded() {
        if (!cacheFile.exists()) {
            cachedExternalDomains = HashSet()
            cachedMtime = -1L
            return
        }
        val mtime = cacheFile.lastModified()
        if (mtime == cachedMtime && cachedExternalDomains.isNotEmpty()) return
        val loaded = HashSet<String>()
        try {
            cacheFile.forEachLine(Charsets.UTF_8) { line ->
                val h = line.trim()
                if (h.isNotEmpty()) loaded.add(h)
            }
        } catch (_: Exception) {
            // Leave existing set if read fails
        }
        cachedExternalDomains = loaded
        cachedMtime = mtime
    }

    private fun matchesDomainSet(host: String, set: HashSet<String>): Boolean {
        var h = host.lowercase(Locale.ROOT)
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0 || dot == h.lastIndexOf('.')) return false
            h = h.substring(dot + 1)
        }
    }

    private fun isSameSite(a: String, b: String): Boolean {
        if (a == b) return true
        val rootA = registrableGuess(a)
        val rootB = registrableGuess(b)
        return rootA == rootB
    }

    private fun registrableGuess(host: String): String {
        val parts = host.split('.')
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
    }

    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
