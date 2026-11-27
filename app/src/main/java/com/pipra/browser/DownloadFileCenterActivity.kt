package com.pipra.browser

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Html
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pipra.browser.ui.theme.PipraColors
import com.pipra.browser.ui.theme.PipraTheme
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private enum class DownloadFilter(val label: String) {
    ALL("All"),
    ACTIVE("Active"),
    COMPLETED("Completed"),
    FAILED("Failed")
}

private data class FolderFileEntry(
    val name: String,
    val uri: Uri,
    val mimeType: String
)

class DownloadFileCenterActivity : ComponentActivity() {

    private var folderPickerDialogState = mutableStateOf<Pair<Uri, List<FolderFileEntry>>?>(null)

    private val openLocalFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
                launchLocalViewer(uri, null, null, null)
            }
        }

    private val openProjectFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri != null) {
                try {
                    contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
                val files = listRootFolderFiles(treeUri)
                if (files.isEmpty()) {
                    Toast.makeText(this, "No readable files found at the root of this folder", Toast.LENGTH_SHORT).show()
                } else {
                    val index = files.firstOrNull {
                        it.name.equals("index.html", ignoreCase = true) ||
                            it.name.equals("index.htm", ignoreCase = true)
                    }
                    if (index != null && files.size == 1) {
                        launchLocalViewer(index.uri, index.name, index.mimeType, treeUri)
                    } else {
                        folderPickerDialogState.value = treeUri to files
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        PipraDownloadManager.ensureInitialized(this)

        val store = Store(this)

        setContent {
            PipraTheme(themeMode = store.themeMode) {
                val allItems by PipraDownloadManager.items.collectAsStateWithLifecycle()
                DownloadFileCenterScreen(
                    items = allItems,
                    folderCandidates = folderPickerDialogState.value,
                    onDismissFolderDialog = { folderPickerDialogState.value = null },
                    onPickFolderFile = { treeUri, entry ->
                        folderPickerDialogState.value = null
                        launchLocalViewer(entry.uri, entry.name, entry.mimeType, treeUri)
                    },
                    onOpenAnyFile = {
                        openLocalFile.launch(arrayOf("*/*"))
                    },
                    onOpenFolder = {
                        openProjectFolder.launch(null)
                    },
                    onOpenItem = { item ->
                        if (!PipraDownloadManager.openDownloadItem(this, item)) {
                            Toast.makeText(this, "Downloaded file could not be opened or is missing", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onPauseItem = { PipraDownloadManager.pause(this, it.id) },
                    onResumeItem = { PipraDownloadManager.resume(this, it.id) },
                    onCancelItem = { PipraDownloadManager.cancel(this, it.id) },
                    onRetryItem = { PipraDownloadManager.retry(this, it.id) },
                    onRenameItem = { item, newName ->
                        val res = PipraDownloadManager.rename(this, item.id, newName)
                        if (res.isSuccess) {
                            Toast.makeText(this, "Renamed to ${res.getOrNull()?.fileName}", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, res.exceptionOrNull()?.message ?: "Rename failed", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onShareItem = { item -> shareDownload(item) },
                    onOpenFolderLocation = { item -> openContainingFolder(item) },
                    onDeleteItem = { item, deleteFile ->
                        PipraDownloadManager.delete(this, item.id, deleteFile)
                    },
                    onClearHistory = {
                        PipraDownloadManager.clearAllHistory(this)
                        Toast.makeText(this, "Completed download history cleared", Toast.LENGTH_SHORT).show()
                    },
                    onBack = { finish() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PipraDownloadManager.refreshFromStore(this)
    }

    private fun launchLocalViewer(uri: Uri, name: String?, mime: String?, treeUri: Uri?) {
        val intent = Intent(this, LocalFileViewerActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(uri, mime ?: "*/*")
            if (!name.isNullOrBlank()) putExtra(LocalFileViewerActivity.EXTRA_DISPLAY_NAME, name)
            if (!mime.isNullOrBlank()) putExtra(LocalFileViewerActivity.EXTRA_MIME_TYPE, mime)
            if (treeUri != null) putExtra(LocalFileViewerActivity.EXTRA_LINKED_TREE_URI, treeUri.toString())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    private fun listRootFolderFiles(treeUri: Uri): List<FolderFileEntry> {
        val out = mutableListOf<FolderFileEntry>()
        val docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            return out
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        try {
            contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (c.moveToNext() && out.size < 200) {
                    val childId = if (idIdx >= 0) c.getString(idIdx) else continue
                    val name = if (nameIdx >= 0) c.getString(nameIdx) else continue
                    val mime = if (mimeIdx >= 0) c.getString(mimeIdx) else ""
                    if (mime != DocumentsContract.Document.MIME_TYPE_DIR) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                        out.add(
                            FolderFileEntry(
                                name = name,
                                uri = uri,
                                mimeType = PipraDownloadManager.resolveMimeType(name, mime, null)
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        return out.sortedWith(
            compareBy<FolderFileEntry> {
                when {
                    it.name.equals("index.html", ignoreCase = true) -> 0
                    it.name.equals("index.htm", ignoreCase = true) -> 1
                    it.name.endsWith(".html", ignoreCase = true) || it.name.endsWith(".htm", ignoreCase = true) -> 2
                    else -> 3
                }
            }.thenBy { it.name.lowercase(Locale.ROOT) }
        )
    }

    private fun shareDownload(item: DownloadItem) {
        val file = File(item.localPath)
        if (!file.exists()) {
            Toast.makeText(this, "File not found on device", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = PipraDownloadManager.shareUriForFile(this, file)
        if (uri == null) {
            Toast.makeText(this, "Cannot share file from this path", Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType.ifBlank { "*/*" }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, "Share ${item.fileName}"))
        } catch (_: Exception) {
            Toast.makeText(this, "No app available to share this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openContainingFolder(item: DownloadItem) {
        val file = File(item.localPath)
        val dir = file.parentFile ?: PipraDownloadManager.downloadsDir(this)
        val dirIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(dirIntent)
            return
        } catch (_: Exception) {}
        Toast.makeText(this, "Saved folder: ${dir.absolutePath}", Toast.LENGTH_LONG).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DownloadFileCenterScreen(
    items: List<DownloadItem>,
    folderCandidates: Pair<Uri, List<FolderFileEntry>>?,
    onDismissFolderDialog: () -> Unit,
    onPickFolderFile: (Uri, FolderFileEntry) -> Unit,
    onOpenAnyFile: () -> Unit,
    onOpenFolder: () -> Unit,
    onOpenItem: (DownloadItem) -> Unit,
    onPauseItem: (DownloadItem) -> Unit,
    onResumeItem: (DownloadItem) -> Unit,
    onCancelItem: (DownloadItem) -> Unit,
    onRetryItem: (DownloadItem) -> Unit,
    onRenameItem: (DownloadItem, String) -> Unit,
    onShareItem: (DownloadItem) -> Unit,
    onOpenFolderLocation: (DownloadItem) -> Unit,
    onDeleteItem: (DownloadItem, Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onBack: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(DownloadFilter.ALL) }
    var renameTarget by remember { mutableStateOf<DownloadItem?>(null) }
    var deleteTarget by remember { mutableStateOf<DownloadItem?>(null) }
    var clearConfirm by remember { mutableStateOf(false) }

    val filtered = remember(items, query, filter) {
        val q = query.trim().lowercase(Locale.ROOT)
        items.filter { item ->
            val matchesFilter = when (filter) {
                DownloadFilter.ALL -> true
                DownloadFilter.ACTIVE -> item.status == DownloadStatus.QUEUED ||
                    item.status == DownloadStatus.DOWNLOADING ||
                    item.status == DownloadStatus.PAUSED
                DownloadFilter.COMPLETED -> item.status == DownloadStatus.COMPLETED
                DownloadFilter.FAILED -> item.status == DownloadStatus.FAILED ||
                    item.status == DownloadStatus.CANCELED
            }
            val matchesQuery = q.isEmpty() ||
                item.fileName.lowercase(Locale.ROOT).contains(q) ||
                item.url.lowercase(Locale.ROOT).contains(q)
            matchesFilter && matchesQuery
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = PipraColors.MatteBlack,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Downloads & Files", color = PipraColors.SilverPrimary) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("downloads_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = PipraColors.SilverPrimary
                        )
                    }
                },
                actions = {
                    if (items.isNotEmpty()) {
                        IconButton(
                            onClick = { clearConfirm = true },
                            modifier = Modifier.testTag("downloads_clear_history")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Clear download history",
                                tint = PipraColors.SilverMuted
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PipraColors.Charcoal,
                    titleContentColor = PipraColors.SilverPrimary
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .testTag("downloads_center_list"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = PipraColors.Charcoal),
                    border = BorderStroke(1.dp, PipraColors.SilverBorder)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Local File & Webpage Viewer",
                            style = MaterialTheme.typography.labelMedium,
                            color = PipraColors.ElectricBlueLight,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Open local HTML websites, ZIP archives, PDFs, TXT, JSON, XML/CSS/JS, images, and APKs safely.",
                            style = MaterialTheme.typography.bodySmall,
                            color = PipraColors.SilverMuted
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = onOpenAnyFile,
                                modifier = Modifier.weight(1f).testTag("open_local_file_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = PipraColors.ElectricBlue)
                            ) {
                                Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Open File")
                            }
                            OutlinedButton(
                                onClick = onOpenFolder,
                                modifier = Modifier.weight(1f).testTag("open_local_folder_button"),
                                border = BorderStroke(1.dp, PipraColors.SilverBorder)
                            ) {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    tint = PipraColors.SilverPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Open Folder", color = PipraColors.SilverPrimary)
                            }
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = PipraColors.SilverMuted)
                    },
                    placeholder = { Text("Search downloads by name or URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("downloads_search_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = PipraColors.SilverPrimary,
                        unfocusedTextColor = PipraColors.SilverPrimary,
                        focusedBorderColor = PipraColors.ElectricBlue,
                        unfocusedBorderColor = PipraColors.SilverBorder,
                        focusedPlaceholderColor = PipraColors.SilverMuted,
                        unfocusedPlaceholderColor = PipraColors.SilverMuted
                    )
                )
            }

            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DownloadFilter.entries.forEach { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(f.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PipraColors.ElectricBlue.copy(alpha = 0.18f),
                                selectedLabelColor = PipraColors.ElectricBlueLight,
                                labelColor = PipraColors.SilverMuted
                            )
                        )
                    }
                }
            }

            if (filtered.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = PipraColors.CardSurface),
                        border = BorderStroke(1.dp, PipraColors.SilverBorder)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = if (items.isEmpty()) "No downloads yet" else "No downloads match this filter",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = PipraColors.SilverPrimary
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Files downloaded from the browser appear here with progress, actions, and local viewing.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PipraColors.SilverMuted
                                )
                            }
                        }
                    }
                }
            } else {
                items(filtered, key = { it.id }) { item ->
                    DownloadItemCard(
                        item = item,
                        onOpen = { onOpenItem(item) },
                        onPause = { onPauseItem(item) },
                        onResume = { onResumeItem(item) },
                        onCancel = { onCancelItem(item) },
                        onRetry = { onRetryItem(item) },
                        onRename = { renameTarget = item },
                        onShare = { onShareItem(item) },
                        onOpenFolder = { onOpenFolderLocation(item) },
                        onDelete = { deleteTarget = item }
                    )
                }
            }

            item { Spacer(Modifier.height(18.dp)) }
        }
    }

    renameTarget?.let { item ->
        var draft by remember(item.id) { mutableStateOf(item.fileName) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = PipraColors.Charcoal,
            title = { Text("Rename File", color = PipraColors.SilverPrimary) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text("Filename") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = PipraColors.SilverPrimary,
                        unfocusedTextColor = PipraColors.SilverPrimary,
                        focusedBorderColor = PipraColors.ElectricBlue,
                        unfocusedBorderColor = PipraColors.SilverBorder
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRenameItem(item, draft)
                        renameTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PipraColors.ElectricBlue)
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel", color = PipraColors.SilverMuted) }
            }
        )
    }

    deleteTarget?.let { item ->
        var alsoDeleteFile by remember(item.id) { mutableStateOf(true) }
        val fileExists = item.localPath.isNotBlank() && File(item.localPath).exists()
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = PipraColors.Charcoal,
            title = { Text("Remove Download?", color = PipraColors.SilverPrimary) },
            text = {
                Column {
                    Text(item.fileName, color = PipraColors.SilverMuted)
                    if (fileExists) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = alsoDeleteFile,
                                onCheckedChange = { alsoDeleteFile = it },
                                colors = CheckboxDefaults.colors(checkedColor = PipraColors.Danger)
                            )
                            Text("Also delete local file from storage", color = PipraColors.SilverPrimary)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteItem(item, alsoDeleteFile && fileExists)
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PipraColors.Danger)
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = PipraColors.SilverMuted) }
            }
        )
    }

    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            containerColor = PipraColors.Charcoal,
            title = { Text("Clear Download History?", color = PipraColors.SilverPrimary) },
            text = {
                Text(
                    "Removes completed, failed, and canceled entries from this list. Downloaded files remain saved on device storage.",
                    color = PipraColors.SilverMuted
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        clearConfirm = false
                        onClearHistory()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PipraColors.ElectricBlue)
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirm = false }) { Text("Cancel", color = PipraColors.SilverMuted) }
            }
        )
    }

    folderCandidates?.let { (treeUri, entries) ->
        AlertDialog(
            onDismissRequest = onDismissFolderDialog,
            containerColor = PipraColors.Charcoal,
            title = { Text("Open File from Folder", color = PipraColors.SilverPrimary) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(entries) { entry ->
                        Surface(
                            onClick = { onPickFolderFile(treeUri, entry) },
                            color = PipraColors.CardSurface,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, PipraColors.SilverBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                Text(
                                    entry.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = PipraColors.SilverPrimary
                                )
                                Text(
                                    entry.mimeType,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PipraColors.SilverMuted
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissFolderDialog) {
                    Text("Close", color = PipraColors.SilverMuted)
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadItemCard(
    item: DownloadItem,
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onOpenFolder: () -> Unit,
    onDelete: () -> Unit
) {
    val fileExists = item.localPath.isNotBlank() && File(item.localPath).exists()
    val host = DomainUtils.extractHostFromUrl(item.url) ?: item.url
    val dateStr = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.createdAt))
    val statusColor = when (item.status) {
        DownloadStatus.COMPLETED -> PipraColors.Success
        DownloadStatus.FAILED, DownloadStatus.CANCELED -> PipraColors.Danger
        DownloadStatus.PAUSED -> PipraColors.Warning
        else -> PipraColors.ElectricBlueLight
    }

    Card(
        modifier = Modifier.fillMaxWidth().testTag("download_card_${item.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = PipraColors.Charcoal),
        border = BorderStroke(1.dp, PipraColors.SilverBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = fileTypeIcon(item.fileName, item.mimeType),
                    contentDescription = null,
                    tint = PipraColors.ElectricBlueLight,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        color = PipraColors.SilverPrimary
                    )
                    Text(
                        text = "$host · $dateStr",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = PipraColors.SilverMuted
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.status.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor
                )
            }

            Spacer(Modifier.height(8.dp))
            val sizeText = buildString {
                when (item.status) {
                    DownloadStatus.DOWNLOADING -> {
                        append(PipraDownloadManager.formatBytes(item.downloadedBytes))
                        if (item.totalBytes > 0L) {
                            append(" / ")
                            append(PipraDownloadManager.formatBytes(item.totalBytes))
                        }
                        if (item.speedBytesPerSec > 0L) {
                            append(" · ")
                            append(PipraDownloadManager.formatBytes(item.speedBytesPerSec))
                            append("/s")
                        }
                    }
                    DownloadStatus.PAUSED -> {
                        append(PipraDownloadManager.formatBytes(item.downloadedBytes))
                        if (item.totalBytes > 0L) append(" / ${PipraDownloadManager.formatBytes(item.totalBytes)}")
                        append(" · Paused")
                    }
                    DownloadStatus.COMPLETED -> {
                        val size = if (fileExists) File(item.localPath).length() else item.totalBytes
                        if (size > 0L) append(PipraDownloadManager.formatBytes(size))
                        else append(item.mimeType)
                        if (item.localPath.isNotBlank() && !fileExists) append(" · File moved or deleted")
                    }
                    else -> {
                        append(item.mimeType)
                    }
                }
            }
            Text(sizeText, style = MaterialTheme.typography.bodySmall, color = PipraColors.SilverMuted)

            if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.QUEUED || item.status == DownloadStatus.PAUSED) {
                Spacer(Modifier.height(6.dp))
                if (item.totalBytes > 0L && item.downloadedBytes >= 0L) {
                    val pct = (item.downloadedBytes.toFloat() / item.totalBytes.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { pct },
                        modifier = Modifier.fillMaxWidth(),
                        color = PipraColors.ElectricBlue,
                        trackColor = PipraColors.CardElevated
                    )
                } else if (item.status != DownloadStatus.PAUSED) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = PipraColors.ElectricBlue,
                        trackColor = PipraColors.CardElevated
                    )
                }
                if (item.status == DownloadStatus.DOWNLOADING && !item.supportsRange) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Pause unavailable (server does not support Range requests)",
                        style = MaterialTheme.typography.bodySmall,
                        color = PipraColors.SilverMuted
                    )
                }
            }

            if (item.errorMessage.isNotBlank() && item.status != DownloadStatus.COMPLETED) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.status == DownloadStatus.FAILED) PipraColors.Danger else PipraColors.SilverMuted
                )
            }

            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                when (item.status) {
                    DownloadStatus.COMPLETED -> {
                        if (fileExists) {
                            SmallActionChip("Open", Icons.AutoMirrored.Filled.OpenInNew, primary = true, onClick = onOpen)
                            SmallActionChip("Share", Icons.Default.Share, onClick = onShare)
                            SmallActionChip("Rename", Icons.Default.Edit, onClick = onRename)
                            SmallActionChip("Folder", Icons.Default.Folder, onClick = onOpenFolder)
                        } else if (item.url.startsWith("http")) {
                            SmallActionChip("Re-download", Icons.Default.Refresh, onClick = onRetry)
                        }
                        SmallActionChip("Remove", Icons.Default.Delete, onClick = onDelete)
                    }
                    DownloadStatus.DOWNLOADING -> {
                        if (item.supportsRange) {
                            SmallActionChip("Pause", Icons.Default.Pause, primary = true, onClick = onPause)
                        }
                        SmallActionChip("Cancel", Icons.Default.Cancel, onClick = onCancel)
                    }
                    DownloadStatus.QUEUED -> {
                        SmallActionChip("Cancel", Icons.Default.Cancel, onClick = onCancel)
                    }
                    DownloadStatus.PAUSED -> {
                        if (item.supportsRange) {
                            SmallActionChip("Resume", Icons.Default.PlayArrow, primary = true, onClick = onResume)
                        }
                        SmallActionChip("Cancel", Icons.Default.Cancel, onClick = onCancel)
                    }
                    DownloadStatus.FAILED, DownloadStatus.CANCELED -> {
                        SmallActionChip("Retry", Icons.Default.Refresh, primary = true, onClick = onRetry)
                        SmallActionChip("Remove", Icons.Default.Delete, onClick = onDelete)
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallActionChip(
    label: String,
    icon: ImageVector,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, if (primary) PipraColors.ElectricBlue else PipraColors.SilverBorder),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (primary) PipraColors.ElectricBlueLight else PipraColors.SilverPrimary
        )
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

private fun fileTypeIcon(name: String, mime: String): ImageVector {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val m = mime.lowercase(Locale.ROOT)
    return when {
        ext == "html" || ext == "htm" || m == "text/html" -> Icons.Default.Html
        ext == "pdf" || m == "application/pdf" -> Icons.Default.PictureAsPdf
        ext == "zip" || m.contains("zip") -> Icons.Default.FolderZip
        ext == "apk" || m.contains("package-archive") -> Icons.Default.Android
        ext in setOf("json", "xml", "css", "js", "mjs") -> Icons.Default.Code
        m.startsWith("image/") || ext in setOf("png", "jpg", "jpeg", "webp", "gif", "svg") -> Icons.Default.Image
        m.startsWith("text/") || ext in setOf("txt", "md", "log", "csv") -> Icons.Default.Description
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}
