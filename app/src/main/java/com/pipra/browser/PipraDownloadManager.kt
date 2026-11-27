package com.pipra.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Unified, streaming V3 Download Manager for Pipra Browser.
 * - Persists records across restarts via Store.downloadItemsV3()
 * - Streams directly to app-private ExternalFilesDir/Downloads via .part staging files
 * - Measures real-time progress and transfer speed
 * - Detects Accept-Ranges / 206 Partial Content so Pause/Resume is offered only when supported
 */
object PipraDownloadManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val activeCalls = ConcurrentHashMap<String, Call>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val pausedIds = ConcurrentHashMap.newKeySet<String>()
    private val canceledIds = ConcurrentHashMap.newKeySet<String>()

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    @Volatile
    private var initialized = false

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appCtx = context.applicationContext
            val store = Store(appCtx)
            val recovered = store.downloadItemsV3().map { item ->
                if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.QUEUED) {
                    val part = partFileFor(item)
                    if (item.supportsRange && part.exists() && part.length() > 0L) {
                        item.copy(
                            status = DownloadStatus.PAUSED,
                            downloadedBytes = part.length(),
                            speedBytesPerSec = 0L,
                            errorMessage = "Paused when browser closed"
                        )
                    } else {
                        part.delete()
                        item.copy(
                            status = DownloadStatus.FAILED,
                            speedBytesPerSec = 0L,
                            errorMessage = "Interrupted before completion"
                        )
                    }
                } else if (item.status == DownloadStatus.COMPLETED && item.localPath.isNotBlank() && !File(item.localPath).exists()) {
                    item
                } else {
                    item
                }
            }
            store.saveDownloadItemsV3(recovered)
            _items.value = recovered
            initialized = true
        }
    }

    fun downloadsDir(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun refreshFromStore(context: Context) {
        ensureInitialized(context)
        _items.value = Store(context.applicationContext).downloadItemsV3()
    }

    sealed class EnqueueResult {
        data class Started(val item: DownloadItem) : EnqueueResult()
        data class DuplicateActive(val existing: DownloadItem) : EnqueueResult()
        data class Error(val message: String) : EnqueueResult()
    }

    fun enqueue(
        context: Context,
        url: String,
        userAgent: String? = null,
        contentDisposition: String? = null,
        hintedMimeType: String? = null,
        cookieHeader: String? = null,
        recordInHistory: Boolean = true
    ): EnqueueResult {
        val appCtx = context.applicationContext
        ensureInitialized(appCtx)

        val trimmed = url.trim()
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
            return EnqueueResult.Error("Only HTTP and HTTPS downloads are supported")
        }

        // Prevent accidental duplicate downloads on repeated taps.
        val existingActive = _items.value.firstOrNull {
            it.url == trimmed && (it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING)
        }
        if (existingActive != null) {
            return EnqueueResult.DuplicateActive(existingActive)
        }

        val mime = resolveMimeType(trimmed, hintedMimeType, contentDisposition)
        val rawName = URLUtil.guessFileName(trimmed, contentDisposition, mime)
        val uniqueFile = uniqueTargetFile(downloadsDir(appCtx), DomainUtils.sanitizeDownloadFileName(rawName))

        val id = UUID.randomUUID().toString()
        val item = DownloadItem(
            id = id,
            fileName = uniqueFile.name,
            url = trimmed,
            mimeType = mime,
            status = DownloadStatus.QUEUED,
            localPath = uniqueFile.absolutePath,
            createdAt = System.currentTimeMillis()
        )
        if (recordInHistory) {
            insertOrUpdateItem(appCtx, item)
        } else {
            // Keep transient in-memory item for active session only when invoked privately
            _items.value = listOf(item) + _items.value
        }

        startTransfer(
            appCtx = appCtx,
            itemId = id,
            userAgent = userAgent,
            cookieHeader = cookieHeader,
            resumeExisting = false,
            persist = recordInHistory
        )
        return EnqueueResult.Started(item)
    }

    fun pause(context: Context, id: String) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        if (!item.supportsRange || item.status != DownloadStatus.DOWNLOADING) return
        pausedIds.add(id)
        activeCalls.remove(id)?.cancel()
        activeJobs.remove(id)?.cancel()
        updateItem(context.applicationContext, id, persist = true) {
            it.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L)
        }
    }

    fun resume(context: Context, id: String) {
        val appCtx = context.applicationContext
        ensureInitialized(appCtx)
        val item = _items.value.firstOrNull { it.id == id } ?: return
        if (item.status != DownloadStatus.PAUSED || !item.supportsRange) return
        pausedIds.remove(id)
        canceledIds.remove(id)
        updateItem(appCtx, id, persist = true) {
            it.copy(status = DownloadStatus.QUEUED, errorMessage = "", speedBytesPerSec = 0L)
        }
        startTransfer(appCtx, id, userAgent = null, cookieHeader = null, resumeExisting = true, persist = true)
    }

    fun cancel(context: Context, id: String) {
        val appCtx = context.applicationContext
        canceledIds.add(id)
        pausedIds.remove(id)
        activeCalls.remove(id)?.cancel()
        activeJobs.remove(id)?.cancel()
        val cur = _items.value.firstOrNull { it.id == id }
        if (cur != null) {
            partFileFor(cur).delete()
        }
        updateItem(appCtx, id, persist = true) {
            it.copy(status = DownloadStatus.CANCELED, speedBytesPerSec = 0L, errorMessage = "Canceled by user")
        }
    }

    fun retry(context: Context, id: String) {
        val appCtx = context.applicationContext
        ensureInitialized(appCtx)
        val item = _items.value.firstOrNull { it.id == id } ?: return
        if (item.status != DownloadStatus.FAILED && item.status != DownloadStatus.CANCELED) return
        pausedIds.remove(id)
        canceledIds.remove(id)
        val part = partFileFor(item)
        val canResume = item.supportsRange && part.exists() && part.length() > 0L
        if (!canResume) part.delete()
        updateItem(appCtx, id, persist = true) {
            it.copy(
                status = DownloadStatus.QUEUED,
                downloadedBytes = if (canResume) part.length() else 0L,
                speedBytesPerSec = 0L,
                errorMessage = ""
            )
        }
        startTransfer(appCtx, id, userAgent = null, cookieHeader = null, resumeExisting = canResume, persist = true)
    }

    fun rename(context: Context, id: String, rawNewName: String): Result<DownloadItem> {
        val appCtx = context.applicationContext
        val item = _items.value.firstOrNull { it.id == id }
            ?: return Result.failure(IllegalArgumentException("Download record not found"))
        if (item.status != DownloadStatus.COMPLETED) {
            return Result.failure(IllegalStateException("Only completed downloads can be renamed"))
        }
        val currentFile = File(item.localPath)
        if (!currentFile.exists()) {
            return Result.failure(IOException("Downloaded file no longer exists on device"))
        }
        val sanitized = DomainUtils.sanitizeDownloadFileName(rawNewName)
        if (sanitized.isBlank()) {
            return Result.failure(IllegalArgumentException("Enter a valid filename"))
        }
        val target = if (sanitized == currentFile.name) {
            currentFile
        } else {
            uniqueTargetFile(currentFile.parentFile ?: downloadsDir(appCtx), sanitized)
        }
        if (target != currentFile && !currentFile.renameTo(target)) {
            return Result.failure(IOException("Could not rename file"))
        }
        val newMime = resolveMimeType(target.name, item.mimeType, null)
        val updated = item.copy(fileName = target.name, localPath = target.absolutePath, mimeType = newMime)
        updateItem(appCtx, id, persist = true) { updated }
        return Result.success(updated)
    }

    fun delete(context: Context, id: String, deleteLocalFile: Boolean) {
        val appCtx = context.applicationContext
        canceledIds.add(id)
        activeCalls.remove(id)?.cancel()
        activeJobs.remove(id)?.cancel()
        val item = _items.value.firstOrNull { it.id == id }
        if (item != null) {
            partFileFor(item).delete()
            if (deleteLocalFile && item.localPath.isNotBlank()) {
                try { File(item.localPath).delete() } catch (_: Exception) {}
            }
        }
        val next = _items.value.filterNot { it.id == id }
        _items.value = next
        Store(appCtx).saveDownloadItemsV3(next)
    }

    fun clearAllHistory(context: Context) {
        val appCtx = context.applicationContext
        val active = _items.value.filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING }
        _items.value = active
        Store(appCtx).saveDownloadItemsV3(active)
    }

    fun shareUriForFile(context: Context, file: File): Uri? {
        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: Exception) {
            null
        }
    }

    fun openDownloadItem(context: Context, item: DownloadItem): Boolean {
        val file = File(item.localPath)
        if (item.status != DownloadStatus.COMPLETED || !file.exists()) return false
        val uri = shareUriForFile(context, file) ?: Uri.fromFile(file)
        if (LocalFileClassifier.canOpenInPipraViewer(item.fileName, item.mimeType)) {
            val intent = Intent(context, LocalFileViewerActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                setDataAndType(uri, item.mimeType)
                putExtra(LocalFileViewerActivity.EXTRA_DISPLAY_NAME, item.fileName)
                putExtra(LocalFileViewerActivity.EXTRA_MIME_TYPE, item.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            return true
        }
        return openExternally(context, file, item.mimeType)
    }

    fun openExternally(context: Context, file: File, mimeType: String): Boolean {
        val uri = shareUriForFile(context, file) ?: return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(Intent.createChooser(intent, "Open with"))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun startTransfer(
        appCtx: Context,
        itemId: String,
        userAgent: String?,
        cookieHeader: String?,
        resumeExisting: Boolean,
        persist: Boolean
    ) {
        val job = scope.launch {
            val initial = _items.value.firstOrNull { it.id == itemId } ?: return@launch
            val finalFile = File(initial.localPath)
            val partFile = partFileFor(initial)
            finalFile.parentFile?.mkdirs()

            val existingBytes = if (resumeExisting && partFile.exists()) partFile.length() else 0L
            if (!resumeExisting && partFile.exists()) partFile.delete()

            val reqBuilder = Request.Builder().url(initial.url)
            if (!userAgent.isNullOrBlank()) reqBuilder.header("User-Agent", userAgent)
            if (!cookieHeader.isNullOrBlank()) reqBuilder.header("Cookie", cookieHeader)
            if (existingBytes > 0L) {
                reqBuilder.header("Range", "bytes=$existingBytes-")
                val validator = initial.eTag.ifBlank { initial.lastModified }
                if (validator.isNotBlank()) reqBuilder.header("If-Range", validator)
            }

            val call = client.newCall(reqBuilder.build())
            activeCalls[itemId] = call

            try {
                call.execute().use { resp ->
                    if (resp.code == 416) {
                        partFile.delete()
                        throw IOException("Server could not resume from byte $existingBytes; please retry from start")
                    }
                    if (!resp.isSuccessful) {
                        throw IOException("HTTP ${resp.code} ${resp.message}")
                    }

                    val body = resp.body ?: throw IOException("Empty response body")
                    val isPartial = resp.code == 206
                    val acceptRanges = resp.header("Accept-Ranges")?.equals("bytes", ignoreCase = true) == true || isPartial
                    val eTag = resp.header("ETag").orEmpty()
                    val lastMod = resp.header("Last-Modified").orEmpty()
                    val headerContentType = resp.header("Content-Type")?.substringBefore(';')?.trim()
                    val headerDisposition = resp.header("Content-Disposition")
                    val effectiveMime = resolveMimeType(initial.fileName, headerContentType ?: initial.mimeType, headerDisposition)

                    val contentLength = body.contentLength()
                    val totalBytes = when {
                        isPartial && contentLength > 0L -> existingBytes + contentLength
                        !isPartial && contentLength > 0L -> contentLength
                        else -> -1L
                    }

                    // Check available space when total size is known
                    if (totalBytes > 0L) {
                        val needed = (totalBytes - if (isPartial) existingBytes else 0L).coerceAtLeast(0L)
                        if (!hasEnoughSpace(finalFile.parentFile ?: downloadsDir(appCtx), needed)) {
                            throw IOException("Not enough storage space available")
                        }
                    }

                    val startingOffset = if (isPartial) existingBytes else 0L
                    if (!isPartial && partFile.exists()) partFile.delete()

                    updateItem(appCtx, itemId, persist) {
                        it.copy(
                            status = DownloadStatus.DOWNLOADING,
                            mimeType = effectiveMime,
                            totalBytes = totalBytes,
                            downloadedBytes = startingOffset,
                            supportsRange = acceptRanges,
                            eTag = eTag,
                            lastModified = lastMod,
                            errorMessage = ""
                        )
                    }

                    var downloaded = startingOffset
                    val buf = ByteArray(16 * 1024)
                    var lastReportTime = System.currentTimeMillis()
                    var bytesSinceReport = 0L

                    FileOutputStream(partFile, isPartial).use { out ->
                        body.byteStream().use { input ->
                            while (true) {
                                if (pausedIds.contains(itemId) || canceledIds.contains(itemId)) {
                                    break
                                }
                                val read = input.read(buf)
                                if (read == -1) break
                                out.write(buf, 0, read)
                                downloaded += read
                                bytesSinceReport += read

                                val now = System.currentTimeMillis()
                                val elapsedMs = now - lastReportTime
                                if (elapsedMs >= 350L) {
                                    val speed = (bytesSinceReport * 1000L) / elapsedMs.coerceAtLeast(1L)
                                    updateItem(appCtx, itemId, persist = false) {
                                        it.copy(
                                            status = DownloadStatus.DOWNLOADING,
                                            downloadedBytes = downloaded,
                                            speedBytesPerSec = speed
                                        )
                                    }
                                    lastReportTime = now
                                    bytesSinceReport = 0L
                                }
                            }
                        }
                    }

                    if (pausedIds.remove(itemId)) {
                        updateItem(appCtx, itemId, persist) {
                            it.copy(status = DownloadStatus.PAUSED, downloadedBytes = downloaded, speedBytesPerSec = 0L)
                        }
                        return@use
                    }
                    if (canceledIds.remove(itemId)) {
                        partFile.delete()
                        updateItem(appCtx, itemId, persist) {
                            it.copy(status = DownloadStatus.CANCELED, speedBytesPerSec = 0L)
                        }
                        return@use
                    }

                    if (totalBytes > 0L && downloaded < totalBytes) {
                        throw IOException("Transfer ended early ($downloaded / $totalBytes bytes)")
                    }

                    if (finalFile.exists()) finalFile.delete()
                    if (!partFile.renameTo(finalFile)) {
                        partFile.copyTo(finalFile, overwrite = true)
                        partFile.delete()
                    }

                    updateItem(appCtx, itemId, persist) {
                        it.copy(
                            status = DownloadStatus.COMPLETED,
                            downloadedBytes = finalFile.length(),
                            totalBytes = finalFile.length(),
                            speedBytesPerSec = 0L,
                            errorMessage = "",
                            completedAt = System.currentTimeMillis()
                        )
                    }
                }
            } catch (e: Exception) {
                if (pausedIds.remove(itemId)) {
                    val bytes = if (partFile.exists()) partFile.length() else initial.downloadedBytes
                    updateItem(appCtx, itemId, persist) {
                        it.copy(status = DownloadStatus.PAUSED, downloadedBytes = bytes, speedBytesPerSec = 0L)
                    }
                } else if (canceledIds.remove(itemId)) {
                    partFile.delete()
                    updateItem(appCtx, itemId, persist) {
                        it.copy(status = DownloadStatus.CANCELED, speedBytesPerSec = 0L)
                    }
                } else {
                    val msg = e.message?.takeIf { it.isNotBlank() } ?: "Download failed"
                    updateItem(appCtx, itemId, persist) {
                        it.copy(
                            status = DownloadStatus.FAILED,
                            downloadedBytes = if (partFile.exists()) partFile.length() else it.downloadedBytes,
                            speedBytesPerSec = 0L,
                            errorMessage = msg
                        )
                    }
                }
            } finally {
                activeCalls.remove(itemId)
                activeJobs.remove(itemId)
            }
        }
        activeJobs[itemId] = job
    }

    private fun partFileFor(item: DownloadItem): File = File("${item.localPath}.part")

    private fun hasEnoughSpace(dir: File, neededBytes: Long): Boolean {
        return try {
            val stat = StatFs(dir.absolutePath)
            stat.availableBytes > neededBytes + (1024L * 1024L)
        } catch (_: Exception) {
            true
        }
    }

    private fun uniqueTargetFile(dir: File, fileName: String): File {
        var candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var counter = 1
        while (candidate.exists() && counter < 999) {
            candidate = File(dir, "$stem ($counter)$ext")
            counter++
        }
        return candidate
    }

    fun resolveMimeType(fileNameOrUrl: String, hintedMime: String?, contentDisposition: String?): String {
        val cleanHint = hintedMime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (cleanHint.isNotBlank() && cleanHint != "application/octet-stream" && cleanHint != "binary/octet-stream") {
            return cleanHint
        }
        val cdName = contentDisposition?.let {
            Regex("filename\\*?=(?:UTF-8''|\"?)([^\";]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.getOrNull(1)
        }
        val ext = (cdName ?: fileNameOrUrl)
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
        return when (ext) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js", "mjs" -> "application/javascript"
            "json" -> "application/json"
            "xml", "svg" -> if (ext == "svg") "image/svg+xml" else "application/xml"
            "txt", "log", "md", "csv" -> "text/plain"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    private fun insertOrUpdateItem(appCtx: Context, item: DownloadItem) {
        synchronized(this) {
            val next = listOf(item) + _items.value.filterNot { it.id == item.id }
            _items.value = next
            Store(appCtx).saveDownloadItemsV3(next)
        }
    }

    private fun updateItem(appCtx: Context, id: String, persist: Boolean, transform: (DownloadItem) -> DownloadItem) {
        synchronized(this) {
            var changed = false
            val next = _items.value.map {
                if (it.id == id) {
                    changed = true
                    transform(it)
                } else {
                    it
                }
            }
            if (changed) {
                _items.value = next
                if (persist) {
                    Store(appCtx).saveDownloadItemsV3(next)
                }
            }
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "Unknown size"
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }
}
