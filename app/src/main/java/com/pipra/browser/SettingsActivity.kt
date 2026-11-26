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
import com.pipra.browser.ui.settings.AboutPipraScreen
import com.pipra.browser.ui.settings.AdBlockerSettingsScreen
import com.pipra.browser.ui.settings.AdultProtectionSettingsScreen
import com.pipra.browser.ui.settings.AppearanceSettingsScreen
import com.pipra.browser.ui.settings.DataManagementScreen
import com.pipra.browser.ui.settings.DownloadsSettingsScreen
import com.pipra.browser.ui.settings.PrivacySettingsScreen
import com.pipra.browser.ui.settings.SearchEngineSettingsScreen
import com.pipra.browser.ui.settings.SecuritySettingsScreen
import com.pipra.browser.ui.settings.SettingsHubScreen
import com.pipra.browser.ui.settings.SettingsSection
import com.pipra.browser.ui.theme.PipraColors
import com.pipra.browser.ui.theme.PipraTheme

class SettingsActivity : ComponentActivity() {

    private val vm: SettingsViewModel by viewModels()

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
            PipraTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                var currentSectionName by rememberSaveable { mutableStateOf(initialSection?.name) }
                val currentSection = currentSectionName?.let { name ->
                    SettingsSection.entries.firstOrNull { it.name == name }
                }
                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(state.feedbackMessage) {
                    val msg = state.feedbackMessage
                    if (!msg.isNullOrBlank()) {
                        snackbarHostState.showSnackbar(msg)
                        vm.clearFeedback()
                    }
                }

                BackHandler(enabled = currentSection != null) {
                    currentSectionName = null
                }

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
                            SettingsSection.AD_BLOCKER -> AdBlockerSettingsScreen(state, vm)
                            SettingsSection.WEBSITE_BLOCKING -> com.pipra.browser.ui.settings.WebsiteBlockingSettingsScreen(state, vm)
                            SettingsSection.ADULT_PROTECTION -> AdultProtectionSettingsScreen(state, vm)
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

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }
}
