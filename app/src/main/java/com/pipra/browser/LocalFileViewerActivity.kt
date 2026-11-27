package com.pipra.browser

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pipra.browser.ui.theme.PipraColors
import com.pipra.browser.ui.theme.PipraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

class LocalFileViewerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_DISPLAY_NAME = "display_name"
        const val EXTRA_MIME_TYPE = "mime_type"
        const val EXTRA_PROJECT_ROOT = "project_root"
        const val EXTRA_LINKED_TREE_URI = "linked_tree_uri"
    }

    private val linkedTreeUriState = mutableStateOf<Uri?>(null)
    private val openTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
                linkedTreeUriState.value = uri
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val uri = extractUri(intent)
        if (uri == null) {
            Toast.makeText(this, "No valid local file URI provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        intent.getStringExtra(EXTRA_LINKED_TREE_URI)?.let { rawTree ->
            try {
                linkedTreeUriState.value = Uri.parse(rawTree)
            } catch (_: Exception) {}
        }

        val store = Store(this)
        val hintedName = intent.getStringExtra(EXTRA_DISPLAY_NAME)
        val hintedMime = intent.getStringExtra(EXTRA_MIME_TYPE) ?: intent.type
        val projectRootExtra = intent.getStringExtra(EXTRA_PROJECT_ROOT)?.let { File(it) }?.takeIf { it.exists() && it.isDirectory }
        val metadata = LocalFileClassifier.inspectUri(this, uri, hintedName, hintedMime)

        setContent {
            val themeMode = store.themeMode
            PipraTheme(themeMode = themeMode) {
                LocalFileViewerRoot(
                    metadata = metadata,
                    explicitProjectRoot = projectRootExtra,
                    linkedTreeUri = linkedTreeUriState.value,
                    onPickWebsiteFolder = { openTreeLauncher.launch(null) },
                    onOpenExtractedHtml = { htmlFile, rootDir ->
                        val fileUri = PipraDownloadManager.shareUriForFile(this, htmlFile) ?: Uri.fromFile(htmlFile)
                        startActivity(Intent(this, LocalFileViewerActivity::class.java).apply {
                            action = Intent.ACTION_VIEW
                            setDataAndType(fileUri, "text/html")
                            putExtra(EXTRA_DISPLAY_NAME, htmlFile.name)
                            putExtra(EXTRA_MIME_TYPE, "text/html")
                            putExtra(EXTRA_PROJECT_ROOT, rootDir.absolutePath)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    },
                    onOpenLinkInBrowser = { webUrl ->
                        startActivity(Intent(this, MainActivity::class.java).apply {
                            action = Intent.ACTION_VIEW
                            data = Uri.parse(webUrl)
                        })
                    },
                    onShareFile = { shareCurrentFile(metadata) },
                    onOpenExternal = { openCurrentExternally(metadata) },
                    onBack = { finish() }
                )
            }
        }
    }

    private fun extractUri(intent: Intent?): Uri? {
        if (intent == null) return null
        val direct = intent.data
        if (direct != null && (direct.scheme == "content" || direct.scheme == "file")) return direct
        @Suppress("DEPRECATION")
        val stream: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        return stream?.takeIf { it.scheme == "content" || it.scheme == "file" }
    }

    private fun shareCurrentFile(meta: LocalFileMetadata) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = meta.mimeType.ifBlank { "*/*" }
            putExtra(Intent.EXTRA_STREAM, meta.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, "Share ${meta.displayName}"))
        } catch (_: Exception) {
            Toast.makeText(this, "No app available to share this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openCurrentExternally(meta: LocalFileMetadata) {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(meta.uri, meta.mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(Intent.createChooser(viewIntent, "Open with"))
        } catch (_: Exception) {
            Toast.makeText(this, "No external app can open this file type", Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalFileViewerRoot(
    metadata: LocalFileMetadata,
    explicitProjectRoot: File?,
    linkedTreeUri: Uri?,
    onPickWebsiteFolder: () -> Unit,
    onOpenExtractedHtml: (File, File) -> Unit,
    onOpenLinkInBrowser: (String) -> Unit,
    onShareFile: () -> Unit,
    onOpenExternal: () -> Unit,
    onBack: () -> Unit
) {
    var htmlSourceMode by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = PipraColors.MatteBlack,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = metadata.displayName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            color = PipraColors.SilverPrimary
                        )
                        Text(
                            text = buildString {
                                append(metadata.kind.label)
                                if (metadata.sizeBytes >= 0) {
                                    append(" · ")
                                    append(PipraDownloadManager.formatBytes(metadata.sizeBytes))
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = PipraColors.SilverMuted
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = PipraColors.SilverPrimary)
                    }
                },
                actions = {
                    if (metadata.kind == LocalFileKind.HTML) {
                        IconButton(onClick = { htmlSourceMode = !htmlSourceMode }) {
                            Icon(
                                imageVector = if (htmlSourceMode) Icons.Default.Public else Icons.Default.Code,
                                contentDescription = if (htmlSourceMode) "Webpage View" else "Source Code View",
                                tint = PipraColors.ElectricBlueLight
                            )
                        }
                    }
                    IconButton(onClick = onShareFile) {
                        Icon(Icons.Default.Share, contentDescription = "Share", tint = PipraColors.SilverPrimary)
                    }
                    IconButton(onClick = onOpenExternal) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open in another app", tint = PipraColors.SilverPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PipraColors.Charcoal)
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (metadata.kind) {
                LocalFileKind.HTML -> HtmlViewerPane(
                    metadata = metadata,
                    showSourceMode = htmlSourceMode,
                    onToggleSourceMode = { htmlSourceMode = it },
                    explicitProjectRoot = explicitProjectRoot,
                    linkedTreeUri = linkedTreeUri,
                    onPickWebsiteFolder = onPickWebsiteFolder,
                    onOpenLinkInBrowser = onOpenLinkInBrowser
                )
                LocalFileKind.MHT -> WebArchiveViewerPane(
                    metadata = metadata,
                    onOpenLinkInBrowser = onOpenLinkInBrowser
                )
                LocalFileKind.TXT, LocalFileKind.XML, LocalFileKind.CSS, LocalFileKind.JS -> CodeOrTextViewerPane(
                    metadata = metadata,
                    prettyPrintJson = false
                )
                LocalFileKind.JSON -> CodeOrTextViewerPane(
                    metadata = metadata,
                    prettyPrintJson = true
                )
                LocalFileKind.PDF -> PdfViewerPane(metadata = metadata, onOpenExternal = onOpenExternal)
                LocalFileKind.IMAGE -> ImageViewerPane(metadata = metadata)
                LocalFileKind.ZIP -> ZipViewerPane(metadata = metadata, onOpenExtractedHtml = onOpenExtractedHtml)
                LocalFileKind.APK -> ApkViewerPane(metadata = metadata, onOpenExternal = onOpenExternal)
                LocalFileKind.UNSUPPORTED -> UnsupportedFilePane(metadata = metadata, onOpenExternal = onOpenExternal)
            }
        }
    }
}

@Composable
private fun HtmlViewerPane(
    metadata: LocalFileMetadata,
    showSourceMode: Boolean,
    onToggleSourceMode: (Boolean) -> Unit,
    explicitProjectRoot: File?,
    linkedTreeUri: Uri?,
    onPickWebsiteFolder: () -> Unit,
    onOpenLinkInBrowser: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val missingResources = remember { mutableStateListOf<String>() }

    val inferredRootDir = remember(metadata, explicitProjectRoot) {
        explicitProjectRoot ?: metadata.directLocalFile?.parentFile
    }
    val relativeEntry = remember(metadata, inferredRootDir) {
        val f = metadata.directLocalFile
        if (f != null && inferredRootDir != null) {
            f.canonicalPath.removePrefix(inferredRootDir.canonicalPath).trimStart(File.separatorChar)
                .replace(File.separatorChar, '/').ifBlank { f.name }
        } else {
            metadata.displayName
        }
    }

    val projectResolver = remember(metadata.uri, inferredRootDir, relativeEntry, linkedTreeUri) {
        missingResources.clear()
        LocalWebProjectResolver(
            context = context,
            entryUri = metadata.uri,
            rootDir = inferredRootDir,
            entryRelativePath = relativeEntry,
            treeUri = linkedTreeUri
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Explicit Webpage View / Source Code View switcher
        Surface(color = PipraColors.Charcoal, border = BorderStroke(0.5.dp, PipraColors.SilverBorder)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = !showSourceMode,
                        onClick = { onToggleSourceMode(false) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        label = { Text("Webpage View") }
                    )
                    SegmentedButton(
                        selected = showSourceMode,
                        onClick = { onToggleSourceMode(true) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        label = { Text("Source Code") }
                    )
                }

                OutlinedButton(
                    onClick = onPickWebsiteFolder,
                    border = BorderStroke(1.dp, PipraColors.SilverBorder)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (linkedTreeUri != null) "Folder Linked" else "Link Folder", maxLines = 1)
                }
            }
        }

        if (!showSourceMode && missingResources.isNotEmpty()) {
            Surface(color = PipraColors.CardSurface, border = BorderStroke(1.dp, PipraColors.Warning.copy(alpha = 0.5f))) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = PipraColors.Warning, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${missingResources.size} local asset(s) missing (${missingResources.take(2).joinToString()}). Tap Link Folder to load CSS/JS/images.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PipraColors.SilverPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (showSourceMode) {
            CodeOrTextViewerPane(metadata = metadata, prettyPrintJson = false)
        } else {
            val websiteBlocker = remember { WebsiteBlocker(context) }
            val adultBlocker = remember { AdultBlockerEngine(context) }
            val adBlocker = remember { AdBlockerEngine(context) }
            val store = remember { Store(context) }
            var loadedKey by remember { mutableStateOf("") }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.apply {
                            // Strictly isolated origin: local file/content access stays disabled
                            javaScriptEnabled = store.javaScriptEnabled
                            domStorageEnabled = false
                            databaseEnabled = false
                            allowFileAccess = false
                            allowContentAccess = false
                            @Suppress("DEPRECATION")
                            run {
                                allowFileAccessFromFileURLs = false
                                allowUniversalAccessFromFileURLs = false
                            }
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            builtInZoomControls = true
                            displayZoomControls = false
                            setSupportZoom(true)
                        }
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val u = request.url
                                val scheme = u.scheme?.lowercase()
                                if (u.host == LocalWebProjectResolver.VIRTUAL_HOST) {
                                    return false
                                }
                                if (scheme == "http" || scheme == "https") {
                                    onOpenLinkInBrowser(u.toString())
                                    return true
                                }
                                return true
                            }

                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                val u = request.url
                                if (u.host == LocalWebProjectResolver.VIRTUAL_HOST) {
                                    val reqPath = u.path ?: "/"
                                    val found = projectResolver.openResource(reqPath)
                                    if (found != null) {
                                        return WebResourceResponse(found.first, "utf-8", found.second)
                                    }
                                    val cleanPath = reqPath.trimStart('/')
                                    if (cleanPath != "favicon.ico" && cleanPath.isNotBlank()) {
                                        view.post {
                                            if (cleanPath !in missingResources && missingResources.size < 25) {
                                                missingResources.add(cleanPath)
                                            }
                                        }
                                    }
                                    return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                                }
                                val urlStr = u.toString()
                                if (websiteBlocker.findMatchingRuleForUrl(urlStr) != null ||
                                    adultBlocker.isBlockedUrl(urlStr, store.adultProtectionEnabled) ||
                                    adBlocker.shouldBlockSubresource(urlStr, null, store.adBlockMode, store.shieldsEnabled, store.adBlockExceptions())
                                ) {
                                    return adBlocker.emptyResponse()
                                }
                                return null
                            }
                        }
                    }
                },
                update = { wv ->
                    val targetUrl = projectResolver.initialVirtualUrl()
                    val key = "$targetUrl|${linkedTreeUri?.toString().orEmpty()}"
                    if (loadedKey != key) {
                        loadedKey = key
                        wv.loadUrl(targetUrl)
                    }
                }
            )
        }
    }
}

