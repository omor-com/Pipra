package com.pipra.browser

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.R

/**
 * Pipra launcher splash screen. Respects saved Light (default) or Dark theme,
 * displays the ant brand mark and developer attribution briefly, then opens MainActivity.
 */
class SplashActivity : AppCompatActivity() {

    private var moved = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val store = Store(this)
        val isDark = store.isDarkThemeEffective()
        val root = findViewById<View>(R.id.splashRoot)
        val bg = getColor(if (isDark) R.color.pipra_dark_bg else R.color.pipra_light_bg)
        val fg = getColor(if (isDark) R.color.pipra_dark_text else R.color.pipra_light_text)
        val dim = getColor(if (isDark) R.color.pipra_dark_text_dim else R.color.pipra_light_text_dim)
        val accent = getColor(if (isDark) R.color.pipra_dark_accent else R.color.pipra_light_accent)

        root.setBackgroundColor(bg)
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (child is ImageView) {
                    child.setColorFilter(fg)
                } else if (child is TextView) {
                    when (child.text.toString()) {
                        getString(R.string.about_tagline) -> child.setTextColor(accent)
                        getString(R.string.about_dev_line) -> child.setTextColor(dim)
                        else -> child.setTextColor(fg)
                    }
                }
            }
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        root.setOnClickListener { go() }
        handler.postDelayed({ go() }, 1100)
    }

    private fun go() {
        if (moved) return
        moved = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
