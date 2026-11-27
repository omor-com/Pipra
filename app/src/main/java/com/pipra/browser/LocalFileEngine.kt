package com.pipra.browser

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

enum class LocalFileKind(val label: String) {
    HTML("HTML Webpage"),
    MHT("Saved Web Archive"),
    TXT("Text Document"),
    JSON("JSON Data"),
    XML("XML Document"),
    CSS("CSS Stylesheet"),
    JS("JavaScript Source"),
    PDF("PDF Document"),
    IMAGE("Image"),
    ZIP("ZIP Archive"),
    APK("Android Package (APK)"),
    UNSUPPORTED("File")
}

data class LocalFileMetadata(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val kind: LocalFileKind,
    val directLocalFile: File? = null
)

data class BoundedTextResult(
    val text: String,
    val truncated: Boolean,
    val totalBytesRead: Long
)

data class ZipEntryInfo(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val compressedBytes: Long
)

data class ZipExtractResult(
    val outputDir: File,
    val extractedCount: Int,
    val entryHtmlFile: File?
)

object LocalFileClassifier {

    fun inspectUri(context: Context, uri: Uri, hintedName: String? = null, hintedMime: String? = null): LocalFileMetadata {
        val resolver = context.contentResolver
        var displayName = hintedName.orEmpty()
        var sizeBytes = -1L

        if (uri.scheme == ContentResolver.SCHEME_FILE && !uri.path.isNullOrBlank()) {
            val f = File(uri.path!!)
            if (displayName.isBlank()) displayName = f.name
            if (f.exists()) sizeBytes = f.length()
        } else {
            try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c: Cursor ->
                    if (c.moveToFirst()) {
                        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                        if (displayName.isBlank() && nameIdx >= 0) {
                            displayName = c.getString(nameIdx).orEmpty()
                        }
                        if (sizeIdx >= 0 && !c.isNull(sizeIdx)) {
                            sizeBytes = c.getLong(sizeIdx)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        if (displayName.isBlank()) {
            displayName = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "document" } ?: "document"
        }

        val mime = resolver.getType(uri)?.takeIf { it.isNotBlank() }
            ?: hintedMime?.takeIf { it.isNotBlank() }
            ?: PipraDownloadManager.resolveMimeType(displayName, null, null)

        val headSample = try {
            resolver.openInputStream(uri)?.use { stream ->
                val buf = ByteArray(512)
                val read = stream.read(buf)
                if (read > 0) buf.copyOf(read) else ByteArray(0)
            } ?: ByteArray(0)
        } catch (_: Exception) {
            ByteArray(0)
        }

        val kind = classify(displayName, mime, headSample)
        val localFile = resolveIfOwnedLocalFile(context, uri)
        return LocalFileMetadata(
            uri = uri,
            displayName = DomainUtils.sanitizeDownloadFileName(displayName),
            mimeType = mime,
            sizeBytes = sizeBytes,
            kind = kind,
            directLocalFile = localFile
        )
    }

    fun classify(fileName: String, mimeType: String, headerBytes: ByteArray = ByteArray(0)): LocalFileKind {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mime = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)

        // Extension and MIME checks first
        when (ext) {
            "html", "htm", "xhtml" -> return LocalFileKind.HTML
            "mht", "mhtml" -> return LocalFileKind.MHT
            "txt", "log", "md", "csv", "ini", "conf" -> return LocalFileKind.TXT
            "json" -> return LocalFileKind.JSON
            "xml" -> return LocalFileKind.XML
            "css" -> return LocalFileKind.CSS
            "js", "mjs" -> return LocalFileKind.JS
            "pdf" -> return LocalFileKind.PDF
            "png", "jpg", "jpeg", "webp", "gif", "bmp", "ico", "svg" -> return LocalFileKind.IMAGE
            "zip" -> return LocalFileKind.ZIP
            "apk" -> return LocalFileKind.APK
        }

        when {
            mime == "text/html" || mime == "application/xhtml+xml" -> return LocalFileKind.HTML
            mime == "multipart/related" || mime == "message/rfc822" -> return LocalFileKind.MHT
            mime == "application/json" || mime == "text/json" -> return LocalFileKind.JSON
            mime == "text/xml" || mime == "application/xml" -> return LocalFileKind.XML
            mime == "text/css" -> return LocalFileKind.CSS
            mime == "application/javascript" || mime == "text/javascript" -> return LocalFileKind.JS
            mime == "application/pdf" -> return LocalFileKind.PDF
            mime.startsWith("image/") -> return LocalFileKind.IMAGE
            mime == "application/zip" || mime == "application/x-zip-compressed" -> return LocalFileKind.ZIP
            mime == "application/vnd.android.package-archive" -> return LocalFileKind.APK
            mime.startsWith("text/") -> return LocalFileKind.TXT
        }

        // Fallback content sniffing when extension/MIME is generic (e.g. application/octet-stream)
        if (headerBytes.isNotEmpty()) {
            if (headerBytes.size >= 4 && headerBytes[0] == '%'.code.toByte() && headerBytes[1] == 'P'.code.toByte() &&
                headerBytes[2] == 'D'.code.toByte() && headerBytes[3] == 'F'.code.toByte()
            ) {
                return LocalFileKind.PDF
            }
            if (headerBytes.size >= 4 && headerBytes[0] == 0x50.toByte() && headerBytes[1] == 0x4B.toByte() &&
                headerBytes[2] == 0x03.toByte() && headerBytes[3] == 0x04.toByte()
            ) {
                return LocalFileKind.ZIP
            }
            val textHead = headerBytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n').lowercase(Locale.ROOT)
            when {
                textHead.startsWith("<!doctype html") || textHead.startsWith("<html") || textHead.startsWith("<head") -> return LocalFileKind.HTML
                textHead.startsWith("<?xml") || textHead.startsWith("<svg") -> return if (textHead.startsWith("<svg")) LocalFileKind.IMAGE else LocalFileKind.XML
                textHead.startsWith("{") || textHead.startsWith("[") -> return LocalFileKind.JSON
            }
        }

        return LocalFileKind.UNSUPPORTED
    }

    fun canOpenInPipraViewer(fileName: String, mimeType: String): Boolean =
        classify(fileName, mimeType) != LocalFileKind.UNSUPPORTED

    private fun resolveIfOwnedLocalFile(context: Context, uri: Uri): File? {
        if (uri.scheme == ContentResolver.SCHEME_FILE && !uri.path.isNullOrBlank()) {
            val f = File(uri.path!!)
            return if (f.exists()) f else null
        }
        // Check if this content URI is served by Pipra's own FileProvider
        val expectedAuth = "${context.packageName}.fileprovider"
        if (uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority == expectedAuth) {
            val segments = uri.pathSegments
            if (segments.size >= 2) {
                val root = segments[0]
                val rel = segments.drop(1).joinToString(File.separator)
                val baseDir = when (root) {
                    "downloads" -> PipraDownloadManager.downloadsDir(context)
                    "external_files" -> context.getExternalFilesDir(null)
                    "saved_pages" -> File(context.filesDir, "saved_pages")
                    "shared_cache" -> context.cacheDir
                    else -> null
                }
                if (baseDir != null) {
                    val candidate = File(baseDir, rel)
                    if (candidate.canonicalPath.startsWith(baseDir.canonicalPath) && candidate.exists()) {
                        return candidate
                    }
                }
            }
        }
        return null
    }
}

object SafeTextReader {
    private const val MAX_PREVIEW_BYTES = 2 * 1024 * 1024 // 2 MB cap keeps UI responsive

