package com.pipra.browser

import android.content.Context
import org.json.JSONObject
import java.io.File

enum class SecureDnsProfile(val key: String, val title: String, val endpoint: String, val summary: String) {
    OFF(
        key = "off",
        title = "Off (System DNS)",
        endpoint = "",
        summary = "Use normal system or carrier DNS resolution."
    ),
    LOW(
        key = "low",
        title = "Kahf Guard Low",
        endpoint = "https://low.kahfguard.com/dns-query",
        summary = "Basic DNS resolution with zero filtering."
    ),
    MEDIUM(
        key = "medium",
        title = "Kahf Guard Medium (Recommended)",
        endpoint = "https://medium.kahfguard.com/dns-query",
        summary = "Blocks adult content, malware, and major phishing domains."
    ),
    HIGH(
        key = "high",
        title = "Kahf Guard High",
        endpoint = "https://high.kahfguard.com/dns-query",
        summary = "Strict filtering: blocks adult content, malware, phishing, ads, and gambling."
    ),
    CUSTOM(
        key = "custom",
        title = "Custom DNS-over-HTTPS",
        endpoint = "",
        summary = "User-supplied DoH resolver URL."
    );

    companion object {
        fun fromKey(key: String?): SecureDnsProfile =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: MEDIUM
    }
}

class SecureDnsManager(private val context: Context) {
    private val configFile = File(context.filesDir, "pipra_secure_dns.json")

    @Synchronized
    fun getProfile(): SecureDnsProfile {
        if (!configFile.exists()) return SecureDnsProfile.MEDIUM
        return try {
            val obj = JSONObject(configFile.readText(Charsets.UTF_8))
            SecureDnsProfile.fromKey(obj.optString("profile", "medium"))
        } catch (_: Exception) {
            SecureDnsProfile.MEDIUM
        }
    }

    @Synchronized
    fun setProfile(profile: SecureDnsProfile) {
        val obj = try {
            if (configFile.exists()) JSONObject(configFile.readText(Charsets.UTF_8)) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
        obj.put("profile", profile.key)
        AtomicFiles.writeText(configFile, obj.toString(2))
    }

    @Synchronized
    fun getCustomEndpoint(): String {
        if (!configFile.exists()) return ""
        return try {
            val obj = JSONObject(configFile.readText(Charsets.UTF_8))
            obj.optString("custom_endpoint", "")
        } catch (_: Exception) {
            ""
        }
    }

    @Synchronized
    fun setCustomEndpoint(url: String) {
        val clean = url.trim()
        val obj = try {
            if (configFile.exists()) JSONObject(configFile.readText(Charsets.UTF_8)) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
        obj.put("custom_endpoint", clean)
        AtomicFiles.writeText(configFile, obj.toString(2))
    }

    fun activeEndpoint(): String {
        val p = getProfile()
        return when (p) {
            SecureDnsProfile.OFF -> ""
            SecureDnsProfile.CUSTOM -> getCustomEndpoint()
            else -> p.endpoint
        }
    }
}