@Composable
private fun WebArchiveViewerPane(
    metadata: LocalFileMetadata,
    onOpenLinkInBrowser: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val file = metadata.directLocalFile
    val savedDirCanonical = remember { File(context.filesDir, "saved_pages").canonicalPath + File.separator }
    val safeArchiveFile = remember(file) {
        if (file != null && file.canonicalPath.startsWith(savedDirCanonical) && file.exists()) file else null
    }

    if (safeArchiveFile == null) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center) {
            Text(
                "This saved archive is missing or outside Pipra's offline reading storage.",
                style = MaterialTheme.typography.bodyMedium,
                color = PipraColors.SilverPrimary
            )
        }
        return
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = false
                    allowFileAccess = true
                    allowContentAccess = false
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    builtInZoomControls = true
                    displayZoomControls = false
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val u = request.url.toString()
                        if (u.startsWith("http://") || u.startsWith("https://")) {
                            onOpenLinkInBrowser(u)
                            return true
                        }
                        return true
                    }
                }
                loadUrl(Uri.fromFile(safeArchiveFile).toString())
            }
        }
    )
}

@Composable
private fun CodeOrTextViewerPane(metadata: LocalFileMetadata, prettyPrintJson: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboardManager.current
    var loading by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf("") }
    var warning by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var wrapLines by remember { mutableStateOf(true) }

    LaunchedEffect(metadata.uri, prettyPrintJson) {
        loading = true
        error = null
        warning = null
        withContext(Dispatchers.IO) {
            try {
                val res = SafeTextReader.readBounded(context.contentResolver, metadata.uri)
                val (formatted, warn) = if (prettyPrintJson) {
                    SafeTextReader.formatJsonSafely(res.text)
                } else {
                    res.text to null
                }
                text = formatted
                warning = buildList {
                    if (res.truncated) add("Preview truncated at 2 MB to keep memory safe.")
                    if (warn != null) add(warn)
                    if (metadata.kind == LocalFileKind.JS) add("Read-only viewer: JavaScript code is NOT executed.")
                }.joinToString(" ").ifBlank { null }
            } catch (e: Exception) {
                error = "Could not read file: ${e.message ?: "Unknown error"}"
            } finally {
                loading = false
            }
        }
    }

    if (loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = PipraColors.ElectricBlue)
        }
        return
    }
    error?.let { err ->
        Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            Text(err, color = PipraColors.Danger, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            FilterChip(
                selected = wrapLines,
                onClick = { wrapLines = !wrapLines },
                label = { Text(if (wrapLines) "Wrap: On" else "Wrap: Off") }
            )
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(text.take(200_000)))
                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                },
                border = BorderStroke(1.dp, PipraColors.SilverBorder)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copy")
            }
        }

        warning?.let { warn ->
            Surface(color = PipraColors.CardSurface, border = BorderStroke(1.dp, PipraColors.SilverBorder)) {
                Text(
                    text = warn,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = PipraColors.SilverMuted
                )
            }
        }

        val vScroll = rememberScrollState()
        val hScroll = rememberScrollState()
        val textModifier = if (wrapLines) {
            Modifier.fillMaxSize().verticalScroll(vScroll).padding(14.dp)
        } else {
            Modifier.fillMaxSize().verticalScroll(vScroll).horizontalScroll(hScroll).padding(14.dp)
        }

        SelectionContainer {
            Text(
                text = text,
                modifier = textModifier,
                fontFamily = if (metadata.kind == LocalFileKind.TXT) FontFamily.Default else FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                color = PipraColors.SilverPrimary
            )
        }
    }
}

