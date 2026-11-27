package com.pipra.browser.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.pipra.browser.SettingsUiState
import com.pipra.browser.SettingsViewModel
import com.pipra.browser.Store
import com.pipra.browser.ui.theme.PipraColors

@Composable
fun PrivacySettingsScreen(state: SettingsUiState, vm: SettingsViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            SectionCard("Tracking & Connection Privacy") {
                SettingSwitchRow(
                    title = "HTTPS-Only Mode",
                    subtitle = "Automatically upgrade HTTP links to HTTPS and block mixed content",
                    checked = state.httpsOnly,
                    testTag = "switch_https_only",
                    onCheckedChange = vm::setHttpsOnly
                )
                SettingSwitchRow(
                    title = "Block third-party cookies",
                    subtitle = "Prevent cross-site cookie tracking in normal windows (always blocked in Private)",
                    checked = state.blockThirdPartyCookies,
                    testTag = "switch_block_3p_cookies",
                    onCheckedChange = vm::setBlockThirdPartyCookies
                )
                SettingSwitchRow(
                    title = "Block pop-ups",
                    subtitle = "Allow new windows only when triggered by an explicit tap",
                    checked = state.blockPopups,
                    testTag = "switch_block_popups",
                    onCheckedChange = vm::setBlockPopups
                )
                SettingSwitchRow(
                    title = "Strip tracking URL parameters",
                    subtitle = "Remove utm_*, fbclid, gclid, msclkid, and similar query tags on navigation",
                    checked = state.stripTrackingParams,
                    testTag = "switch_strip_tracking",
                    onCheckedChange = vm::setStripTrackingParams
                )
            }
        }
        item {
            InfoBanner(
                title = "Site Permissions & Private Windows",
                body = "Camera, microphone, and geolocation requests are denied by default. Private windows run in an isolated :incognito process with an ephemeral cookie jar and FLAG_SECURE screenshot protection, though private mode does not hide traffic from websites, ISPs, or network administrators."
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun SecuritySettingsScreen(state: SettingsUiState, vm: SettingsViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            SectionCard("WebView Execution Controls") {
                SettingSwitchRow(
                    title = "Enable JavaScript",
                    subtitle = "Required by most modern sites; disable for strict static browsing",
                    checked = state.javaScriptEnabled,
                    testTag = "switch_javascript",
                    onCheckedChange = vm::setJavaScriptEnabled
                )
            }
        }
        item {
            SectionCard("Verified WebView Safeguards") {
                val items = listOf(
                    "Local file & content provider access" to "Disabled (allowFileAccess = false, allowContentAccess = false)",
                    "Page-triggered unsafe schemes" to "file:, content:, javascript:, data:, blob: blocked",
                    "TLS certificate validation" to "Strict refusal on invalid certificates (never bypassed)",
                    "JavaScript-to-native bridge" to "None (addJavascriptInterface is never exposed)",
                    "Google Safe Browsing" to "Enabled on WebView",
                    "External app intents" to "Component/selector stripped, BROWSABLE enforced, confirmation required",
                    "Android Auto-Backup" to "Disabled (allowBackup = false)"
                )
                items.forEach { (k, v) ->
                    Column(modifier = Modifier.padding(vertical = 5.dp)) {
                        Text(k, style = MaterialTheme.typography.bodyMedium, color = PipraColors.SilverPrimary)
                        Text(v, style = MaterialTheme.typography.bodySmall, color = PipraColors.SilverMuted)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun DownloadsSettingsScreen(state: SettingsUiState, vm: SettingsViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            SectionCard("Download & File Center") {
                Button(
                    onClick = {
                        ctx.startActivity(android.content.Intent(ctx, com.pipra.browser.DownloadFileCenterActivity::class.java))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PipraColors.ElectricBlue),
                    modifier = Modifier.fillMaxWidth().testTag("open_downloads_center_button")
                ) {
                    Text("Open Download & File Center")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "View live download progress, pause/resume supported transfers, share, rename, delete, and open local HTML websites, PDFs, ZIPs, JSON, TXT, images, and APKs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PipraColors.SilverMuted
                )
            }
        }
        item {
            SectionCard("Download Behavior") {
                SettingSwitchRow(
                    title = "Confirm before downloading",
                    subtitle = "Show filename and host confirmation dialog before starting downloads",
                    checked = state.askBeforeDownload,
                    testTag = "switch_ask_download",
                    onCheckedChange = vm::setAskBeforeDownload
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Destination: App-private Downloads directory (no broad storage permission required). Only http:// and https:// URLs with sanitized filenames are accepted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PipraColors.SilverMuted
                )
            }
        }
        item {
            SectionCard("Recent Downloads (${state.downloads.size})") {
                if (state.downloads.isEmpty()) {
                    Text(
                        "No downloads recorded yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PipraColors.SilverMuted
                    )
                } else {
                    state.downloads.take(25).forEach { d ->
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            Text(d.fileName, style = MaterialTheme.typography.bodyMedium, color = PipraColors.SilverPrimary)
                            Text(
                                "${formatTimestamp(d.time)} · ${d.url.take(64)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = PipraColors.SilverMuted
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = vm::clearDownloadsLog,
                        border = BorderStroke(1.dp, PipraColors.SilverBorder)
                    ) {
                        Text("Clear Download History", color = PipraColors.SilverPrimary)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun SearchEngineSettingsScreen(state: SettingsUiState, vm: SettingsViewModel) {
    var customInput by remember(state.customSearchUrl) { mutableStateOf(state.customSearchUrl) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("search_engine_list"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        items(Store.SEARCH_ENGINES.keys.toList()) { engine ->
            val selected = state.searchEngine == engine
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.setSearchEngine(engine) }
                    .testTag("search_engine_${engine.lowercase().replace(' ', '_')}"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = PipraColors.Charcoal),
                border = BorderStroke(1.dp, if (selected) PipraColors.ElectricBlue else PipraColors.SilverBorder)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = { vm.setSearchEngine(engine) },
                        colors = RadioButtonDefaults.colors(selectedColor = PipraColors.ElectricBlue)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(engine, style = MaterialTheme.typography.titleMedium, color = PipraColors.SilverPrimary)
                        Text(
                            Store.SEARCH_ENGINES[engine].orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = PipraColors.SilverMuted
                        )
                    }
                }
            }
        }
        item {
            val customSelected = state.searchEngine == Store.CUSTOM_SEARCH_ENGINE
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = PipraColors.Charcoal),
                border = BorderStroke(1.dp, if (customSelected) PipraColors.ElectricBlue else PipraColors.SilverBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(enabled = state.customSearchUrl.isNotBlank()) {
                            vm.setSearchEngine(Store.CUSTOM_SEARCH_ENGINE)
                        }
                    ) {
                        RadioButton(
                            selected = customSelected,
                            onClick = {
                                if (state.customSearchUrl.isNotBlank()) vm.setSearchEngine(Store.CUSTOM_SEARCH_ENGINE)
                            },
                            enabled = state.customSearchUrl.isNotBlank(),
                            colors = RadioButtonDefaults.colors(selectedColor = PipraColors.ElectricBlue)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Custom Search URL",
                            style = MaterialTheme.typography.titleMedium,
                            color = PipraColors.SilverPrimary
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = customInput,
                        onValueChange = { customInput = it },
                        label = { Text("Search URL template (use %s for query)") },
                        placeholder = { Text("https://search.example.com/?q=%s") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("custom_search_input"),
                        colors = pipraTextFieldColors()
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { vm.setCustomSearchUrl(customInput) },
                        colors = ButtonDefaults.buttonColors(containerColor = PipraColors.ElectricBlue),
                        modifier = Modifier.testTag("save_custom_search_button")
                    ) {
                        Text("Save & Use Custom Engine")
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun DataManagementScreen(vm: SettingsViewModel) {
    var confirmAll by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            SectionCard("Selective Cleanup") {
                OutlinedButton(
                    onClick = vm::clearHistoryOnly,
                    modifier = Modifier.fillMaxWidth().testTag("clear_history_button"),
                    border = BorderStroke(1.dp, PipraColors.SilverBorder)
                ) {
                    Text("Clear Browsing History", color = PipraColors.SilverPrimary)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = vm::clearCookiesOnly,
                    modifier = Modifier.fillMaxWidth().testTag("clear_cookies_button"),
                    border = BorderStroke(1.dp, PipraColors.SilverBorder)
                ) {
                    Text("Clear All Cookies", color = PipraColors.SilverPrimary)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = vm::clearSiteStorageOnly,
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, PipraColors.SilverBorder)
                ) {
                    Text("Clear Site Storage (localStorage / IndexedDB)", color = PipraColors.SilverPrimary)
                }
            }
        }
        item {
            SectionCard("Clear All Browsing Data") {
                Text(
                    "Clears browsing history, cookies, site storage, network inspector buffer, and WebView cache. Active Website Blocking locks are never removed by data cleanup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PipraColors.SilverMuted
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { confirmAll = true },
                    colors = ButtonDefaults.buttonColors(containerColor = PipraColors.Danger),
                    modifier = Modifier.fillMaxWidth().testTag("clear_all_data_button")
                ) {
                    Text("Clear All Browsing Data")
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            containerColor = PipraColors.Charcoal,
            title = { Text("Clear Browsing Data?", color = PipraColors.SilverPrimary) },
            text = {
                Text(
                    "History, cookies, site storage, and cache will be deleted. Locked Website Blocking rules remain active.",
                    color = PipraColors.SilverMuted
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.clearAllBrowsingData()
                        confirmAll = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PipraColors.Danger)
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAll = false }) { Text("Cancel", color = PipraColors.SilverMuted) }
            }
        )
    }
}

@Composable
fun AboutPipraScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var legalDialogTitle by remember { mutableStateOf<String?>(null) }
    var legalDialogBody by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("about_pipra_list"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = PipraColors.Charcoal),
                border = BorderStroke(1.dp, PipraColors.SilverBorder)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    androidx.compose.material3.Surface(
                        color = PipraColors.CardElevated,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.size(88.dp)
                    ) {
                        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_pipra_ant_mark),
                                contentDescription = "Pipra Logo",
                                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(PipraColors.SilverPrimary),
                                modifier = Modifier.size(58.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Pipra",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = PipraColors.SilverPrimary
                    )
                    Text(
                        text = "Private Android Web Browser · v3.0",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PipraColors.ElectricBlueLight
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Developed by Muhammad Omor",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = PipraColors.SilverPrimary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "muhammedomor.dev@gmail.com",
                        style = MaterialTheme.typography.bodySmall,
                        color = PipraColors.SilverMuted
                    )
                }
            }
        }
        item {
            SectionCard("Legal & Documentation") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            legalDialogTitle = "Privacy Policy"
                            legalDialogBody = PIPRA_PRIVACY_POLICY
                        },
                        modifier = Modifier.weight(1f).testTag("about_privacy_policy_btn"),
                        border = BorderStroke(1.dp, PipraColors.SilverBorder)
                    ) {
                        Text("Privacy Policy", color = PipraColors.SilverPrimary)
                    }
                    OutlinedButton(
                        onClick = {
                            legalDialogTitle = "Terms & Conditions"
                            legalDialogBody = PIPRA_TERMS_AND_CONDITIONS
                        },
                        modifier = Modifier.weight(1f).testTag("about_terms_btn"),
                        border = BorderStroke(1.dp, PipraColors.SilverBorder)
                    ) {
                        Text("Terms & Conditions", color = PipraColors.SilverPrimary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        try {
                            ctx.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://github.com/omor-com/Pipra")
                                )
                            )
                        } catch (_: Exception) {}
                    },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, PipraColors.SilverBorder)
                ) {
                    Text("Source Code: github.com/omor-com/Pipra", color = PipraColors.ElectricBlueLight)
                }
            }
        }
        item {
            InfoBanner(
                title = "Engine & Extension Compatibility",
                body = "Pipra uses Android WebView + androidx.webkit for page rendering, multi-tab browsing, isolated :incognito windows, and saved offline web archives. Because it uses WebView (not GeckoView), Firefox/WebExtension (.xpi) add-ons are not supported; built-in Ad Blocker tiers, 7–365 day Website Locks, Adult Protection, and DevTools CSS/JS Insert Rules are supported natively."
            )
        }
        item {
            InfoBanner(
                title = "Architecture & Filter Sources",
                body = "Pipra extends the open-source Injectra architecture. Optional manual filter updates use public domain lists (Peter Lowe Ad & Tracking Server List, StevenBlack Unified Hosts, AdGuard DNS Filter, and OISD NSFW). All rule evaluation stays strictly on your device."
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    legalDialogTitle?.let { title ->
        AlertDialog(
            onDismissRequest = { legalDialogTitle = null },
            containerColor = PipraColors.Charcoal,
            title = { Text(title, color = PipraColors.SilverPrimary) },
            text = {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                ) {
                    androidx.compose.foundation.lazy.LazyColumn {
                        item {
                            Text(
                                text = legalDialogBody,
                                style = MaterialTheme.typography.bodySmall,
                                color = PipraColors.SilverMuted
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { legalDialogTitle = null }) {
                    Text("Close", color = PipraColors.ElectricBlueLight)
                }
            }
        )
    }
}

private const val PIPRA_PRIVACY_POLICY = """Pipra Browser Privacy Policy (v3.0)

1. Zero Telemetry & No Accounts
Pipra does not require registration or sign-in, contains no third-party analytics SDKs, and does not collect or transmit personal browsing history, search queries, bookmarks, or downloaded filenames to the developer.

2. Local-Only Storage
Browsing history, bookmarks, offline saved web archives, theme preferences, download records, and website blocking rules are stored strictly in private app storage on your device (with Android Auto-Backup disabled).

3. Private Windows (:incognito)
Private windows run in a separate Android process (:incognito) with an ephemeral cookie jar and FLAG_SECURE screenshot protection. History is not recorded in private mode, and cookies/site storage are wiped when the private window closes. Private browsing does not hide network traffic from websites you visit, your ISP, or network administrators.

4. Network Connections
Connections occur when you navigate to URLs, perform searches through your selected search engine, download files you request, or manually tap update for optional open-source Ad Blocker / Adult Protection filter lists. Local files opened in Pipra's file viewer are inspected strictly on-device without uploading data.

5. Optional Developer Support
Tapping "Donate to Developer" opens https://www.supportkori.com/muhammedomor in your browser. Any contribution is completely optional and handled externally by that service.

6. Contact
Developer: Muhammad Omor
Email: muhammedomor.dev@gmail.com
Source: https://github.com/omor-com/Pipra"""

private const val PIPRA_TERMS_AND_CONDITIONS = """Pipra Browser Terms & Conditions (v3.0)

1. Scope of Service
Pipra is an open-source privacy-focused Android browser developed by Muhammad Omor and provided "as is" without warranties of any kind.

2. User Responsibility
You are solely responsible for websites visited, files downloaded, and local files opened within Pipra (including extracted archives and APK packages).

3. Website Blocking & Protection Limits
Locked Website Blocking rules enforce 7–365 day locks within Pipra's Settings interface. As a standard Android application, Pipra cannot block device-level OS actions such as clearing app data, changing the system clock, or uninstalling the app.

4. Local File Viewer & Offline Content
Untrusted local HTML files are rendered in an isolated WebView without native JavaScript bridges or direct file:// filesystem exposure. Saved offline web archives reflect a local snapshot at capture time and may not preserve dynamic authenticated sessions.

5. Contact
Developer: Muhammad Omor
Email: muhammedomor.dev@gmail.com
Repository: https://github.com/omor-com/Pipra"""

