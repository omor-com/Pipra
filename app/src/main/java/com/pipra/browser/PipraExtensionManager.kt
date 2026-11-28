package com.pipra.browser

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class CatalogExtension(
    val id: String,
    val name: String,
    val category: String,
    val summary: String,
    val xpiUrl: String,
    val homepageUrl: String
)

data class InstalledExtensionInfo(
    val id: String,
    val name: String,
    val version: String,
    val creator: String,
    val description: String,
    val enabled: Boolean,
    val allowedInPrivate: Boolean,
    val hasBrowserAction: Boolean,
    val actionTitle: String,
    val badgeText: String,
    val optionsUrl: String?
)

/**
 * Bridges Mozilla GeckoView's WebExtensionController with Pipra Browser UI.
 * Supports:
 * - Curated Mozilla AMO extension installation (.xpi)
 * - Custom AMO / direct .xpi URL installation
 * - Local .xpi file staging from Storage Access Framework content:// URIs
 * - Enable/Disable, Private Mode access toggle, Update, Uninstall
 * - BrowserAction / PageAction popup dispatch
 */
object PipraExtensionManager {

    val CURATED_CATALOG = listOf(
        CatalogExtension(
            id = "uBlock0@raymondhill.net",
            name = "uBlock Origin",
            category = "Privacy & Ad Blocking",
            summary = "Efficient wide-spectrum content & script blocker for Firefox / GeckoView.",
            xpiUrl = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi",
            homepageUrl = "https://addons.mozilla.org/en-US/firefox/addon/ublock-origin/"
        ),
        CatalogExtension(
            id = "addon@darkreader.org",
            name = "Dark Reader",
            category = "Appearance",
            summary = "Dark theme for every website with brightness and contrast controls.",
            xpiUrl = "https://addons.mozilla.org/firefox/downloads/latest/darkreader/latest.xpi",
            homepageUrl = "https://addons.mozilla.org/en-US/firefox/addon/darkreader/"
        ),
        CatalogExtension(
            id = "jid1-MnnxcxisBPnSXQ@jetpack",
            name = "Privacy Badger",
            category = "Privacy",
            summary = "Automatically learns to block invisible third-party trackers by EFF.",
            xpiUrl = "https://addons.mozilla.org/firefox/downloads/latest/privacy-badger17/latest.xpi",
            homepageUrl = "https://addons.mozilla.org/en-US/firefox/addon/privacy-badger17/"
        ),
        CatalogExtension(
            id = "sponsorBlocker@ajay.app",
            name = "SponsorBlock",
            category = "Video & Media",
            summary = "Skip sponsored segments, intros, and outros on YouTube videos.",
            xpiUrl = "https://addons.mozilla.org/firefox/downloads/latest/sponsorblock/latest.xpi",
            homepageUrl = "https://addons.mozilla.org/en-US/firefox/addon/sponsorblock/"
        ),
        CatalogExtension(
            id = "{aecec67f-0d10-4fa7-b7c7-609a2db280cf}",
            name = "Violentmonkey",
            category = "Userscripts",
            summary = "Open-source userscript manager compatible with GreasyFork scripts.",
            xpiUrl = "https://addons.mozilla.org/firefox/downloads/latest/violentmonkey/latest.xpi",
            homepageUrl = "https://addons.mozilla.org/en-US/firefox/addon/violentmonkey/"
        ),
        CatalogExtension(
            id = "jid1-BoFifL9Vbdl2zQ@jetpack",
            name = "Decentraleyes",
            category = "Privacy & Speed",
            summary = "Emulates common CDN libraries locally to reduce third-party tracking.",
            xpiUrl = "https://addons.mozilla.org/firefox/downloads/latest/decentraleyes/latest.xpi",
            homepageUrl = "https://addons.mozilla.org/en-US/firefox/addon/decentraleyes/"
        )
    )

    private val _installed = MutableStateFlow<List<InstalledExtensionInfo>>(emptyList())
    val installed: StateFlow<List<InstalledExtensionInfo>> = _installed.asStateFlow()

    private val extensionObjects = ConcurrentHashMap<String, WebExtension>()
    private val actions = ConcurrentHashMap<String, WebExtension.Action>()

    @Volatile
    private var promptHandler: ((WebExtension, List<String>, List<String>, (Boolean, Boolean) -> Unit) -> Unit)? = null

    @Volatile
    private var popupHandler: ((WebExtension, GeckoSession) -> Unit)? = null

    @Volatile
    private var runtimeRef: GeckoRuntime? = null

