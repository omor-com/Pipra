package com.pipra.browser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pipra.browser.ui.settings.AboutPipraScreen
import com.pipra.browser.ui.settings.AdBlockerSettingsScreen
import com.pipra.browser.ui.settings.AdultProtectionSettingsScreen
import com.pipra.browser.ui.settings.AppLockSettingsScreen
import com.pipra.browser.ui.settings.AppearanceSettingsScreen
import com.pipra.browser.ui.settings.DataManagementScreen
import com.pipra.browser.ui.settings.DownloadsSettingsScreen
import com.pipra.browser.ui.settings.ExtensionsSettingsScreen
import com.pipra.browser.ui.settings.FocusScheduleSettingsScreen
import com.pipra.browser.ui.settings.PrivacySettingsScreen
import com.pipra.browser.ui.settings.PrivacyStatsSettingsScreen
import com.pipra.browser.ui.settings.SearchEngineSettingsScreen
import com.pipra.browser.ui.settings.SecuritySettingsScreen
import com.pipra.browser.ui.settings.SettingsHubScreen
import com.pipra.browser.ui.settings.SettingsSection
import com.pipra.browser.ui.settings.SitePermissionsSettingsScreen
import com.pipra.browser.ui.settings.pipraTextFieldColors
import com.pipra.browser.ui.theme.PipraColors
import com.pipra.browser.ui.theme.PipraTheme

class SettingsActivity : ComponentActivity() {

    private val vm: SettingsViewModel by viewModels {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return SettingsViewModel(application) as T
            }
        }
    }

    companion object {
        const val EXTRA_SECTION = "section"
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val initialSectionName = intent?.getStringExtra(EXTRA_SECTION)
        val initialSection = SettingsSection.entries.firstOrNull { it.name == initialSectionName }

        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            PipraTheme(themeMode = state.themeMode) {
                val isDark = com.pipra.browser.ui.theme.LocalPipraColors.current.isDark
                androidx.compose.runtime.SideEffect {
                    val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                    controller.isAppearanceLightStatusBars = !isDark
                    controller.isAppearanceLightNavigationBars = !isDark
                }

                var currentSectionName by rememberSaveable { mutableStateOf(initialSection?.name) }
                val currentSection = currentSectionName?.let { name ->
                    SettingsSection.entries.firstOrNull { it.name == name }
                }
                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(currentSectionName) {
                    snackbarHostState.currentSnackbarData?.dismiss()
                }

                LaunchedEffect(state.feedbackMessage) {
                    val msg = state.feedbackMessage
                    if (!msg.isNullOrBlank()) {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(msg)
                        vm.clearFeedback()
                    }
                }

                BackHandler(enabled = currentSection != null) {
                    currentSectionName = null
                }

                val appLock = remember { AppLockManager(this@SettingsActivity) }
                var settingsUnlocked by rememberSaveable {
                    mutableStateOf(!appLock.isLockRequired(LockScope.SETTINGS))
                }
                var pinEntry by remember { mutableStateOf("") }
                var pinError by remember { mutableStateOf<String?>(null) }

                if (!settingsUnlocked) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(28.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Pipra Settings Locked", style = MaterialTheme.typography.headlineSmall, color = PipraColors.SilverPrimary)
                        Spacer(Modifier.height(8.dp))
                        Text("Enter your App Lock PIN to access Settings.", style = MaterialTheme.typography.bodySmall, color = PipraColors.SilverMuted)
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = pinEntry,
                            onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) { pinEntry = it; pinError = null } },
                            label = { Text("PIN") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            colors = pipraTextFieldColors(),
                            modifier = Modifier.fillMaxWidth().testTag("settings_unlock_pin_input")
                        )
                        pinError?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = PipraColors.Danger)
                        }
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = {
                                when (val res = appLock.verifyPin(pinEntry)) {
                                    is PinVerifyResult.Success -> settingsUnlocked = true
                                    is PinVerifyResult.Invalid -> pinError = "Wrong PIN (${res.remainingAttempts} attempts left)"
                                    is PinVerifyResult.LockedOut -> pinError = "Too many attempts. Wait ${res.remainingSeconds}s"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = PipraColors.ElectricBlue),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Unlock Settings") }
                    }
                } else {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = PipraColors.MatteBlack,
                        contentWindowInsets = WindowInsets.safeDrawing,
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        topBar = {
                            TopAppBar(
                                title = {
                                    Text(
                                        text = currentSection?.title ?: "Settings",
                                        color = PipraColors.SilverPrimary
                                    )
                                },
                                navigationIcon = {
                                    IconButton(
                                        onClick = {
                                            if (currentSection != null) currentSectionName = null
                                            else finish()
                                        },
                                        modifier = Modifier.testTag("settings_back_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back",
                                            tint = PipraColors.SilverPrimary
                                        )
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = PipraColors.Charcoal,
                                    titleContentColor = PipraColors.SilverPrimary
                                )
                            )
                        }
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            when (currentSection) {
                                null -> SettingsHubScreen(
                                    state = state,
                                    onSelect = { currentSectionName = it.name }
                                )
                                SettingsSection.APPEARANCE -> AppearanceSettingsScreen(state, vm)
                                SettingsSection.EXTENSIONS -> ExtensionsSettingsScreen(state, vm)
                                SettingsSection.AD_BLOCKER -> AdBlockerSettingsScreen(state, vm)
                                SettingsSection.PRIVACY_STATS -> PrivacyStatsSettingsScreen(state, vm)
                                SettingsSection.WEBSITE_BLOCKING -> com.pipra.browser.ui.settings.WebsiteBlockingSettingsScreen(state, vm)
                                SettingsSection.FOCUS_SCHEDULE -> FocusScheduleSettingsScreen(state, vm)
                                SettingsSection.ADULT_PROTECTION -> AdultProtectionSettingsScreen(state, vm)
                                SettingsSection.SITE_PERMISSIONS -> SitePermissionsSettingsScreen(state, vm)
                                SettingsSection.APP_LOCK -> AppLockSettingsScreen(state, vm)
                                SettingsSection.PRIVACY -> PrivacySettingsScreen(state, vm)
                                SettingsSection.SECURITY -> SecuritySettingsScreen(state, vm)
                                SettingsSection.DOWNLOADS -> DownloadsSettingsScreen(state, vm)
                                SettingsSection.SEARCH_ENGINE -> SearchEngineSettingsScreen(state, vm)
                                SettingsSection.DATA_MANAGEMENT -> DataManagementScreen(vm)
                                SettingsSection.ABOUT -> AboutPipraScreen()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }
}
