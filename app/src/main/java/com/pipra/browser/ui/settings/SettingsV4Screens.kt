package com.pipra.browser.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pipra.browser.FocusScheduleRule
import com.pipra.browser.PipraExtensionManager
import com.pipra.browser.SettingsUiState
import com.pipra.browser.SettingsViewModel
import com.pipra.browser.SitePermissionDecision
import com.pipra.browser.ui.theme.PipraColors

@Composable
fun ExtensionsSettingsScreen(state: SettingsUiState, vm: SettingsViewModel) {
    var xpiUrlInput by remember { mutableStateOf("") }
    val pickXpi = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.installExtensionFromUri(uri)
    }
    val installedIds = remember(state.installedExtensions) { state.installedExtensions.map { it.id }.toSet() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("extensions_list"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            SectionCard("Install Firefox Extension (.xpi)") {
                Text(
                    text = "Pipra supports Mozilla GeckoView WebExtensions. Install from addons.mozilla.org URL, a local .xpi file, or pick from the curated catalog below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PipraColors.SilverMuted
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = xpiUrlInput,
                    onValueChange = { xpiUrlInput = it },
                    label = { Text("Direct .xpi or addons.mozilla.org URL") },
                    placeholder = { Text("https://addons.mozilla.org/.../latest.xpi") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("extension_url_input"),
                    colors = pipraTextFieldColors()
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            vm.installExtensionFromUrl(xpiUrlInput)
                            xpiUrlInput = ""
                        },
                        enabled = !state.extensionBusy && xpiUrlInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = PipraColors.ElectricBlue),
                        modifier = Modifier.weight(1f).testTag("extension_install_url_btn")
                    ) {
                        if (state.extensionBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = PipraColors.SilverPrimary)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("Install URL")
                    }
                    OutlinedButton(
                        onClick = { pickXpi.launch(arrayOf("application/x-xpinstall", "application/zip", "*/*")) },
                        enabled = !state.extensionBusy,
                        border = BorderStroke(1.dp, PipraColors.SilverBorder),
                        modifier = Modifier.testTag("extension_pick_file_btn")
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp), tint = PipraColors.SilverPrimary)
                        Spacer(Modifier.width(6.dp))
                        Text("Local .xpi", color = PipraColors.SilverPrimary)
                    }
                }
                state.extensionStatus?.let { msg ->
                    Spacer(Modifier.height(8.dp))
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = PipraColors.ElectricBlueLight)
                }
            }
        }

        item {
            Text(
                text = "Installed Extensions (${state.installedExtensions.size})",
                style = MaterialTheme.typography.labelMedium,
                color = PipraColors.ElectricBlueLight,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        if (state.installedExtensions.isEmpty()) {
            item {
                InfoBanner(
                    title = "No Extensions Installed",
                    body = "Install uBlock Origin, Dark Reader, or any compatible .xpi add-on above. Note that extension installs require active browser runtime and internet access for remote AMO downloads."
                )
            }
        } else {
            items(state.installedExtensions, key = { it.id }) { ext ->
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("installed_ext_${ext.id}"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = PipraColors.Charcoal),
                    border = BorderStroke(1.dp, PipraColors.SilverBorder)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Extension, contentDescription = null, tint = PipraColors.ElectricBlueLight, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${ext.name} · v${ext.version}", style = MaterialTheme.typography.titleMedium, color = PipraColors.SilverPrimary)
                                Text(ext.creator, style = MaterialTheme.typography.bodySmall, color = PipraColors.SilverMuted)
                            }
                            Switch(
                                checked = ext.enabled,
                                onCheckedChange = { vm.toggleExtensionEnabled(ext.id, it) },
                                colors = SwitchDefaults.colors(checkedTrackColor = PipraColors.ElectricBlue)
                            )
                        }
                        if (ext.description.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(ext.description, style = MaterialTheme.typography.bodySmall, color = PipraColors.SilverMuted)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = ext.allowedInPrivate,
                                onCheckedChange = { vm.toggleExtensionPrivate(ext.id, it) },
                                colors = CheckboxDefaults.colors(checkedColor = PipraColors.ElectricBlue)
                            )
                            Text("Allow in Private mode", style = MaterialTheme.typography.bodySmall, color = PipraColors.SilverPrimary, modifier = Modifier.weight(1f))
                            IconButton(onClick = { vm.updateExtension(ext.id) }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Update ${ext.name}", tint = PipraColors.SilverPrimary)
                            }
                            IconButton(onClick = { vm.uninstallExtension(ext.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Uninstall ${ext.name}", tint = PipraColors.Danger)
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "Curated Add-ons Catalog",
                style = MaterialTheme.typography.labelMedium,
                color = PipraColors.ElectricBlueLight,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        items(PipraExtensionManager.CURATED_CATALOG, key = { it.id }) { cat ->
            val isInstalled = cat.id in installedIds
            Card(
                modifier = Modifier.fillMaxWidth().testTag("catalog_ext_${cat.id}"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = PipraColors.Charcoal),
                border = BorderStroke(1.dp, PipraColors.SilverBorder)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Text(cat.name, style = MaterialTheme.typography.titleMedium, color = PipraColors.SilverPrimary)
                        Text(cat.category, style = MaterialTheme.typography.labelSmall, color = PipraColors.ElectricBlueLight)
                        Spacer(Modifier.height(2.dp))
                        Text(cat.summary, style = MaterialTheme.typography.bodySmall, color = PipraColors.SilverMuted)
                    }
                    Button(
                        onClick = { vm.installExtensionFromUrl(cat.xpiUrl) },
                        enabled = !isInstalled && !state.extensionBusy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isInstalled) PipraColors.CardElevated else PipraColors.ElectricBlue
                        )
                    ) {
                        Text(if (isInstalled) "Installed" else "Install")
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun PrivacyStatsSettingsScreen(state: SettingsUiState, vm: SettingsViewModel) {
    val stats = state.privacyStats
    val maxCat = maxOf(1L, stats.adsBlocked, stats.trackersBlocked, stats.cookiesBlocked, stats.fingerprintersBlocked)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("privacy_stats_list"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            SectionCard("Shield Impact Summary") {
                Text(
                    text = "${stats.totalBlocked}",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = PipraColors.ElectricBlueLight
                )
                Text(
                    text = "Total ads, trackers, cookies, and fingerprinters blocked · ${stats.httpsUpgrades} HTTPS upgrades",
                    style = MaterialTheme.typography.bodySmall,
                    color = PipraColors.SilverMuted
                )
                Spacer(Modifier.height(12.dp))
                StatBarRow("Ads Blocked", stats.adsBlocked, maxCat)
                StatBarRow("Trackers Blocked", stats.trackersBlocked, maxCat)
                StatBarRow("3rd-Party Cookies", stats.cookiesBlocked, maxCat)
                StatBarRow("Fingerprinters", stats.fingerprintersBlocked, maxCat)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = vm::resetPrivacyStats,
                    border = BorderStroke(1.dp, PipraColors.SilverBorder),
                    modifier = Modifier.testTag("reset_privacy_stats_btn")
                ) {
                    Text("Reset Counters", color = PipraColors.SilverPrimary)
                }
            }
        }
        item {
            SectionCard("Top Blocked Domains (${stats.topBlockedDomains.size})") {
                if (stats.topBlockedDomains.isEmpty()) {
                    Text(
                        "No blocked third-party requests recorded yet. Browse normally and blocked hosts will appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PipraColors.SilverMuted
                    )
                } else {
                    stats.topBlockedDomains.forEach { (host, count) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(host, style = MaterialTheme.typography.bodyMedium, color = PipraColors.SilverPrimary)
                            Text("$count", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = PipraColors.ElectricBlueLight)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun StatBarRow(label: String, count: Long, maxVal: Long) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = PipraColors.SilverPrimary)
            Text("$count", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = PipraColors.ElectricBlueLight)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (count.toFloat() / maxVal.toFloat()).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = PipraColors.ElectricBlue,
            trackColor = PipraColors.CardElevated
        )
    }
}

@Composable
fun FocusScheduleSettingsScreen(state: SettingsUiState, vm: SettingsViewModel) {
    var pomoHosts by remember { mutableStateOf("youtube.com, reddit.com, tiktok.com") }
    var pomoMinutes by remember { mutableIntStateOf(25) }

    var ruleLabel by remember { mutableStateOf("Deep Work") }
    var ruleHosts by remember { mutableStateOf("") }
    var includeSubdomains by remember { mutableStateOf(true) }
    var daysMask by remember { mutableIntStateOf(FocusScheduleRule.WEEKDAYS_MASK) }
    var startHour by remember { mutableIntStateOf(9) }
    var endHour by remember { mutableIntStateOf(17) }

    val pomoRunning = state.pomodoro.isRunning()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("focus_schedule_list"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            SectionCard("Pomodoro Focus Timer") {
                if (pomoRunning) {
                    Text(
                        text = "Active: ${state.pomodoro.remainingMinutes()} min remaining",
                        style = MaterialTheme.typography.titleMedium,
                        color = PipraColors.Success,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Blocking: ${state.pomodoro.blockedHosts.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = PipraColors.SilverMuted
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = vm::stopPomodoro,
                        colors = ButtonDefaults.buttonColors(containerColor = PipraColors.Danger),
                        modifier = Modifier.fillMaxWidth().testTag("pomodoro_stop_btn")
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Stop Pomodoro Session")
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(15, 25, 45, 60).forEach { mins ->
                            FilterChip(
                                selected = pomoMinutes == mins,
                                onClick = { pomoMinutes = mins },
                                label = { Text("${mins}m") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = PipraColors.ElectricBlue.copy(alpha = 0.2f),
                                    selectedLabelColor = PipraColors.ElectricBlueLight,
                                    labelColor = PipraColors.SilverMuted
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pomoHosts,
                        onValueChange = { pomoHosts = it },
                        label = { Text("Distracting domains (comma-separated)") },
                        modifier = Modifier.fillMaxWidth().testTag("pomodoro_hosts_input"),
                        colors = pipraTextFieldColors()
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { vm.startPomodoro(pomoMinutes, pomoHosts) },
                        colors = ButtonDefaults.buttonColors(containerColor = PipraColors.ElectricBlue),
                        modifier = Modifier.fillMaxWidth().testTag("pomodoro_start_btn")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Start $pomoMinutes-Min Pomodoro")
                    }
                }
            }
        }

        item {
            SectionCard("Add Recurring Focus Window") {
                OutlinedTextField(
                    value = ruleLabel,
                    onValueChange = { ruleLabel = it },
                    label = { Text("Schedule label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = pipraTextFieldColors()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ruleHosts,
                    onValueChange = { ruleHosts = it },
                    label = { Text("Domains to block (e.g. instagram.com, x.com)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("focus_rule_hosts_input"),
                    colors = pipraTextFieldColors()
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = daysMask == FocusScheduleRule.WEEKDAYS_MASK,
                        onClick = { daysMask = FocusScheduleRule.WEEKDAYS_MASK },
                        label = { Text("Weekdays") }
                    )
                    FilterChip(
                        selected = daysMask == FocusScheduleRule.ALL_DAYS_MASK,
                        onClick = { daysMask = FocusScheduleRule.ALL_DAYS_MASK },
                        label = { Text("Every Day") }
                    )
                    FilterChip(
                        selected = startHour == 9 && endHour == 17,
                        onClick = { startHour = 9; endHour = 17 },
                        label = { Text("09:00–17:00") }
                    )
                    FilterChip(
                        selected = startHour == 22 && endHour == 6,
                        onClick = { startHour = 22; endHour = 6 },
                        label = { Text("22:00–06:00") }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = includeSubdomains,
                        onCheckedChange = { includeSubdomains = it },
                        colors = CheckboxDefaults.colors(checkedColor = PipraColors.ElectricBlue)
                    )
                    Text("Include subdomains", style = MaterialTheme.typography.bodySmall, color = PipraColors.SilverPrimary)
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (vm.addFocusRule(ruleLabel, ruleHosts, includeSubdomains, daysMask, startHour * 60, endHour * 60)) {
                            ruleHosts = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PipraColors.ElectricBlue),
                    modifier = Modifier.fillMaxWidth().testTag("focus_add_rule_btn")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add Focus Schedule")
                }
            }
        }

        items(state.focusRules, key = { it.id }) { rule ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = PipraColors.Charcoal),
                border = BorderStroke(1.dp, PipraColors.SilverBorder)
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(rule.label, style = MaterialTheme.typography.titleMedium, color = PipraColors.SilverPrimary)
                        Text("${rule.formattedWindow()} · ${rule.hosts.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = PipraColors.SilverMuted)
                    }
                    Switch(checked = rule.enabled, onCheckedChange = { vm.toggleFocusRule(rule.id, it) })
                    IconButton(onClick = { vm.deleteFocusRule(rule.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete focus rule", tint = PipraColors.Danger)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun SitePermissionsSettingsScreen(state: SettingsUiState, vm: SettingsViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("site_permissions_list"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            InfoBanner(
                title = "Per-Site Permissions",
                body = "Pipra prompts before granting websites access to Camera, Microphone, Location, or Notifications. Decisions you save appear here. Private windows never persist site permissions."
            )
        }
        if (state.sitePermissions.isEmpty()) {
            item {
                SectionCard("Saved Permissions (0)") {
                    Text(
                        "No saved per-site permission overrides yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PipraColors.SilverMuted
                    )
                }
            }
        } else {
            items(state.sitePermissions, key = { "${it.host}_${it.type.key}" }) { entry ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = PipraColors.Charcoal),
                    border = BorderStroke(1.dp, PipraColors.SilverBorder)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.host, style = MaterialTheme.typography.titleMedium, color = PipraColors.SilverPrimary)
                            Text(
                                "${entry.type.label}: ${entry.decision.label}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (entry.decision == SitePermissionDecision.ALLOW) PipraColors.Success else PipraColors.Danger
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                val toggled = if (entry.decision == SitePermissionDecision.ALLOW) SitePermissionDecision.DENY else SitePermissionDecision.ALLOW
                                vm.setSitePermission(entry.host, entry.type, toggled)
                            },
                            border = BorderStroke(1.dp, PipraColors.SilverBorder)
                        ) {
                            Text(
                                if (entry.decision == SitePermissionDecision.ALLOW) "Block" else "Allow",
                                color = PipraColors.SilverPrimary
                            )
                        }
                        IconButton(onClick = { vm.removeSitePermission(entry.host, entry.type) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Reset ${entry.host}", tint = PipraColors.SilverMuted)
                        }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = vm::clearAllSitePermissions,
                    modifier = Modifier.fillMaxWidth().testTag("clear_site_permissions_btn"),
                    border = BorderStroke(1.dp, PipraColors.SilverBorder)
                ) {
                    Text("Reset All Site Permissions to Ask", color = PipraColors.Danger)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun AppLockSettingsScreen(state: SettingsUiState, vm: SettingsViewModel) {
    var pinInput by remember { mutableStateOf("") }
    var disablePinInput by remember { mutableStateOf("") }
    val lock = state.appLock

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("app_lock_list"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            SectionCard(if (lock.hasPin) "App Lock Active" else "Configure PIN Lock") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = if (lock.hasPin) PipraColors.Success else PipraColors.SilverMuted
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (lock.hasPin) "4–8 digit salted SHA-256 PIN is configured." else "No PIN configured. Set a 4–8 digit PIN to protect Private Windows, Settings, or App Launch.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PipraColors.SilverMuted
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) pinInput = it },
                    label = { Text(if (lock.hasPin) "New PIN (4–8 digits)" else "Create PIN (4–8 digits)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("app_lock_pin_input"),
                    colors = pipraTextFieldColors()
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        if (vm.setAppLockPin(pinInput)) pinInput = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PipraColors.ElectricBlue),
                    modifier = Modifier.fillMaxWidth().testTag("app_lock_save_pin_btn")
                ) {
                    Text(if (lock.hasPin) "Update PIN" else "Enable App Lock")
                }
            }
        }

        if (lock.hasPin) {
            item {
                SectionCard("Protection Scopes") {
                    SettingSwitchRow(
                        title = "Lock Private Windows",
                        subtitle = "Require PIN when opening an isolated :incognito window",
                        checked = lock.lockPrivateWindow,
                        testTag = "switch_lock_private",
                        onCheckedChange = { vm.updateAppLockScopes(lock.lockOnLaunch, it, lock.lockSettings, lock.allowDeviceCredential) }
                    )
                    SettingSwitchRow(
                        title = "Lock Settings",
                        subtitle = "Require PIN before opening Pipra Settings",
                        checked = lock.lockSettings,
                        testTag = "switch_lock_settings",
                        onCheckedChange = { vm.updateAppLockScopes(lock.lockOnLaunch, lock.lockPrivateWindow, it, lock.allowDeviceCredential) }
                    )
                    SettingSwitchRow(
                        title = "Lock on App Launch",
                        subtitle = "Require PIN whenever Pipra starts",
                        checked = lock.lockOnLaunch,
                        testTag = "switch_lock_launch",
                        onCheckedChange = { vm.updateAppLockScopes(it, lock.lockPrivateWindow, lock.lockSettings, lock.allowDeviceCredential) }
                    )
                    SettingSwitchRow(
                        title = "Allow Device Biometric / Screen Lock",
                        subtitle = "Show system fingerprint / device credential unlock option when available",
                        checked = lock.allowDeviceCredential,
                        testTag = "switch_lock_biometric",
                        onCheckedChange = { vm.updateAppLockScopes(lock.lockOnLaunch, lock.lockPrivateWindow, lock.lockSettings, it) }
                    )
                }
            }
            item {
                SectionCard("Disable App Lock") {
                    OutlinedTextField(
                        value = disablePinInput,
                        onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) disablePinInput = it },
                        label = { Text("Enter current PIN to disable") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("app_lock_disable_input"),
                        colors = pipraTextFieldColors()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            if (vm.disableAppLock(disablePinInput)) disablePinInput = ""
                        },
                        border = BorderStroke(1.dp, PipraColors.Danger),
                        modifier = Modifier.fillMaxWidth().testTag("app_lock_disable_btn")
                    ) {
                        Text("Remove PIN & Disable Lock", color = PipraColors.Danger)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}