@Composable
private fun PdfViewerPane(metadata: LocalFileMetadata, onOpenExternal: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var zoomFactor by remember { mutableFloatStateOf(1f) }
    var rendererHolder by remember { mutableStateOf<Pair<ParcelFileDescriptor, PdfRenderer>?>(null) }

    DisposableEffect(metadata.uri) {
        onDispose {
            try { rendererHolder?.second?.close() } catch (_: Exception) {}
            try { rendererHolder?.first?.close() } catch (_: Exception) {}
        }
    }

    LaunchedEffect(metadata.uri) {
        loading = true
        error = null
        withContext(Dispatchers.IO) {
            try {
                val pfd = context.contentResolver.openFileDescriptor(metadata.uri, "r")
                    ?: throw IllegalStateException("Cannot open PDF file descriptor")
                val pdf = PdfRenderer(pfd)
                rendererHolder = pfd to pdf
                pageCount = pdf.pageCount
            } catch (e: Exception) {
                error = "Unable to render PDF (${e.message ?: "password-protected or damaged file"})."
            } finally {
                loading = false
            }
        }
    }

    if (loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = PipraColors.ElectricBlue)
        }
        return
    }
    error?.let { err ->
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(err, color = PipraColors.Danger, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenExternal) { Text("Open in External PDF App") }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("$pageCount page(s)", color = PipraColors.SilverMuted, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { zoomFactor = (zoomFactor - 0.25f).coerceAtLeast(1f) }) {
                    Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out", tint = PipraColors.SilverPrimary)
                }
                Text("${(zoomFactor * 100).toInt()}%", color = PipraColors.SilverPrimary, style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = { zoomFactor = (zoomFactor + 0.25f).coerceAtMost(2.5f) }) {
                    Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in", tint = PipraColors.SilverPrimary)
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(pageCount) { index ->
                val pdf = rendererHolder?.second
                if (pdf != null) {
                    var bmp by remember(index, zoomFactor) { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(index, zoomFactor) {
                        bmp = withContext(Dispatchers.IO) {
                            synchronized(pdf) {
                                pdf.openPage(index).use { page ->
                                    val targetW = ((page.width * 2) * zoomFactor).toInt().coerceIn(300, 2200)
                                    val targetH = ((page.height.toFloat() / page.width.coerceAtLeast(1)) * targetW).toInt().coerceAtLeast(200)
                                    val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                                    out.eraseColor(android.graphics.Color.WHITE)
                                    page.render(out, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    out
                                }
                            }
                        }
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, PipraColors.SilverBorder)
                    ) {
                        val rendered = bmp
                        if (rendered != null) {
                            Image(
                                bitmap = rendered.asImageBitmap(),
                                contentDescription = "Page ${index + 1}",
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = PipraColors.ElectricBlue)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun ImageViewerPane(metadata: LocalFileMetadata) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val isSvg = metadata.displayName.endsWith(".svg", ignoreCase = true) || metadata.mimeType == "image/svg+xml"
    if (isSvg) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    val svgText = try { SafeTextReader.readBounded(ctx.contentResolver, metadata.uri).text } catch (_: Exception) { "" }
                    loadDataWithBaseURL(null, "<html><body style='margin:0;display:grid;place-items:center;min-height:100vh;'>$svgText</body></html>", "text/html", "utf-8", null)
                }
            }
        )
        return
    }

    LaunchedEffect(metadata.uri) {
        withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(metadata.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
                var sample = 1
                while (maxDim / sample > 2400) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                bitmap = context.contentResolver.openInputStream(metadata.uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                if (bitmap == null) error = "Unable to decode image"
            } catch (e: Exception) {
                error = "Failed to load image: ${e.message}"
            }
        }
    }

    val bmp = bitmap
    if (error != null) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(error!!, color = PipraColors.Danger)
        }
    } else if (bmp == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = PipraColors.ElectricBlue)
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PipraColors.MatteBlack)
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        if (scale > 1.2f) {
                            scale = 1f; offsetX = 0f; offsetY = 0f
                        } else {
                            scale = 2.5f
                        }
                    })
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f; offsetY = 0f
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = metadata.displayName,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    ),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun ZipViewerPane(
    metadata: LocalFileMetadata,
    onOpenExtractedHtml: (File, File) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<ZipEntryInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var extracting by remember { mutableStateOf(false) }
    var extractResult by remember { mutableStateOf<ZipExtractResult?>(null) }

    LaunchedEffect(metadata.uri) {
        loading = true
        withContext(Dispatchers.IO) {
            try {
                entries = SafeZipHandler.listEntries(context.contentResolver, metadata.uri)
            } catch (e: Exception) {
                error = "Could not inspect archive: ${e.message}"
            } finally {
                loading = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Spacer(Modifier.height(6.dp)) }
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = PipraColors.Charcoal),
                border = BorderStroke(1.dp, PipraColors.SilverBorder)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Archive Actions", style = MaterialTheme.typography.titleMedium, color = PipraColors.SilverPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Safe extraction checks every entry for path traversal (../) and enforces a 150 MB / 2,000-file decompression guard.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PipraColors.SilverMuted
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            extracting = true
                            error = null
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val res = SafeZipHandler.safeExtract(context, metadata.uri, metadata.displayName)
                                    extractResult = res
                                } catch (e: Exception) {
                                    error = e.message ?: "Extraction failed"
                                } finally {
                                    extracting = false
                                }
                            }
                        },
                        enabled = !extracting && !loading,
                        colors = ButtonDefaults.buttonColors(containerColor = PipraColors.ElectricBlue)
                    ) {
                        Icon(Icons.Default.Unarchive, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (extracting) "Extracting safely…" else "Extract Safely to Downloads")
                    }
                    extractResult?.let { res ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Extracted ${res.extractedCount} items into ${res.outputDir.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = PipraColors.Success,
                            fontWeight = FontWeight.SemiBold
                        )
                        res.entryHtmlFile?.let { htmlFile ->
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { onOpenExtractedHtml(htmlFile, res.outputDir) },
                                colors = ButtonDefaults.buttonColors(containerColor = PipraColors.Success)
                            ) {
                                Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Open ${htmlFile.name} in Webpage View")
                            }
                        }
                    }
                    error?.let { err ->
                        Spacer(Modifier.height(8.dp))
                        Text(err, color = PipraColors.Danger, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (loading) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PipraColors.ElectricBlue)
                }
            }
        } else {
            item {
                Text(
                    "Contents (${entries.size} entries)",
                    style = MaterialTheme.typography.labelMedium,
                    color = PipraColors.ElectricBlueLight
                )
            }
            items(entries) { entry ->
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = PipraColors.Charcoal),
                    border = BorderStroke(1.dp, PipraColors.SilverBorder)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = entry.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = PipraColors.SilverPrimary
                        )
                        if (!entry.isDirectory && entry.sizeBytes >= 0) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = PipraDownloadManager.formatBytes(entry.sizeBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = PipraColors.SilverMuted
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

private data class ApkDetails(
    val appLabel: String,
    val packageName: String,
    val versionName: String,
    val permissions: List<String>
)

@Composable
private fun ApkViewerPane(metadata: LocalFileMetadata, onOpenExternal: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var details by remember { mutableStateOf<ApkDetails?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(metadata.uri) {
        withContext(Dispatchers.IO) {
            var tempCopy: File? = null
            try {
                val apkFile = metadata.directLocalFile ?: run {
                    val tmp = File(context.cacheDir, "inspect_${System.currentTimeMillis()}.apk")
                    context.contentResolver.openInputStream(metadata.uri)?.use { input ->
                        FileOutputStream(tmp).use { output -> input.copyTo(output) }
                    }
                    tempCopy = tmp
                    tmp
                }
                val pm = context.packageManager
                @Suppress("DEPRECATION")
                val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
                } else {
                    pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_PERMISSIONS)
                }
                if (pkgInfo == null) {
                    error = "Could not parse APK archive metadata; the package may be corrupted."
                } else {
                    pkgInfo.applicationInfo?.apply {
                        sourceDir = apkFile.absolutePath
                        publicSourceDir = apkFile.absolutePath
                    }
                    val label = pkgInfo.applicationInfo?.loadLabel(pm)?.toString() ?: metadata.displayName
                    details = ApkDetails(
                        appLabel = label,
                        packageName = pkgInfo.packageName.orEmpty(),
                        versionName = pkgInfo.versionName ?: "Unknown",
                        permissions = pkgInfo.requestedPermissions?.toList() ?: emptyList()
                    )
                }
            } catch (e: Exception) {
                error = "Failed to inspect APK: ${e.message}"
            } finally {
                tempCopy?.delete()
                loading = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = PipraColors.CardSurface),
                border = BorderStroke(1.dp, PipraColors.Warning)
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = PipraColors.Warning)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Security Notice: APK packages downloaded from the web are untrusted code. Pipra never bypasses Android package verification or Play Protect. Only proceed if you trust the source.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PipraColors.SilverPrimary
                    )
                }
            }
        }

        if (loading) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PipraColors.ElectricBlue)
                }
            }
        } else if (error != null) {
            item {
                Text(error!!, color = PipraColors.Danger, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            details?.let { info ->
                item {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = PipraColors.Charcoal),
                        border = BorderStroke(1.dp, PipraColors.SilverBorder)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(info.appLabel, style = MaterialTheme.typography.titleMedium, color = PipraColors.SilverPrimary)
                            Text("Package: ${info.packageName}", style = MaterialTheme.typography.bodySmall, color = PipraColors.SilverMuted)
                            Text("Version: ${info.versionName}", style = MaterialTheme.typography.bodySmall, color = PipraColors.SilverMuted)
                            Spacer(Modifier.height(6.dp))
                            Button(
                                onClick = onOpenExternal,
                                colors = ButtonDefaults.buttonColors(containerColor = PipraColors.ElectricBlue)
                            ) {
                                Icon(Icons.Default.InstallMobile, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Open with Android Installer / Files")
                            }
                            Text(
                                "Android requires explicit confirmation in your system installer or Files app before installing any APK.",
                                style = MaterialTheme.typography.bodySmall,
                                color = PipraColors.SilverMuted
                            )
                        }
                    }
                }
                item {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = PipraColors.Charcoal),
                        border = BorderStroke(1.dp, PipraColors.SilverBorder)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                "Requested Permissions (${info.permissions.size})",
                                style = MaterialTheme.typography.titleMedium,
                                color = PipraColors.SilverPrimary
                            )
                            Spacer(Modifier.height(6.dp))
                            if (info.permissions.isEmpty()) {
                                Text("None declared.", style = MaterialTheme.typography.bodySmall, color = PipraColors.SilverMuted)
                            } else {
                                info.permissions.forEach { perm ->
                                    Text(perm, style = MaterialTheme.typography.bodySmall, color = PipraColors.SilverMuted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UnsupportedFilePane(metadata: LocalFileMetadata, onOpenExternal: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Cannot preview ${metadata.displayName} internally", style = MaterialTheme.typography.titleMedium, color = PipraColors.SilverPrimary)
        Spacer(Modifier.height(6.dp))
        Text("MIME type: ${metadata.mimeType}", style = MaterialTheme.typography.bodySmall, color = PipraColors.SilverMuted)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenExternal, colors = ButtonDefaults.buttonColors(containerColor = PipraColors.ElectricBlue)) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Open with Another App")
        }
    }
}
