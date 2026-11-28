package com.pipra.browser

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

enum class LockScope {
    LAUNCH,
    PRIVATE_WINDOW,
    SETTINGS
}

data class AppLockConfig(
    val hasPin: Boolean = false,
    val lockOnLaunch: Boolean = false,
    val lockPrivateWindow: Boolean = true,
    val lockSettings: Boolean = false,
    val allowDeviceCredential: Boolean = true
)

sealed class PinVerifyResult {
    data object Success : PinVerifyResult()
    data class Invalid(val remainingAttempts: Int) : PinVerifyResult()
    data class LockedOut(val remainingSeconds: Int) : PinVerifyResult()
}

/**
 * Local App Lock manager using salted SHA-256 PIN hashing and brute-force backoff.
 * Stored atomically in filesDir/pipra_applock.json so both normal & :incognito processes share the lock policy.
 */
class AppLockManager(context: Context) {

    private val lockFile = File(context.filesDir, "pipra_applock.json")

    companion object {
        private const val MAX_ATTEMPTS_BEFORE_LOCKOUT = 5
        private const val LOCKOUT_DURATION_MS = 30_000L
        private const val HASH_ROUNDS = 1024
    }

    @Synchronized
    fun config(): AppLockConfig {
        val o = readJson()
        val hasPin = o.optString("pinHash", "").isNotBlank() && o.optString("salt", "").isNotBlank()
        return AppLockConfig(
            hasPin = hasPin,
            lockOnLaunch = hasPin && o.optBoolean("lockOnLaunch", false),
            lockPrivateWindow = hasPin && o.optBoolean("lockPrivateWindow", true),
            lockSettings = hasPin && o.optBoolean("lockSettings", false),
            allowDeviceCredential = o.optBoolean("allowDeviceCredential", true)
        )
    }

    fun isLockRequired(scope: LockScope): Boolean {
        val cfg = config()
        if (!cfg.hasPin) return false
        return when (scope) {
            LockScope.LAUNCH -> cfg.lockOnLaunch
            LockScope.PRIVATE_WINDOW -> cfg.lockPrivateWindow
            LockScope.SETTINGS -> cfg.lockSettings
        }
    }

    @Synchronized
    fun setPin(pin: String): Result<Unit> {
        val trimmed = pin.trim()
        if (trimmed.length !in 4..8 || !trimmed.all { it.isDigit() }) {
            return Result.failure(IllegalArgumentException("PIN must be 4 to 8 digits."))
        }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        val hash = hashPin(trimmed, salt)
        val o = readJson()
        o.put("salt", salt)
        o.put("pinHash", hash)
        o.put("failedAttempts", 0)
        o.put("lockoutUntilMillis", 0L)
        if (!o.has("lockPrivateWindow")) o.put("lockPrivateWindow", true)
        AtomicFiles.writeText(lockFile, o.toString(2))
        return Result.success(Unit)
    }

    @Synchronized
    fun updateScopes(
        lockOnLaunch: Boolean,
        lockPrivateWindow: Boolean,
        lockSettings: Boolean,
        allowDeviceCredential: Boolean
    ) {
        val o = readJson()
        o.put("lockOnLaunch", lockOnLaunch)
        o.put("lockPrivateWindow", lockPrivateWindow)
        o.put("lockSettings", lockSettings)
        o.put("allowDeviceCredential", allowDeviceCredential)
        AtomicFiles.writeText(lockFile, o.toString(2))
    }

    @Synchronized
    fun verifyPin(pin: String, nowMillis: Long = System.currentTimeMillis()): PinVerifyResult {
        val o = readJson()
        val salt = o.optString("salt", "")
        val expectedHash = o.optString("pinHash", "")
        if (salt.isBlank() || expectedHash.isBlank()) return PinVerifyResult.Success

        val lockoutUntil = o.optLong("lockoutUntilMillis", 0L)
        if (lockoutUntil > nowMillis) {
            val secs = (((lockoutUntil - nowMillis) + 999L) / 1000L).toInt().coerceAtLeast(1)
            return PinVerifyResult.LockedOut(secs)
        }

        val candidate = hashPin(pin.trim(), salt)
        if (constantTimeEquals(candidate, expectedHash)) {
            o.put("failedAttempts", 0)
            o.put("lockoutUntilMillis", 0L)
            AtomicFiles.writeText(lockFile, o.toString(2))
            return PinVerifyResult.Success
        }

        val failed = o.optInt("failedAttempts", 0) + 1
        if (failed >= MAX_ATTEMPTS_BEFORE_LOCKOUT) {
            o.put("failedAttempts", 0)
            o.put("lockoutUntilMillis", nowMillis + LOCKOUT_DURATION_MS)
            AtomicFiles.writeText(lockFile, o.toString(2))
            return PinVerifyResult.LockedOut((LOCKOUT_DURATION_MS / 1000L).toInt())
        } else {
            o.put("failedAttempts", failed)
            AtomicFiles.writeText(lockFile, o.toString(2))
            return PinVerifyResult.Invalid(MAX_ATTEMPTS_BEFORE_LOCKOUT - failed)
        }
    }

    @Synchronized
    fun disableLock(currentPin: String, nowMillis: Long = System.currentTimeMillis()): PinVerifyResult {
        val verify = verifyPin(currentPin, nowMillis)
        if (verify !is PinVerifyResult.Success) return verify
        if (lockFile.exists()) lockFile.delete()
        return PinVerifyResult.Success
    }

    private fun hashPin(pin: String, saltHex: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var current = "$saltHex:$pin".toByteArray(Charsets.UTF_8)
        repeat(HASH_ROUNDS) {
            digest.reset()
            digest.update(current)
            digest.update(saltHex.toByteArray(Charsets.UTF_8))
            current = digest.digest()
        }
        return current.toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private fun readJson(): JSONObject {
        if (!lockFile.exists()) return JSONObject()
        return try {
            JSONObject(lockFile.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            JSONObject()
        }
    }
}
