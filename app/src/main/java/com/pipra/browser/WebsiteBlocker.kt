package com.pipra.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class BlockedSiteRule(
    val id: String,
    val host: String,
    val includeSubdomains: Boolean,
    val lockDays: Int,
    val createdAtMillis: Long,
    val lockUntilMillis: Long
) {
    fun isLocked(nowMillis: Long = System.currentTimeMillis()): Boolean = nowMillis < lockUntilMillis

    fun remainingMillis(nowMillis: Long = System.currentTimeMillis()): Long =
        (lockUntilMillis - nowMillis).coerceAtLeast(0L)
}

sealed class RuleActionResult {
    data class Success(val rule: BlockedSiteRule? = null) : RuleActionResult()
    data class Error(val message: String) : RuleActionResult()
}

/**
 * Persistent, tamper-resistant Website Blocking engine with mandatory 7–365 day locks.
 *
 * Key guarantees:
 * - Hostnames are validated and normalized via [DomainUtils.normalizeHost].
 * - Lock periods are strictly validated (7..365 days) inside the engine.
 * - Active locks block editing and deletion at the engine/persistence layer, not merely in UI controls.
 * - Rules are stored atomically in filesDir with a local HMAC-SHA256 integrity envelope.
 * - Multi-process safe: invalidates in-memory cache whenever the file's mtime changes, so
 *   both normal and :incognito processes enforce identical rules immediately.
 */
class WebsiteBlocker(context: Context) {

    private val rulesFile = File(context.filesDir, "pipra_blocked_sites.json")
    private val keyFile = File(context.filesDir, "pipra_rule_hmac.key")

    @Volatile
    private var cachedMtime: Long = -1L

    @Volatile
    private var cachedRules: List<BlockedSiteRule> = emptyList()

    @Volatile
    private var integrityWarning: Boolean = false

    companion object {
        const val MIN_LOCK_DAYS = 7
        const val MAX_LOCK_DAYS = 365
        const val DAY_MILLIS = 86_400_000L
    }

    @Synchronized
    fun rules(): List<BlockedSiteRule> {
        if (!rulesFile.exists()) {
            cachedMtime = -1L
            cachedRules = emptyList()
            integrityWarning = false
            return emptyList()
        }
        val mtime = rulesFile.lastModified()
        if (mtime == cachedMtime && cachedRules.isNotEmpty()) {
            return cachedRules
        }
        val loaded = loadFromDisk()
        cachedRules = loaded
        cachedMtime = mtime
        return loaded
    }

    fun hasIntegrityWarning(): Boolean = integrityWarning

    /**
     * Checks whether [url] matches any saved website blocking rule.
     * Rules continue blocking after lock expiry until the user explicitly removes or edits them.
     */
    fun findMatchingRuleForUrl(url: String): BlockedSiteRule? {
        val host = DomainUtils.extractHostFromUrl(url) ?: return null
        return findMatchingRuleForHost(host)
    }

    fun findMatchingRuleForHost(rawHost: String): BlockedSiteRule? {
        val host = DomainUtils.normalizeHost(rawHost)
            ?: rawHost.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (host.isEmpty()) return null
        return rules().firstOrNull { rule ->
            DomainUtils.matchesHost(host, rule.host, rule.includeSubdomains)
        }
    }

    @Synchronized
    fun addRule(
        rawInput: String,
        includeSubdomains: Boolean,
        lockDays: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): RuleActionResult {
        val host = DomainUtils.normalizeHost(rawInput)
            ?: return RuleActionResult.Error("Enter a valid domain name or URL (for example: m.youtube.com).")

        if (lockDays !in MIN_LOCK_DAYS..MAX_LOCK_DAYS) {
            return RuleActionResult.Error("Lock duration must be between $MIN_LOCK_DAYS and $MAX_LOCK_DAYS days.")
        }

        val current = rules().toMutableList()
        // Reject exact duplicates and subdomain conflicts.
        for (existing in current) {
            if (existing.host == host && existing.includeSubdomains == includeSubdomains) {
                return RuleActionResult.Error("$host is already in your blocked list.")
            }
            if (existing.host == host && existing.includeSubdomains != includeSubdomains) {
                return RuleActionResult.Error(
                    if (existing.includeSubdomains) {
                        "$host (including subdomains) is already blocked."
                    } else {
                        "An exact rule for $host already exists. Remove or wait for its lock before replacing scope."
                    }
                )
            }
            if (existing.includeSubdomains && DomainUtils.matchesHost(host, existing.host, true)) {
                return RuleActionResult.Error("$host is already covered by subdomain rule ${existing.host}.")
            }
            if (includeSubdomains && DomainUtils.matchesHost(existing.host, host, true) && existing.isLocked(nowMillis)) {
                return RuleActionResult.Error(
                    "Subdomain rule ${existing.host} is currently locked and overlaps with $host."
                )
            }
        }

        val lockUntil = nowMillis + lockDays.toLong() * DAY_MILLIS
        val newRule = BlockedSiteRule(
            id = UUID.randomUUID().toString(),
            host = host,
            includeSubdomains = includeSubdomains,
            lockDays = lockDays,
            createdAtMillis = nowMillis,
            lockUntilMillis = lockUntil
        )
        current.add(0, newRule)
        persistRules(current)
        return RuleActionResult.Success(newRule)
    }