    fun readBounded(resolver: ContentResolver, uri: Uri): BoundedTextResult {
        val out = java.io.ByteArrayOutputStream()
        var truncated = false
        resolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                val remaining = MAX_PREVIEW_BYTES - total
                if (remaining <= 0) {
                    truncated = true
                    break
                }
                val toWrite = n.coerceAtMost(remaining)
                out.write(buf, 0, toWrite)
                total += toWrite
                if (toWrite < n) {
                    truncated = true
                    break
                }
            }
        } ?: throw IOException("Unable to open file stream")

        val text = out.toByteArray().toString(Charsets.UTF_8)
        return BoundedTextResult(text = text, truncated = truncated, totalBytesRead = out.size().toLong())
    }

    fun formatJsonSafely(raw: String): Pair<String, String?> {
        val trimmed = raw.trim()
        return try {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed).toString(2) to null
                trimmed.startsWith("[") -> JSONArray(trimmed).toString(2) to null
                else -> raw to "File does not begin with a JSON object or array; showing raw text."
            }
        } catch (e: Exception) {
            raw to "Invalid JSON syntax (${e.message ?: "parse error"}); showing raw text."
        }
    }
}

object SafeZipHandler {
    private const val MAX_LIST_ENTRIES = 2500
    private const val MAX_EXTRACT_ENTRIES = 2000
    private const val MAX_EXTRACT_BYTES = 150L * 1024L * 1024L // 150 MB decompression cap

