package com.pipra.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class SitePermissionType(val key: String, val label: String) {
    GEOLOCATION("location", "Location"),
    CAMERA("camera", "Camera"),
    MICROPHONE("microphone", "Microphone"),
    NOTIFICATIONS("notifications", "Notifications"),
    MEDIA_AUTOPLAY("autoplay", "Media Autoplay");

    companion object {
        fun fromKey(raw: String?): SitePermissionType? =
            entries.firstOrNull { it.key.equals(raw, ignoreCase = true) }
    }
}

enum class SitePermissionDecision(val key: String, val label: String) {
    ASK("ask", "Ask"),
    ALLOW("allow", "Allow"),
    DENY("deny", "Block");

    companion object {
        fun fromKey(raw: String?): SitePermissionDecision =
            entries.firstOrNull { it.key.equals(raw, ignoreCase = true) } ?: ASK
    }
}

data class SitePermissionEntry(
    val host: String,
    val type: SitePermissionType,
    val decision: SitePermissionDecision
)

/**
 * Per-site permissions storage for Camera, Microphone, Location, Notifications, and Autoplay.
 * Persisted atomically in filesDir/pipra_site_permissions.json.
 */
class SitePermissionManager(context: Context) {

    private val permFile = File(context.filesDir, "pipra_site_permissions.json")

    @Synchronized
    fun listEntries(): List<SitePermissionEntry> {
        val arr = readJson().optJSONArray("entries") ?: return emptyList()
        val out = ArrayList<SitePermissionEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val host = DomainUtils.normalizeHost(o.optString("host", "")) ?: continue
            val type = SitePermissionType.fromKey(o.optString("type", "")) ?: continue
            val decision = SitePermissionDecision.fromKey(o.optString("decision", ""))
            if (decision != SitePermissionDecision.ASK) {
                out.add(SitePermissionEntry(host, type, decision))
            }
        }
        return out.sortedWith(compareBy({ it.host }, { it.type.label }))
    }

    @Synchronized
    fun getDecision(rawHostOrUrl: String, type: SitePermissionType): SitePermissionDecision {
        val host = DomainUtils.extractHostFromUrl(rawHostOrUrl)
            ?: DomainUtils.normalizeHost(rawHostOrUrl)
            ?: return SitePermissionDecision.ASK
        return listEntries().firstOrNull { it.host == host && it.type == type }?.decision
            ?: SitePermissionDecision.ASK
    }

    @Synchronized
    fun setDecision(rawHostOrUrl: String, type: SitePermissionType, decision: SitePermissionDecision) {
        val host = DomainUtils.extractHostFromUrl(rawHostOrUrl)
            ?: DomainUtils.normalizeHost(rawHostOrUrl)
            ?: return
        val next = listEntries().filterNot { it.host == host && it.type == type }.toMutableList()
        if (decision != SitePermissionDecision.ASK) {
            next.add(SitePermissionEntry(host, type, decision))
        }
        saveEntries(next)
    }

    @Synchronized
    fun removeEntry(host: String, type: SitePermissionType) {
        val next = listEntries().filterNot { it.host == host && it.type == type }
        saveEntries(next)
    }

    @Synchronized
    fun clearAll() {
        saveEntries(emptyList())
    }

    private fun saveEntries(list: List<SitePermissionEntry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject()
                    .put("host", e.host)
                    .put("type", e.type.key)
                    .put("decision", e.decision.key)
            )
        }
        val root = JSONObject().put("entries", arr)
        AtomicFiles.writeText(permFile, root.toString(2))
    }

    private fun readJson(): JSONObject {
        if (!permFile.exists()) return JSONObject()
        return try {
            JSONObject(permFile.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            JSONObject()
        }
    }
}
