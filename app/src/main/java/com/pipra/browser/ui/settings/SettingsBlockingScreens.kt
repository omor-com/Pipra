package com.pipra.browser.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pipra.browser.AdBlockMode
import com.pipra.browser.AdBlockerEngine
import com.pipra.browser.AdultBlockerEngine
import com.pipra.browser.BlockedSiteRule
import com.pipra.browser.DomainUtils
import com.pipra.browser.RuleActionResult
import com.pipra.browser.SettingsUiState
import com.pipra.browser.SettingsViewModel
import com.pipra.browser.WebsiteBlocker
import com.pipra.browser.ui.theme.PipraColors
import kotlin.math.roundToInt

@Composable
fun AdBlockerSettingsScreen(state: SettingsUiState, vm: SettingsViewModel) {
    var exceptionInput by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("adblock_list"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            SectionCard("Master Control") {
                SettingSwitchRow(
                    title = "Enable Ad Blocker",
                    subtitle = "Block third-party ad networks, trackers, and analytics off-device",
                    checked = state.adBlockEnabled,
                    testTag = "adblock_master_switch",
                    onCheckedChange = vm::setAdBlockEnabled
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Total blocked requests: ${state.adBlockTotalBlocked}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PipraColors.ElectricBlueLight
                )
            }
        }
        item {
            Text(
                text = "Filtering Mode",
                style = MaterialTheme.typography.labelMedium,
                color = PipraColors.ElectricBlueLight,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
        items(AdBlockMode.entries, key = { it.key }) { mode ->
            val selected = state.adBlockMode == mode
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected,
                        onClick = { vm.setAdBlockMode(mode) },
                        role = Role.RadioButton
                    )
                    .testTag("adblock_mode_${mode.key}"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = PipraColors.Charcoal),
                border = BorderStroke(
                    1.dp,
                    if (selected) PipraColors.ElectricBlue.copy(alpha = 0.7f) else PipraColors.SilverBorder
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = PipraColors.ElectricBlue,
                            unselectedColor = PipraColors.SilverMuted
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "${mode.label} (${vm.adBlocker.totalBuiltinCount(mode)} built-in domains)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = PipraColors.SilverPrimary
                        )
                        Text(
                            text = mode.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = PipraColors.SilverMuted
                        )
                    }
                }
            }
        }
        item {
            SectionCard("Filter Lists & Offline Cache") {
                Text(
                    text = "Last successful update: ${formatTimestamp(state.adBlockLastUpdatedMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PipraColors.SilverPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Cached external domains: ${state.adBlockCachedCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PipraColors.SilverMuted
                )
                Spacer(Modifier.height(10.dp))
                AdBlockerEngine.PRESET_SOURCES.forEach { (label, url) ->
                    val active = state.adBlockListUrl == url
                    FilterChip(
                        selected = active,
                        onClick = { vm.setAdBlockListUrl(url) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PipraColors.ElectricBlue.copy(alpha = 0.2f),
                            selectedLabelColor = PipraColors.ElectricBlueLight,
                            labelColor = PipraColors.SilverMuted
                        ),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { vm.updateAdBlockLists() },
                    enabled = !state.adBlockUpdating,
                    colors = ButtonDefaults.buttonColors(containerColor = PipraColors.ElectricBlue),
                    modifier = Modifier.testTag("adblock_update_button")
                ) {
                    if (state.adBlockUpdating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = PipraColors.SilverPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Updating…")
                    } else {
                        Text("Update Filter Lists Now")
                    }
                }
                state.adBlockUpdateStatus?.let { status ->
                    Spacer(Modifier.height(8.dp))
                    Text(status, style = MaterialTheme.typography.bodySmall, color = PipraColors.ElectricBlueLight)
                }
            }
        }
        item {
            SectionCard("Per-Site Exceptions") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = exceptionInput,
                        onValueChange = { exceptionInput = it },
                        label = { Text("Allowlist hostname (e.g. news.example.com)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("adblock_exception_input"),
                        colors = pipraTextFieldColors()
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (vm.addAdBlockException(exceptionInput)) exceptionInput = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PipraColors.ElectricBlue),
                        modifier = Modifier.testTag("adblock_exception_add")
                    ) {
                        Text("Add")
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (state.adBlockExceptions.isEmpty()) {
                    Text(
                        "No per-site exceptions. Ad Blocker protects all sites.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PipraColors.SilverMuted
                    )
                } else {
                    state.adBlockExceptions.sorted().forEach { host ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = host,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = PipraColors.SilverPrimary
                            )
                            IconButton(onClick = { vm.removeAdBlockException(host) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove $host", tint = PipraColors.SilverMuted)
                            }
                        }
                    }
                }
            }
        }
        item {
            InfoBanner(
                title = "Honest Scope Note",
                body = "No browser-level blocker can guarantee blocking every first-party or stream-embedded video advertisement. Cached lists remain active offline, and failed updates preserve your last valid ruleset."
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun WebsiteBlockingSettingsScreen(state: SettingsUiState, vm: SettingsViewModel) {
    var hostInput by remember { mutableStateOf("") }
    var includeSubdomains by remember { mutableStateOf(false) }
    var lockDaysFloat by remember { mutableFloatStateOf(30f) }
    var formError by remember { mutableStateOf<String?>(null) }
    var pendingConfirmAdd by remember { mutableStateOf<Triple<String, Boolean, Int>?>(null) }
    var editingRule by remember { mutableStateOf<BlockedSiteRule?>(null) }
    var deletingRule by remember { mutableStateOf<BlockedSiteRule?>(null) }

    val lockDays = lockDaysFloat.roundToInt().coerceIn(WebsiteBlocker.MIN_LOCK_DAYS, WebsiteBlocker.MAX_LOCK_DAYS)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("website_blocking_list"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        if (state.blockerIntegrityWarning) {
            item {
                InfoBanner(
                    title = "Integrity Notice",
                    body = "Local rule file verification detected an unexpected external modification."
                )
            }
        }

        item {
            SectionCard("Add Locked Website Rule") {
                OutlinedTextField(
                    value = hostInput,
                    onValueChange = { hostInput = it; formError = null },
                    label = { Text("Website hostname or URL (e.g. m.youtube.com)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("block_host_input"),
                    colors = pipraTextFieldColors()
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { includeSubdomains = !includeSubdomains },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = includeSubdomains,
                        onCheckedChange = { includeSubdomains = it },
                        modifier = Modifier.testTag("block_subdomains_checkbox"),
                        colors = CheckboxDefaults.colors(
                            checkedColor = PipraColors.ElectricBlue,
                            uncheckedColor = PipraColors.SilverMuted
                        )
                    )
                    Column {
                        Text("Include subdomains", color = PipraColors.SilverPrimary, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (includeSubdomains) "Blocks the host and *.host (without affecting unrelated domains)"
                            else "Blocks exact hostname only (e.g. m.youtube.com only)",
                            color = PipraColors.SilverMuted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Lock period: $lockDays days (Min 7 · Max 365)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = PipraColors.ElectricBlueLight
                )
                Slider(
                    value = lockDaysFloat,
                    onValueChange = { lockDaysFloat = it },
                    valueRange = 7f..365f,
                    modifier = Modifier.testTag("block_lock_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = PipraColors.ElectricBlue,
                        activeTrackColor = PipraColors.ElectricBlue
                    )
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7, 30, 90, 365).forEach { preset ->
                        FilterChip(
                            selected = lockDays == preset,
                            onClick = { lockDaysFloat = preset.toFloat() },
                            label = { Text("${preset}d") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PipraColors.ElectricBlue.copy(alpha = 0.2f),
                                selectedLabelColor = PipraColors.ElectricBlueLight,
                                labelColor = PipraColors.SilverMuted
                            )
                        )
                    }
                }
                formError?.let { err ->
                    Spacer(Modifier.height(6.dp))
                    Text(err, color = PipraColors.Danger, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val norm = DomainUtils.normalizeHost(hostInput)
                        if (norm == null) {
                            formError = "Enter a valid hostname or URL (for example: m.youtube.com)."
                        } else {
                            pendingConfirmAdd = Triple(norm, includeSubdomains, lockDays)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PipraColors.ElectricBlue),
                    modifier = Modifier.fillMaxWidth().testTag("block_add_rule_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Lock & Block Website")
                }
            }
        }

        if (state.blockedRules.isEmpty()) {
            item {
                InfoBanner(
                    title = "No Blocked Websites",
                    body = "Add a hostname above and choose a 7 to 365 day lock period. Active rules cannot be edited or removed in Settings until their deadline expires."
                )
            }
        } else {
            items(state.blockedRules, key = { it.id }) { rule ->
                val locked = rule.isLocked()
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("blocked_rule_${rule.host}"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = PipraColors.Charcoal),
                    border = BorderStroke(1.dp, if (locked) PipraColors.ElectricBlue.copy(alpha = 0.45f) else PipraColors.SilverBorder)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = if (locked) "Locked" else "Unlocked",
                            tint = if (locked) PipraColors.ElectricBlueLight else PipraColors.Success,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = rule.host + if (rule.includeSubdomains) " (+ subdomains)" else " (exact host)",
                                style = MaterialTheme.typography.titleMedium,
                                color = PipraColors.SilverPrimary
                            )
                            Text(
                                text = vm.websiteBlocker.formatRemaining(rule.lockUntilMillis),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (locked) PipraColors.ElectricBlueLight else PipraColors.Success
                            )
                            Text(
                                text = "Unlocks: ${vm.websiteBlocker.formatUnlockDate(rule.lockUntilMillis)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = PipraColors.SilverMuted
                            )
                        }
                        if (!locked) {
                            IconButton(
                                onClick = { editingRule = rule },
                                modifier = Modifier.testTag("edit_rule_${rule.host}")
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit rule", tint = PipraColors.SilverPrimary)
                            }
                            IconButton(
                                onClick = { deletingRule = rule },
                                modifier = Modifier.testTag("delete_rule_${rule.host}")
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete rule", tint = PipraColors.Danger)
                            }
                        }
                    }
                }
            }
        }
        item {
            InfoBanner(
                title = "Lock Scope & Honest Limitations",
                body = "Active rules block navigation, links, redirects, and subresources across normal and private windows. Like any standard Android app, Pipra cannot prevent device-level actions such as uninstalling the app, clearing Android app data, rooting, or changing the system clock."
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    pendingConfirmAdd?.let { (host, subs, days) ->
        AlertDialog(
            onDismissRequest = { pendingConfirmAdd = null },
            containerColor = PipraColors.Charcoal,
            title = { Text("Confirm $days-Day Lock", color = PipraColors.SilverPrimary) },
            text = {
                Text(
                    "Block $host (${if (subs) "including subdomains" else "exact host only"}) for $days days?\n\n" +
                        "Once confirmed, this rule cannot be edited or deleted in Settings until the lock expires.",
                    color = PipraColors.SilverMuted
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        when (val res = vm.addWebsiteBlockRule(host, subs, days)) {
                            is RuleActionResult.Success -> {
                                hostInput = ""
                                formError = null
                            }
                            is RuleActionResult.Error -> formError = res.message
                        }
                        pendingConfirmAdd = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PipraColors.ElectricBlue),
                    modifier = Modifier.testTag("confirm_lock_button")
                ) {
                    Text("Confirm Lock")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmAdd = null }) {
                    Text("Cancel", color = PipraColors.SilverMuted)
                }
            }
        )
    }

    editingRule?.let { rule ->
        var newHost by remember(rule.id) { mutableStateOf(rule.host) }
        var newSubs by remember(rule.id) { mutableStateOf(rule.includeSubdomains) }
        var newDays by remember(rule.id) { mutableFloatStateOf(rule.lockDays.toFloat()) }
        var editError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { editingRule = null },
            containerColor = PipraColors.Charcoal,
            title = { Text("Edit Expired Rule", color = PipraColors.SilverPrimary) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newHost,
                        onValueChange = { newHost = it; editError = null },
                        label = { Text("Hostname") },
                        singleLine = true,
                        colors = pipraTextFieldColors()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = newSubs, onCheckedChange = { newSubs = it })
                        Text("Include subdomains", color = PipraColors.SilverPrimary)
                    }
                    Text("New lock: ${newDays.roundToInt()} days", color = PipraColors.ElectricBlueLight)
                    Slider(value = newDays, onValueChange = { newDays = it }, valueRange = 7f..365f)
                    editError?.let { Text(it, color = PipraColors.Danger, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    when (val res = vm.updateWebsiteBlockRule(rule.id, newHost, newSubs, newDays.roundToInt())) {
                        is RuleActionResult.Success -> editingRule = null
                        is RuleActionResult.Error -> editError = res.message
                    }
                }) { Text("Save & Re-Lock") }
            },
            dismissButton = {
                TextButton(onClick = { editingRule = null }) { Text("Cancel", color = PipraColors.SilverMuted) }
            }
        )
    }

    deletingRule?.let { rule ->
        AlertDialog(
            onDismissRequest = { deletingRule = null },
            containerColor = PipraColors.Charcoal,
            title = { Text("Delete Rule", color = PipraColors.SilverPrimary) },
            text = { Text("Remove ${rule.host} from Website Blocking?", color = PipraColors.SilverMuted) },
            confirmButton = {
                Button(
                    onClick = {
                        when (val res = vm.deleteWebsiteBlockRule(rule.id)) {
                            is RuleActionResult.Success -> deletingRule = null
                            is RuleActionResult.Error -> {
                                formError = res.message
                                deletingRule = null
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PipraColors.Danger)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deletingRule = null }) { Text("Cancel", color = PipraColors.SilverMuted) }
            }
        )
    }
}