    fun listEntries(resolver: ContentResolver, uri: Uri): List<ZipEntryInfo> {
        val out = ArrayList<ZipEntryInfo>()
        resolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null && out.size < MAX_LIST_ENTRIES) {
                    out.add(
                        ZipEntryInfo(
                            name = entry.name,
                            isDirectory = entry.isDirectory,
                            sizeBytes = entry.size,
                            compressedBytes = entry.compressedSize
                        )
                    )
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } ?: throw IOException("Cannot read ZIP archive")
        return out
    }

    fun safeExtract(context: Context, uri: Uri, archiveDisplayName: String): ZipExtractResult {
        val baseDownloads = PipraDownloadManager.downloadsDir(context)
        val extractedParent = File(baseDownloads, "Extracted").apply { mkdirs() }
        val cleanStem = DomainUtils.sanitizeDownloadFileName(archiveDisplayName.substringBeforeLast('.'))
            .ifBlank { "archive" }
        var destDir = File(extractedParent, cleanStem)
        var suffix = 1
        while (destDir.exists()) {
            destDir = File(extractedParent, "${cleanStem}_$suffix")
            suffix++
        }
        if (!destDir.mkdirs()) throw IOException("Could not create extraction folder")

        val destCanonical = destDir.canonicalPath + File.separator
        var entryCount = 0
        var totalBytes = 0L
        var firstHtml: File? = null
        var indexHtml: File? = null
        val buf = ByteArray(16 * 1024)

        try {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        entryCount++
                        if (entryCount > MAX_EXTRACT_ENTRIES) {
                            throw SecurityException("Archive exceeds safe entry limit ($MAX_EXTRACT_ENTRIES files)")
                        }
                        val outFile = File(destDir, entry.name)
                        val outCanonical = outFile.canonicalPath
                        if (!outCanonical.startsWith(destCanonical) && outCanonical != destDir.canonicalPath) {
                            throw SecurityException("Blocked unsafe path traversal entry: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                while (true) {
                                    val n = zis.read(buf)
                                    if (n == -1) break
                                    totalBytes += n
                                    if (totalBytes > MAX_EXTRACT_BYTES) {
                                        throw SecurityException("Archive exceeds safe uncompressed limit (150 MB)")
                                    }
                                    fos.write(buf, 0, n)
                                }
                            }
                            val lowerName = outFile.name.lowercase(Locale.ROOT)
                            if (lowerName == "index.html" || lowerName == "index.htm") {
                                if (indexHtml == null || outFile.parentFile == destDir) {
                                    indexHtml = outFile
                                }
                            } else if ((lowerName.endsWith(".html") || lowerName.endsWith(".htm")) && firstHtml == null) {
                                firstHtml = outFile
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } ?: throw IOException("Unable to open archive stream")
        } catch (e: Exception) {
            destDir.deleteRecursively()
            throw e
        }

        return ZipExtractResult(
            outputDir = destDir,
            extractedCount = entryCount,
            entryHtmlFile = indexHtml ?: firstHtml
        )
    }
}

