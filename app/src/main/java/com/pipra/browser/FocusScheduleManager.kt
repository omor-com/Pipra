package com.pipra.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

data class FocusScheduleRule(
    val id: String,
    val label: String,
    val hosts: List<String>,
    val includeSubdomains: Boolean = true,
    val daysOfWeekMask: Int = ALL_DAYS_MASK, // bit 0=Sun..6=Sat (Calendar.DAY_OF_WEEK - 1)
    val startMinuteOfDay: Int = 9 * 60,
    val endMinuteOfDay: Int = 17 * 60,
    val enabled: Boolean = true
) {
    companion object {
        const val ALL_DAYS_MASK = 0b1111111
        const val WEEKDAYS_MASK = 0b0111110 // Mon..Fri (bits 1..5)
    }

    fun isActiveAt(nowMillis: Long): Boolean {
        if (!enabled || hosts.isEmpty()) return false
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val dayBit = (cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY).coerceIn(0, 6)
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        return if (startMinuteOfDay <= endMinuteOfDay) {
            ((daysOfWeekMask shr dayBit) and 1) == 1 && minute in startMinuteOfDay until endMinuteOfDay
        } else {
            // Overnight window (e.g. 22:00 .. 06:00): after start on today OR before end from yesterday
            val prevDayBit = (dayBit + 6) % 7
            val inLatePart = ((daysOfWeekMask shr dayBit) and 1) == 1 && minute >= startMinuteOfDay
            val inEarlyPart = ((daysOfWeekMask shr prevDayBit) and 1) == 1 && minute < endMinuteOfDay
            inLatePart || inEarlyPart
        }
    }

    fun formattedWindow(): String {
        val sh = startMinuteOfDay / 60
        val sm = startMinuteOfDay % 60
        val eh = endMinuteOfDay / 60
        val em = endMinuteOfDay % 60
        return String.format(Locale.US, "%02d:%02d – %02d:%02d", sh, sm, eh, em)
    }
}

data class PomodoroSessionState(
    val activeUntilMillis: Long = 0L,
    val durationMinutes: Int = 25,
    val blockedHosts: List<String> = emptyList()
) {
    fun isRunning(nowMillis: Long = System.currentTimeMillis()): Boolean =
        activeUntilMillis > nowMillis && blockedHosts.isNotEmpty()

    fun remainingMinutes(nowMillis: Long = System.currentTimeMillis()): Int {
        val rem = (activeUntilMillis - nowMillis).coerceAtLeast(0L)
        return ((rem + 59_999L) / 60_000L).toInt()
    }
}

data class FocusMatch(
    val label: String,
    val detail: String,
    val isPomodoro: Boolean
)

/**
 * Time-based Focus Schedule & Pomodoro distraction blocker.
 * Stored atomically in filesDir/pipra_focus_schedule.json for cross-process enforcement.
 * Completely separate from WebsiteBlocker's immutable 7–365 day locks.
 */
class FocusScheduleManager(context: Context) {

    private val focusFile = File(context.filesDir, "pipra_focus_schedule.json")