@Composable
fun AdultProtectionSettingsScreen(state: SettingsUiState, vm: SettingsViewModel) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            SectionCard("Adult Website Protection") {
                SettingSwitchRow(
                    title = "Enable Adult Protection",
                    subtitle = "Block known adult domains locally across normal and private windows",
                    checked = state.adultProtectionEnabled,
                    testTag = "adult_protection_switch",
                    onCheckedChange = vm::setAdultProtectionEnabled
                )
            }
        }
        item {
            SectionCard("Offline Rules & Manual List Updates") {
                Text(
                    text = "Active domains (baseline + cached): ${state.adultActiveCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PipraColors.SilverPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Last successful update: ${formatTimestamp(state.adultLastUpdatedMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PipraColors.SilverMuted
                )
                Spacer(Modifier.height(10.dp))
                AdultBlockerEngine.PRESET_SOURCES.forEach { (label, url) ->
                    FilterChip(
                        selected = state.adultListUrl == url,
                        onClick = { vm.setAdultListUrl(url) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PipraColors.ElectricBlue.copy(alpha = 0.2f),
                            selectedLabelColor = PipraColors.ElectricBlueLight,
                            labelColor = PipraColors.SilverMuted
                        ),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { vm.updateAdultBlocklist() },
                    enabled = !state.adultUpdating,
                    colors = ButtonDefaults.buttonColors(containerColor = PipraColors.ElectricBlue),
                    modifier = Modifier.testTag("adult_update_button")
                ) {
                    if (state.adultUpdating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = PipraColors.SilverPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Updating…")
                    } else {
                        Text("Update Adult Blocklist")
                    }
                }
                state.adultUpdateStatus?.let { status ->
                    Spacer(Modifier.height(8.dp))
                    Text(status, style = MaterialTheme.typography.bodySmall, color = PipraColors.ElectricBlueLight)
                }
            }
        }
        item {
            InfoBanner(
                title = "Local-Only Filtering & Limitations",
                body = "Pipra never uploads browsing history or URLs to any remote classification service. Domain-based filtering works offline with cached rules, but cannot detect every page on general-purpose websites or newly created domains."
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}
