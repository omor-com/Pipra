package com.pipra.browser.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pipra.browser.SettingsUiState
import com.pipra.browser.SettingsViewModel
import com.pipra.browser.Store
import com.pipra.browser.ui.theme.PipraColors
import java.text.DateFormat
import java.util.Date

enum class SettingsSection(val title: String, val icon: ImageVector) {
    APPEARANCE("Appearance", Icons.Default.Palette),
    AD_BLOCKER("Ad Blocker", Icons.Default.Shield),
    WEBSITE_BLOCKING("Website Blocking", Icons.Default.Block),
    ADULT_PROTECTION("Adult Protection", Icons.Default.Lock),
    PRIVACY("Privacy", Icons.Default.PrivacyTip),
    SECURITY("Security", Icons.Default.Security),
    DOWNLOADS("Downloads", Icons.Default.Download),
    SEARCH_ENGINE("Search Engine", Icons.Default.Search),
    DATA_MANAGEMENT("Data Management", Icons.Default.DeleteSweep),
    ABOUT("About Pipra", Icons.Default.Info)
}

@Composable
fun SettingsHubScreen(
    state: SettingsUiState,
    onSelect: (SettingsSection) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("settings_hub_list"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Spacer(Modifier.height(6.dp)) }
        items(SettingsSection.entries) { section ->
            val subtitle = when (section) {
                SettingsSection.APPEARANCE -> "${state.themeMode.label} theme · Start page, tab strip, desktop"
                SettingsSection.AD_BLOCKER ->
                    if (state.adBlockEnabled) "${state.adBlockMode.label} mode · ${state.adBlockTotalBlocked} blocked"
                    else "Off"
                SettingsSection.WEBSITE_BLOCKING -> "${state.blockedRules.size} rule(s) · 7–365 day locks"
                SettingsSection.ADULT_PROTECTION ->
                    if (state.adultProtectionEnabled) "On · ${state.adultActiveCount} domains active"
                    else "Off"
                SettingsSection.PRIVACY -> "HTTPS-only, 3P cookies, pop-ups, tracking params"
                SettingsSection.SECURITY -> "JavaScript, TLS validation, Safe Browsing"
                SettingsSection.DOWNLOADS -> "${state.downloads.size} recorded · Download & File Center"
                SettingsSection.SEARCH_ENGINE ->
                    if (state.searchEngine == Store.CUSTOM_SEARCH_ENGINE) "Custom (${state.customSearchUrl.take(32)})"
                    else state.searchEngine
                SettingsSection.DATA_MANAGEMENT -> "Clear history, cookies, site data, cache"
                SettingsSection.ABOUT -> "Pipra 3.0 · Privacy Policy, Terms & Attribution"
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .clickable { onSelect(section) }
                    .testTag("settings_item_${section.name.lowercase()}"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = PipraColors.Charcoal),
                border = BorderStroke(1.dp, PipraColors.SilverBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = section.icon,
                        contentDescription = null,
                        tint = PipraColors.ElectricBlueLight,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = section.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = PipraColors.SilverPrimary
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = PipraColors.SilverMuted
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = PipraColors.SilverMuted
                    )
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSettingsScreen(state: SettingsUiState, vm: SettingsViewModel) {
    var homepageInput by remember(state.homepage) { mutableStateOf(state.homepage) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("appearance_list"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            SectionCard("App Theme") {
                Text(
                    text = "Applies across Pipra browser chrome, Settings, Home, Downloads, and Local Viewer without forcing dark mode onto websites.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PipraColors.SilverMuted
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    com.pipra.browser.AppThemeMode.entries.forEach { mode ->
                        val selected = state.themeMode == mode
                        androidx.compose.material3.FilterChip(
                            selected = selected,
                            onClick = { vm.setThemeMode(mode) },
                            label = { Text(mode.label) },
                            modifier = Modifier.testTag("theme_mode_${mode.key}"),
                            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PipraColors.ElectricBlue.copy(alpha = 0.18f),
                                selectedLabelColor = PipraColors.ElectricBlueLight,
                                labelColor = PipraColors.SilverMuted
                            )
                        )
                    }
                }
            }
        }
        item {
            SectionCard("Homepage") {
                OutlinedTextField(
                    value = homepageInput,
                    onValueChange = { homepageInput = it },
                    label = { Text("Start page or URL") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("homepage_input"),
                    colors = pipraTextFieldColors()
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { vm.setHomepage(homepageInput) },
                        colors = ButtonDefaults.buttonColors(containerColor = PipraColors.ElectricBlue),
                        modifier = Modifier.testTag("save_homepage_button")
                    ) {
                        Text("Save Homepage")
                    }
                    OutlinedButton(
                        onClick = {
                            homepageInput = Store.DEFAULT_HOMEPAGE
                            vm.setHomepage(Store.DEFAULT_HOMEPAGE)
                        },
                        border = BorderStroke(1.dp, PipraColors.SilverBorder)
                    ) {
                        Text("Use Pipra Home", color = PipraColors.SilverPrimary)
                    }
                }
            }
        }
        item {
            SectionCard("Browser Layout") {
                SettingSwitchRow(
                    title = "Show tab strip",
                    subtitle = "Display open tab chips above the address bar",
                    checked = state.showTabStrip,
                    testTag = "switch_tab_strip",
                    onCheckedChange = vm::setShowTabStrip
                )
                SettingSwitchRow(
                    title = "Open pages in desktop mode",
                    subtitle = "Use desktop User-Agent and wide viewport for new tabs",
                    checked = state.desktopByDefault,
                    testTag = "switch_desktop_default",
                    onCheckedChange = vm::setDesktopByDefault
                )
                SettingSwitchRow(
                    title = "Block images (data saver)",
                    subtitle = "Skip loading network images to reduce bandwidth",
                    checked = state.blockImages,
                    testTag = "switch_block_images",
                    onCheckedChange = vm::setBlockImages
                )
            }
        }
        item {
            InfoBanner(
                title = "Light & Dark Pipra Theme",
                body = "Pipra defaults to a clean Light appearance and lets you switch instantly between Light, Dark, and System default without restarting tabs."
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = PipraColors.Charcoal),
        border = BorderStroke(1.dp, PipraColors.SilverBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = PipraColors.ElectricBlueLight,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, color = PipraColors.SilverPrimary)
            Spacer(Modifier.height(2.dp))
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = PipraColors.SilverMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
            colors = SwitchDefaults.colors(
                checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                checkedTrackColor = PipraColors.ElectricBlue,
                uncheckedThumbColor = PipraColors.SilverMuted,
                uncheckedTrackColor = PipraColors.CardElevated
            )
        )
    }
}

@Composable
fun InfoBanner(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = PipraColors.CardSurface),
        border = BorderStroke(1.dp, PipraColors.SilverBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = PipraColors.SilverPrimary)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = PipraColors.SilverMuted)
        }
    }
}

@Composable
fun pipraTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = PipraColors.SilverPrimary,
    unfocusedTextColor = PipraColors.SilverPrimary,
    focusedBorderColor = PipraColors.ElectricBlue,
    unfocusedBorderColor = PipraColors.SilverBorder,
    focusedLabelColor = PipraColors.ElectricBlueLight,
    unfocusedLabelColor = PipraColors.SilverMuted,
    cursorColor = PipraColors.ElectricBlueLight
)

fun formatTimestamp(millis: Long): String {
    if (millis <= 0L) return "Never (using bundled offline rules)"
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
}
