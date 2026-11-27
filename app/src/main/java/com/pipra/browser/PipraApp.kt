package com.pipra.browser

import android.app.Application
import android.os.Build
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import java.io.File

/**
 * Private windows run in their own process (":incognito") with their own WebView
 * data directory, so they get a completely separate cookie jar, cache, local
 * storage and IndexedDB from normal windows. Any leftover private data from a
 * prior killed session is wiped here before WebView can initialize.
 */
class PipraApp : Application() {

    companion object {
        const val PRIVATE_SUFFIX = "incognito"

        fun currentProcessName(): String {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return getProcessName()
            }
            return try {
                File("/proc/self/cmdline").readText(Charsets.UTF_8).trim('\u0000', ' ', '\n')
            } catch (_: Exception) {
                ""
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val store = Store(this)
        val nightMode = when (store.themeMode) {
            AppThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            AppThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            AppThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)

        if (currentProcessName().endsWith(":$PRIVATE_SUFFIX")) {
            wipePrivateData()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WebView.setDataDirectorySuffix(PRIVATE_SUFFIX)
            }
        }
    }

    private fun wipePrivateData() {
        val dirs = listOfNotNull(dataDir.listFiles(), cacheDir.listFiles()).flatMap { it.asList() }
        for (f in dirs) {
            if (f.name.contains(PRIVATE_SUFFIX)) f.deleteRecursively()
        }
        File(cacheDir, "WebView").listFiles()?.forEach {
            if (it.name.contains(PRIVATE_SUFFIX)) it.deleteRecursively()
        }
    }
}
