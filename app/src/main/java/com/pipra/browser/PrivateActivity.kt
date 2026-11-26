package com.pipra.browser

/**
 * A private window. Runs in the ":incognito" process (see AndroidManifest and
 * PipraApp), so it never shares cookies or site data with normal windows,
 * and everything is erased when it closes.
 */
class PrivateActivity : MainActivity() {
    override val isPrivate: Boolean = true
}
