package com.pipra.browser

import android.content.Context
import org.json.JSONObject
import java.io.File

data class PrivacyStatsSnapshot(
    val adsBlocked: Long = 0L,
    val trackersBlocked: Long = 0L,
    val cookiesBlocked: Long = 0L,
    val fingerprintersBlocked: Long = 0L,
    val httpsUpgrades: Long = 0L,
    val topBlockedDomains: List<Pair<String, Long>> = emptyList(),
    val updatedAtMillis: Long = 0L
) {
    val totalBlocked: Long
        get() = adsBlocked + trackersBlocked + cookiesBlocked + fingerprintersBlocked
}

/**
 * Persistent on-device Privacy Shield statistics tracker.
 * Aggregates ad-blocker subresource blocks, GeckoView ContentBlocking events,
 * and HTTPS-Only upgrades atomically into filesDir/pipra_privacy_stats.json.
 */
class PrivacyStatsManager(context: Context) {

    private val statsFile = File(context.filesDir, "pipra_privacy_stats.json")

    @Synchronized
    fun snapshot(): PrivacyStatsSnapshot {
        val obj = readJson()
        val domainsObj = obj.optJSONObject("domains") ?: JSONObject()
        val domains = mutableListOf<Pair<String, Long>>()
        val keys = domainsObj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = domainsObj.optLong(k, 0L)
            if (k.isNotBlank() && v > 0L) domains += k to v
        }
        return PrivacyStatsSnapshot(
            adsBlocked = obj.optLong("adsBlocked", 0L),
            trackersBlocked = obj.optLong("trackersBlocked", 0L),
            cookiesBlocked = obj.optLong("cookiesBlocked", 0L),
            fingerprintersBlocked = obj.optLong("fingerprintersBlocked", 0L),
            httpsUpgrades = obj.optLong("httpsUpgrades", 0L),
            topBlockedDomains = domains.sortedByDescending { it.second }.take(15),
            updatedAtMillis = obj.optLong("updatedAtMillis", 0L)
        )
    }

    @Synchronized
    fun recordAdBlock(blockedUrlOrHost: String) {
        recordEvent(categoryKey = "adsBlocked", rawHostOrUrl = blockedUrlOrHost)
    }

    @Synchronized
    fun recordTrackerBlock(blockedUrlOrHost: String) {
        recordEvent(categoryKey = "trackersBlocked", rawHostOrUrl = blockedUrlOrHost)
    }

    @Synchronized
    fun recordCookieBlock(blockedUrlOrHost: String) {
        recordEvent(categoryKey = "cookiesBlocked", rawHostOrUrl = blockedUrlOrHost)
    }

    @Synchronized
    fun recordFingerprinterBlock(blockedUrlOrHost: String) {
        recordEvent(categoryKey = "fingerprintersBlocked", rawHostOrUrl = blockedUrlOrHost)
    }

    @Synchronized
    fun recordHttpsUpgrade(hostOrUrl: String) {
        val obj = readJson()
        obj.put("httpsUpgrades", obj.optLong("httpsUpgrades", 0L) + 1L)
        obj.put("updatedAtMillis", System.currentTimeMillis())
        AtomicFiles.writeText(statsFile, obj.toString(2))
    }

    @Synchronized
    fun resetStats() {
        AtomicFiles.writeText(statsFile, JSONObject().toString(2))
    }

    private fun recordEvent(categoryKey: String, rawHostOrUrl: String) {
        val obj = readJson()
        obj.put(categoryKey, obj.optLong(categoryKey, 0L) + 1L)
        obj.put("updatedAtMillis", System.currentTimeMillis())

        val host = DomainUtils.extractHostFromUrl(rawHostOrUrl)
            ?: DomainUtils.normalizeHost(rawHostOrUrl)
        if (!host.isNullOrBlank()) {
            val domains = obj.optJSONObject("domains") ?: JSONObject()
            domains.put(host, domains.optLong(host, 0L) + 1L)
            if (domains.length() > 60) {
                val pruned = JSONObject()
                val sorted = mutableListOf<Pair<String, Long>>()
                val keys = domains.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    sorted += k to domains.optLong(k, 0L)
                }
                sorted.sortedByDescending { it.second }.take(40).forEach { (k, v) ->
                    pruned.put(k, v)
                }
                obj.put("domains", pruned)
            } else {
                obj.put("domains", domains)
            }
        }
        AtomicFiles.writeText(statsFile, obj.toString(2))
    }

    private fun readJson(): JSONObject {
        if (!statsFile.exists()) return JSONObject()
        return try {
            JSONObject(statsFile.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            JSONObject()
        }
    }
}