    @Synchronized
    fun updateRule(
        ruleId: String,
        rawInput: String,
        includeSubdomains: Boolean,
        lockDays: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): RuleActionResult {
        val current = rules().toMutableList()
        val idx = current.indexOfFirst { it.id == ruleId }
        if (idx < 0) return RuleActionResult.Error("Rule not found.")

        val existing = current[idx]
        if (existing.isLocked(nowMillis)) {
            return RuleActionResult.Error(
                "This rule is locked (${formatRemaining(existing.lockUntilMillis, nowMillis)}) and cannot be modified until the lock expires."
            )
        }

        val host = DomainUtils.normalizeHost(rawInput)
            ?: return RuleActionResult.Error("Enter a valid domain name or URL.")

        if (lockDays !in MIN_LOCK_DAYS..MAX_LOCK_DAYS) {
            return RuleActionResult.Error("Lock duration must be between $MIN_LOCK_DAYS and $MAX_LOCK_DAYS days.")
        }

        val others = current.filterNot { it.id == ruleId }
        if (others.any { it.host == host && it.includeSubdomains == includeSubdomains }) {
            return RuleActionResult.Error("A rule for $host already exists.")
        }

        val updated = BlockedSiteRule(
            id = existing.id,
            host = host,
            includeSubdomains = includeSubdomains,
            lockDays = lockDays,
            createdAtMillis = nowMillis,
            lockUntilMillis = nowMillis + lockDays.toLong() * DAY_MILLIS
        )
        current[idx] = updated
        persistRules(current)
        return RuleActionResult.Success(updated)
    }

    @Synchronized
    fun deleteRule(
        ruleId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): RuleActionResult {
        val current = rules().toMutableList()
        val existing = current.firstOrNull { it.id == ruleId }
            ?: return RuleActionResult.Error("Rule not found.")

        if (existing.isLocked(nowMillis)) {
            return RuleActionResult.Error(
                "Active lock: ${existing.host} cannot be removed until ${formatUnlockDate(existing.lockUntilMillis)}."
            )
        }

        current.removeAll { it.id == ruleId }
        persistRules(current)
        return RuleActionResult.Success(existing)
    }

    private fun persistRules(list: List<BlockedSiteRule>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("host", r.host)
                    .put("includeSubdomains", r.includeSubdomains)
                    .put("lockDays", r.lockDays)
                    .put("createdAtMillis", r.createdAtMillis)
                    .put("lockUntilMillis", r.lockUntilMillis)
            )
        }
        val payload = arr.toString()
        val hmac = computeHmac(payload)
        val envelope = JSONObject()
            .put("version", 1)
            .put("payload", payload)
            .put("hmac", hmac)
        AtomicFiles.writeText(rulesFile, envelope.toString(2))
        cachedRules = list.toList()
        cachedMtime = rulesFile.lastModified()
        integrityWarning = false
    }

    private fun loadFromDisk(): List<BlockedSiteRule> {
        return try {
            val raw = rulesFile.readText(Charsets.UTF_8)
            val envelope = JSONObject(raw)
            val payload = envelope.optString("payload", "[]")
            val storedHmac = envelope.optString("hmac", "")
            val expectedHmac = computeHmac(payload)
            integrityWarning = storedHmac.isNotEmpty() && !constantTimeEquals(storedHmac, expectedHmac)

            val arr = JSONArray(payload)
            val out = ArrayList<BlockedSiteRule>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "").ifBlank { continue }
                val host = DomainUtils.normalizeHost(o.optString("host", "")) ?: continue
                val includeSub = o.optBoolean("includeSubdomains", false)
                val lockDays = o.optInt("lockDays", MIN_LOCK_DAYS).coerceIn(MIN_LOCK_DAYS, MAX_LOCK_DAYS)
                val created = o.optLong("createdAtMillis", 0L)
                val until = o.optLong("lockUntilMillis", created + lockDays.toLong() * DAY_MILLIS)
                out.add(BlockedSiteRule(id, host, includeSub, lockDays, created, until))
            }
            out
        } catch (_: Exception) {
            integrityWarning = true
            emptyList()
        }
    }

    private fun getOrCreateKey(): ByteArray {
        if (keyFile.exists() && keyFile.length() == 32L) {
            return keyFile.readBytes()
        }
        val key = ByteArray(32)
        SecureRandom().nextBytes(key)
        keyFile.parentFile?.mkdirs()
        keyFile.writeBytes(key)
        return key
    }

    private fun computeHmac(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(getOrCreateKey(), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    fun formatRemaining(lockUntilMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val rem = lockUntilMillis - nowMillis
        if (rem <= 0L) return "Lock expired · Editable"
        val totalMinutes = (rem / 60_000L).coerceAtLeast(1L)
        val days = totalMinutes / (60L * 24L)
        val hours = (totalMinutes % (60L * 24L)) / 60L
        val mins = totalMinutes % 60L
        return when {
            days > 0 -> "${days}d ${hours}h remaining"
            hours > 0 -> "${hours}h ${mins}m remaining"
            else -> "${mins}m remaining"
        }
    }

    fun formatUnlockDate(lockUntilMillis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lockUntilMillis))
}