/**
 * Resolves relative paths (CSS, JS, images, fonts, sub-pages) safely for local HTML rendering:
 * 1. If a local root File directory is available (e.g., extracted ZIP or downloads folder), strictly confines access inside it.
 * 2. If a user-picked SAF DocumentTree URI is linked, traverses and serves matching relative entries from ContentResolver.
 */
class LocalWebProjectResolver(
    private val context: Context,
    private val entryUri: Uri,
    private val rootDir: File?,
    private val entryRelativePath: String,
    private val treeUri: Uri? = null
) {
    private val treeIndex: Map<String, Uri> by lazy { buildTreeIndex() }

    companion object {
        const val VIRTUAL_HOST = "local.pipra.invalid"
        const val VIRTUAL_ORIGIN = "https://$VIRTUAL_HOST"

        fun normalizeVirtualPath(rawPath: String): String? {
            val clean = rawPath.substringBefore('?').substringBefore('#').trimStart('/')
            val parts = ArrayList<String>()
            for (seg in clean.split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> {
                        if (parts.isEmpty()) return null
                        parts.removeAt(parts.size - 1)
                    }
                    else -> parts.add(seg)
                }
            }
            return parts.joinToString("/")
        }
    }

    fun initialVirtualUrl(): String = "$VIRTUAL_ORIGIN/${entryRelativePath.trimStart('/')}"

    fun openResource(requestPath: String): Pair<String, InputStream>? {
        val normRel = normalizeVirtualPath(requestPath) ?: return null
        if (normRel == entryRelativePath.trimStart('/')) {
            val stream = context.contentResolver.openInputStream(entryUri) ?: return null
            val mime = PipraDownloadManager.resolveMimeType(normRel, null, null)
            return mime to stream
        }

        // Check linked SAF project folder first if present
        if (treeUri != null) {
            val matchedUri = treeIndex[normRel.lowercase(Locale.ROOT)]
                ?: treeIndex["${entryRelativePath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }}$normRel".lowercase(Locale.ROOT)]
            if (matchedUri != null) {
                val stream = context.contentResolver.openInputStream(matchedUri)
                if (stream != null) {
                    val mime = PipraDownloadManager.resolveMimeType(normRel, context.contentResolver.getType(matchedUri), null)
                    return mime to stream
                }
            }
        }

        // Check confined local project directory (extracted ZIP or downloads subfolder)
        if (rootDir != null && rootDir.exists()) {
            val rootCanonical = rootDir.canonicalPath
            val target = File(rootDir, normRel)
            val targetCanonical = target.canonicalPath
            if ((targetCanonical == rootCanonical || targetCanonical.startsWith(rootCanonical + File.separator)) &&
                target.exists() && target.isFile
            ) {
                val mime = PipraDownloadManager.resolveMimeType(target.name, null, null)
                return mime to target.inputStream()
            }
        }

        return null
    }

    fun hasFolderContext(): Boolean = rootDir != null || treeUri != null

    private fun buildTreeIndex(): Map<String, Uri> {
        val rootTree = treeUri ?: return emptyMap()
        val result = HashMap<String, Uri>()
        val rootDocId = try {
            DocumentsContract.getTreeDocumentId(rootTree)
        } catch (_: Exception) {
            return emptyMap()
        }
        traverseTree(rootTree, rootDocId, "", result, depth = 0)
        return result
    }

    private fun traverseTree(
        treeUri: Uri,
        parentDocId: String,
        prefix: String,
        out: MutableMap<String, Uri>,
        depth: Int
    ) {
        if (depth > 8 || out.size >= 1500) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (c.moveToNext() && out.size < 1500) {
                    val docId = if (idIdx >= 0) c.getString(idIdx) else continue
                    val name = if (nameIdx >= 0) c.getString(nameIdx) else continue
                    val mime = if (mimeIdx >= 0) c.getString(mimeIdx) else ""
                    val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        traverseTree(treeUri, docId, rel, out, depth + 1)
                    } else {
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        out[rel.lowercase(Locale.ROOT)] = fileUri
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