    @Synchronized
    fun rules(): List<FocusScheduleRule> {
        val obj = readJson()
        val arr = obj.optJSONArray("rules") ?: return emptyList()
        val out = ArrayList<FocusScheduleRule>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val hostsArr = o.optJSONArray("hosts") ?: JSONArray()
            val hosts = mutableListOf<String>()
            for (j in 0 until hostsArr.length()) {
                DomainUtils.normalizeHost(hostsArr.optString(j, ""))?.let { hosts.add(it) }
            }
            if (hosts.isEmpty()) continue
            out.add(
                FocusScheduleRule(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    label = o.optString("label", "Focus Window").ifBlank { "Focus Window" },
                    hosts = hosts,
                    includeSubdomains = o.optBoolean("includeSubdomains", true),
                    daysOfWeekMask = o.optInt("daysOfWeekMask", FocusScheduleRule.ALL_DAYS_MASK),
                    startMinuteOfDay = o.optInt("startMinuteOfDay", 9 * 60).coerceIn(0, 1439),
                    endMinuteOfDay = o.optInt("endMinuteOfDay", 17 * 60).coerceIn(0, 1439),
                    enabled = o.optBoolean("enabled", true)
                )
            )
        }
        return out
    }

    @Synchronized
    fun pomodoroState(): PomodoroSessionState {
        val obj = readJson().optJSONObject("pomodoro") ?: return PomodoroSessionState()
        val hostsArr = obj.optJSONArray("blockedHosts") ?: JSONArray()
        val hosts = mutableListOf<String>()
        for (i in 0 until hostsArr.length()) {
            DomainUtils.normalizeHost(hostsArr.optString(i, ""))?.let { hosts.add(it) }
        }
        return PomodoroSessionState(
            activeUntilMillis = obj.optLong("activeUntilMillis", 0L),
            durationMinutes = obj.optInt("durationMinutes", 25).coerceIn(5, 180),
            blockedHosts = hosts
        )
    }

    @Synchronized
    fun addRule(
        label: String,
        hostsInput: String,
        includeSubdomains: Boolean,
        daysOfWeekMask: Int,
        startMinuteOfDay: Int,
        endMinuteOfDay: Int
    ): Result<FocusScheduleRule> {
        val parsedHosts = parseHostList(hostsInput)
        if (parsedHosts.isEmpty()) {
            return Result.failure(IllegalArgumentException("Enter at least one valid domain (e.g. youtube.com, reddit.com)."))
        }
        if (startMinuteOfDay == endMinuteOfDay) {
            return Result.failure(IllegalArgumentException("Start and end time cannot be the same."))
        }
        val cleanMask = if (daysOfWeekMask == 0) FocusScheduleRule.ALL_DAYS_MASK else daysOfWeekMask
        val rule = FocusScheduleRule(
            id = UUID.randomUUID().toString(),
            label = label.trim().ifBlank { "Focus Session" },
            hosts = parsedHosts,
            includeSubdomains = includeSubdomains,
            daysOfWeekMask = cleanMask,
            startMinuteOfDay = startMinuteOfDay.coerceIn(0, 1439),
            endMinuteOfDay = endMinuteOfDay.coerceIn(0, 1439),
            enabled = true
        )
        val next = listOf(rule) + rules()
        saveAll(next, pomodoroState())
        return Result.success(rule)
    }

    @Synchronized
    fun toggleRuleEnabled(id: String, enabled: Boolean) {
        val next = rules().map { if (it.id == id) it.copy(enabled = enabled) else it }
        saveAll(next, pomodoroState())
    }

    @Synchronized
    fun deleteRule(id: String) {
        val next = rules().filterNot { it.id == id }
        saveAll(next, pomodoroState())
    }

    @Synchronized
    fun startPomodoro(
        durationMinutes: Int,
        hostsInput: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Result<PomodoroSessionState> {
        val parsed = parseHostList(hostsInput)
        if (parsed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Enter at least one domain for your Pomodoro block list."))
        }
        val mins = durationMinutes.coerceIn(5, 180)
        val state = PomodoroSessionState(
            activeUntilMillis = nowMillis + mins * 60_000L,
            durationMinutes = mins,
            blockedHosts = parsed
        )
        saveAll(rules(), state)
        return Result.success(state)
    }

    @Synchronized
    fun stopPomodoro() {
        saveAll(rules(), PomodoroSessionState())
    }

    fun findActiveBlock(url: String, nowMillis: Long = System.currentTimeMillis()): FocusMatch? {
        val host = DomainUtils.extractHostFromUrl(url) ?: return null
        return findActiveBlockForHost(host, nowMillis)
    }

    fun findActiveBlockForHost(rawHost: String, nowMillis: Long = System.currentTimeMillis()): FocusMatch? {
        val host = DomainUtils.normalizeHost(rawHost) ?: return null
        val pomo = pomodoroState()
        if (pomo.isRunning(nowMillis) && pomo.blockedHosts.any { DomainUtils.matchesHost(host, it, true) }) {
            val endStr = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(pomo.activeUntilMillis))
            return FocusMatch(
                label = "Pomodoro Focus (${pomo.remainingMinutes(nowMillis)}m left)",
                detail = "Active until $endStr",
                isPomodoro = true
            )
        }
        val activeRule = rules().firstOrNull { rule ->
            rule.isActiveAt(nowMillis) && rule.hosts.any { DomainUtils.matchesHost(host, it, rule.includeSubdomains) }
        } ?: return null
        return FocusMatch(
            label = activeRule.label,
            detail = "Scheduled focus window ${activeRule.formattedWindow()}",
            isPomodoro = false
        )
    }

    fun parseHostList(raw: String): List<String> {
        return raw.split(',', '\n', ' ')
            .mapNotNull { token -> DomainUtils.normalizeHost(token) }
            .distinct()
    }

    private fun saveAll(rules: List<FocusScheduleRule>, pomodoro: PomodoroSessionState) {
        val root = JSONObject()
        val arr = JSONArray()
        for (r in rules) {
            val hArr = JSONArray()
            r.hosts.forEach { hArr.put(it) }
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("label", r.label)
                    .put("hosts", hArr)
                    .put("includeSubdomains", r.includeSubdomains)
                    .put("daysOfWeekMask", r.daysOfWeekMask)
                    .put("startMinuteOfDay", r.startMinuteOfDay)
                    .put("endMinuteOfDay", r.endMinuteOfDay)
                    .put("enabled", r.enabled)
            )
        }
        root.put("rules", arr)

        val pHosts = JSONArray()
        pomodoro.blockedHosts.forEach { pHosts.put(it) }
        root.put(
            "pomodoro",
            JSONObject()
                .put("activeUntilMillis", pomodoro.activeUntilMillis)
                .put("durationMinutes", pomodoro.durationMinutes)
                .put("blockedHosts", pHosts)
        )
        AtomicFiles.writeText(focusFile, root.toString(2))
    }

    private fun readJson(): JSONObject {
        if (!focusFile.exists()) return JSONObject()
        return try {
            JSONObject(focusFile.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            JSONObject()
        }
    }
}