    fun attachRuntime(
        runtime: GeckoRuntime,
        onInstallPrompt: (WebExtension, List<String>, List<String>, (Boolean, Boolean) -> Unit) -> Unit,
        onPopupSession: (WebExtension, GeckoSession) -> Unit
    ) {
        runtimeRef = runtime
        promptHandler = onInstallPrompt
        popupHandler = onPopupSession

        runtime.webExtensionController.setPromptDelegate(object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<out String>,
                origins: Array<out String>,
                dataCollectionPermissions: Array<out String>
            ): GeckoResult<WebExtension.PermissionPromptResponse> {
                val res = GeckoResult<WebExtension.PermissionPromptResponse>()
                val handler = promptHandler
                if (handler == null) {
                    res.complete(WebExtension.PermissionPromptResponse(false, false, false))
                    return res
                }
                handler(extension, permissions.toList(), origins.toList()) { granted, privateGranted ->
                    res.complete(
                        WebExtension.PermissionPromptResponse(
                            granted,
                            privateGranted,
                            false
                        )
                    )
                }
                return res
            }

            override fun onUpdatePrompt(
                extension: WebExtension,
                newPermissions: Array<out String>,
                newOrigins: Array<out String>,
                newDataCollectionPermissions: Array<out String>
            ): GeckoResult<AllowOrDeny> = GeckoResult.fromValue(AllowOrDeny.ALLOW)
        })

        refreshInstalled(runtime)
    }

    fun refreshInstalled(runtime: GeckoRuntime? = runtimeRef) {
        val rt = runtime ?: return
        rt.webExtensionController.list().accept({ list ->
            val resolved = list.orEmpty()
            extensionObjects.clear()
            resolved.forEach { ext ->
                extensionObjects[ext.id] = ext
                wireActionDelegate(ext)
            }
            publishState(resolved)
        }, {
            // Keep existing state on transient error
        })
    }

    fun isValidExtensionInstallUri(rawUri: String): Boolean {
        val trimmed = rawUri.trim()
        if (trimmed.isBlank()) return false
        val uri = try { Uri.parse(trimmed) } catch (_: Exception) { return false }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "https") {
            val host = uri.host?.lowercase() ?: return false
            return trimmed.lowercase().contains(".xpi") || host == "addons.mozilla.org"
        }
        return scheme == "file" && trimmed.lowercase().endsWith(".xpi")
    }

    fun installFromUrl(
        rawUrl: String,
        runtime: GeckoRuntime? = runtimeRef,
        onResult: (Result<InstalledExtensionInfo>) -> Unit
    ) {
        val rt = runtime ?: run {
            onResult(Result.failure(IllegalStateException("Browser runtime is not active yet. Open a browser tab first.")))
            return
        }
        val clean = rawUrl.trim()
        if (!isValidExtensionInstallUri(clean)) {
            onResult(Result.failure(IllegalArgumentException("Enter a valid https:// addons.mozilla.org or .xpi URL.")))
            return
        }
        rt.webExtensionController.install(clean).accept({ ext ->
            if (ext == null) {
                onResult(Result.failure(IllegalStateException("Extension installation was canceled.")))
            } else {
                extensionObjects[ext.id] = ext
                wireActionDelegate(ext)
                refreshInstalled(rt)
                onResult(Result.success(toInfo(ext)))
            }
        }, { err ->
            onResult(Result.failure(err ?: IllegalStateException("Failed to install extension.")))
        })
    }

    fun installFromContentUri(
        context: Context,
        uri: Uri,
        runtime: GeckoRuntime? = runtimeRef,
        onResult: (Result<InstalledExtensionInfo>) -> Unit
    ) {
        val rt = runtime ?: run {
            onResult(Result.failure(IllegalStateException("Browser runtime is not active yet. Open a browser tab first.")))
            return
        }
        try {
            val dir = File(context.filesDir, "extensions_staging").apply { mkdirs() }
            val staged = File(dir, "ext_${System.currentTimeMillis()}.xpi")
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
                staged.length() > 0L
            } ?: false
            if (!copied) {
                staged.delete()
                onResult(Result.failure(IllegalArgumentException("Selected .xpi file could not be read.")))
                return
            }
            val fileUri = Uri.fromFile(staged).toString()
            rt.webExtensionController.install(fileUri).accept({ ext ->
                staged.delete()
                if (ext == null) {
                    onResult(Result.failure(IllegalStateException("Extension installation was canceled.")))
                } else {
                    extensionObjects[ext.id] = ext
                    wireActionDelegate(ext)
                    refreshInstalled(rt)
                    onResult(Result.success(toInfo(ext)))
                }
            }, { err ->
                staged.delete()
                onResult(Result.failure(err ?: IllegalStateException("Could not install local .xpi package.")))
            })
        } catch (e: Exception) {
            onResult(Result.failure(e))
        }
    }

    fun setEnabled(id: String, enabled: Boolean, onDone: (Boolean) -> Unit = {}) {
        val rt = runtimeRef ?: return onDone(false)
        val ext = extensionObjects[id] ?: return onDone(false)
        val call = if (enabled) {
            rt.webExtensionController.enable(ext, WebExtensionController.EnableSource.USER)
        } else {
            rt.webExtensionController.disable(ext, WebExtensionController.EnableSource.USER)
        }
        call.accept({ updated ->
            if (updated != null) extensionObjects[updated.id] = updated
            refreshInstalled(rt)
            onDone(updated != null)
        }, { onDone(false) })
    }

    fun setAllowedInPrivate(id: String, allowed: Boolean, onDone: (Boolean) -> Unit = {}) {
        val rt = runtimeRef ?: return onDone(false)
        val ext = extensionObjects[id] ?: return onDone(false)
        rt.webExtensionController.setAllowedInPrivateBrowsing(ext, allowed).accept({ updated ->
            if (updated != null) extensionObjects[updated.id] = updated
            refreshInstalled(rt)
            onDone(updated != null)
        }, { onDone(false) })
    }

    fun updateExtension(id: String, onDone: (Boolean) -> Unit = {}) {
        val rt = runtimeRef ?: return onDone(false)
        val ext = extensionObjects[id] ?: return onDone(false)
        rt.webExtensionController.update(ext).accept({ updated ->
            if (updated != null) extensionObjects[updated.id] = updated
            refreshInstalled(rt)
            onDone(true)
        }, { onDone(false) })
    }

    fun uninstall(id: String, onDone: (Boolean) -> Unit = {}) {
        val rt = runtimeRef ?: return onDone(false)
        val ext = extensionObjects[id] ?: return onDone(false)
        rt.webExtensionController.uninstall(ext).accept({
            extensionObjects.remove(id)
            actions.remove(id)
            refreshInstalled(rt)
            onDone(true)
        }, { onDone(false) })
    }

    fun triggerAction(id: String): Boolean {
        val action = actions[id] ?: return false
        action.click()
        return true
    }

    fun updateActiveSession(session: GeckoSession, active: Boolean) {
        try {
            runtimeRef?.webExtensionController?.setTabActive(session, active)
        } catch (_: Exception) {}
    }

    private fun wireActionDelegate(ext: WebExtension) {
        ext.setActionDelegate(object : WebExtension.ActionDelegate {
            override fun onBrowserAction(
                extension: WebExtension,
                session: GeckoSession?,
                action: WebExtension.Action
            ) {
                actions[extension.id] = action
                publishState(extensionObjects.values.toList())
            }

            override fun onPageAction(
                extension: WebExtension,
                session: GeckoSession?,
                action: WebExtension.Action
            ) {
                actions.putIfAbsent(extension.id, action)
                publishState(extensionObjects.values.toList())
            }

            override fun onOpenPopup(
                extension: WebExtension,
                action: WebExtension.Action
            ): GeckoResult<GeckoSession> {
                val popupSession = GeckoSession()
                runtimeRef?.let { popupSession.open(it) }
                popupHandler?.invoke(extension, popupSession)
                return GeckoResult.fromValue(popupSession)
            }

            override fun onTogglePopup(
                extension: WebExtension,
                action: WebExtension.Action
            ): GeckoResult<GeckoSession> = onOpenPopup(extension, action)
        })
    }

    private fun publishState(extensions: List<WebExtension>) {
        _installed.value = extensions
            .filter { !it.isBuiltIn }
            .map(::toInfo)
            .sortedBy { it.name.lowercase() }
    }

    private fun toInfo(ext: WebExtension): InstalledExtensionInfo {
        val meta = ext.metaData
        val action = actions[ext.id]
        return InstalledExtensionInfo(
            id = ext.id,
            name = meta.name?.takeIf { it.isNotBlank() } ?: ext.id,
            version = meta.version?.takeIf { it.isNotBlank() } ?: "1.0",
            creator = meta.creatorName?.takeIf { it.isNotBlank() } ?: "Mozilla Add-on",
            description = meta.description.orEmpty(),
            enabled = meta.enabled,
            allowedInPrivate = meta.allowedInPrivateBrowsing,
            hasBrowserAction = action != null && (action.enabled != false),
            actionTitle = action?.title.orEmpty(),
            badgeText = action?.badgeText.orEmpty(),
            optionsUrl = meta.optionsPageUrl
        )
    }
}
