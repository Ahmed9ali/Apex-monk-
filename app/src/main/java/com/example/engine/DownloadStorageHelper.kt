package com.example.engine

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.example.R
import com.example.data.local.DownloadItemEntity
import com.example.data.preferences.BrowserPreferences
import com.example.data.preferences.DownloadLocationMode
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

/**
 * Resolves Play-compliant mobile download locations:
 * - Public mobile Downloads folder (`Environment.DIRECTORY_DOWNLOADS`, saved via Scoped Storage
 *   `MediaStore.Downloads` on API 29+ with zero storage permissions)
 * - App-private internal storage
 * - User-selected mobile Storage Access Framework (SAF) folders (Internal storage subfolders, SD card, etc.)
 * Also handles display summaries, device storage usage, cleanup, and FileProvider-backed open intents.
 */
object DownloadStorageHelper {

    data class StorageSpaceInfo(
        val usedBytes: Long,
        val totalBytes: Long,
        val availableBytes: Long
    ) {
        val usedFraction: Float
            get() = if (totalBytes > 0L) {
                (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
    }

    data class PublishedDownloadTarget(
        val locationPathOrUri: String,
        val resolvedFileName: String
    )

    sealed class OpenDownloadedFileResult {
        data object Opened : OpenDownloadedFileResult()
        data class Failed(val message: String) : OpenDownloadedFileResult()
    }

    private val BLOCKED_AUTO_OPEN_EXTENSIONS = setOf(
        "apk", "dex", "jar", "so", "exe", "bat", "cmd", "sh", "bin", "msi"
    )

    /**
     * Returns the mobile device's public Downloads folder (e.g. `/storage/emulated/0/Download`).
     */
    fun resolvePublicDownloadsDirectory(): File {
        @Suppress("DEPRECATION")
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }

    /**
     * Returns the user-visible path to the mobile device's Downloads folder.
     */
    fun getMobileDownloadsDisplayPath(context: Context): String {
        val publicPath = runCatching { resolvePublicDownloadsDirectory().absolutePath }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        return publicPath ?: resolveDirectory(context, DownloadLocationMode.APP_EXTERNAL).absolutePath
    }

    /**
     * Returns a best-effort initial URI pointing to the phone's `Download` folder for
     * `ActivityResultContracts.OpenDocumentTree` on API 26+.
     */
    fun getInitialDownloadsPickerUri(): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return runCatching {
            DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:${Environment.DIRECTORY_DOWNLOADS}"
            )
        }.getOrNull()
    }

    /**
     * Resolves the app-writable staging/storage directory for the given [DownloadLocationMode].
     * For [DownloadLocationMode.APP_EXTERNAL], active NIO multi-TCP segments stage here before
     * being published atomically to the mobile public `Downloads` folder on completion.
     */
    fun resolveDirectory(context: Context, mode: DownloadLocationMode): File {
        val appContext = context.applicationContext
        val dir = when (mode) {
            DownloadLocationMode.APP_INTERNAL -> File(appContext.filesDir, "downloads")
            DownloadLocationMode.APP_EXTERNAL,
            DownloadLocationMode.CUSTOM_SAF -> {
                appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: File(appContext.filesDir, "downloads")
            }
        }
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getStorageSpaceInfo(
        context: Context,
        mode: DownloadLocationMode = DownloadLocationMode.APP_EXTERNAL,
        downloads: List<DownloadItemEntity> = emptyList()
    ): StorageSpaceInfo {
        val rawDownloadsUsed = computeDownloadsUsedBytes(context, downloads)
        val publicDownloads = if (mode == DownloadLocationMode.APP_EXTERNAL || mode == DownloadLocationMode.CUSTOM_SAF) {
            runCatching { resolvePublicDownloadsDirectory() }
                .getOrNull()
                ?.takeIf { it.exists() }
                ?: runCatching { Environment.getExternalStorageDirectory() }
                    .getOrNull()
                    ?.takeIf { it.exists() }
        } else {
            null
        }
        val candidates = listOfNotNull(
            publicDownloads,
            runCatching { resolveDirectory(context, mode) }.getOrNull(),
            runCatching { context.applicationContext.filesDir }.getOrNull(),
            runCatching { Environment.getDataDirectory() }.getOrNull()
        )
        for (dir in candidates) {
            val stats = runCatching {
                val statFs = StatFs(dir.absolutePath)
                val total = statFs.totalBytes.coerceAtLeast(0L)
                val available = statFs.availableBytes.coerceAtLeast(0L)
                if (total > 0L) {
                    val clampedUsed = rawDownloadsUsed.coerceIn(0L, total)
                    StorageSpaceInfo(
                        usedBytes = clampedUsed,
                        totalBytes = total,
                        availableBytes = available
                    )
                } else {
                    null
                }
            }.getOrNull()
            if (stats != null) return stats
        }
        return StorageSpaceInfo(
            usedBytes = rawDownloadsUsed.coerceAtLeast(0L),
            totalBytes = 0L,
            availableBytes = 0L
        )
    }

    fun computeDownloadsUsedBytes(
        context: Context,
        downloads: List<DownloadItemEntity>
    ): Long {
        if (downloads.isEmpty()) return 0L
        val appContext = context.applicationContext
        val seenPaths = HashSet<String>(downloads.size)
        var totalUsed = 0L

        for (item in downloads) {
            if (item.status == FastDownloadEngine.STATUS_CANCELED) continue

            val path = item.localFilePath.trim()
            if (path.isNotEmpty() && !seenPaths.add(path)) {
                continue
            }

            val itemBytes = when {
                item.status == FastDownloadEngine.STATUS_COMPLETED -> {
                    when {
                        path.startsWith("content://", ignoreCase = true) -> {
                            queryContentUriSize(appContext, Uri.parse(path))
                                ?.takeIf { it > 0L }
                                ?: maxOf(item.totalBytes, item.downloadedBytes, 0L)
                        }
                        path.isNotEmpty() -> {
                            val file = File(path)
                            if (file.exists() && file.isFile) {
                                file.length().coerceAtLeast(0L)
                            } else {
                                maxOf(item.totalBytes, item.downloadedBytes, 0L)
                            }
                        }
                        else -> maxOf(item.totalBytes, item.downloadedBytes, 0L)
                    }
                }
                item.status == FastDownloadEngine.STATUS_DOWNLOADING ||
                    item.status == FastDownloadEngine.STATUS_PAUSED ||
                    item.status == FastDownloadEngine.STATUS_FAILED -> {
                    item.downloadedBytes.coerceAtLeast(0L)
                }
                else -> 0L
            }
            totalUsed += itemBytes
        }
        return totalUsed.coerceAtLeast(0L)
    }

    private fun queryContentUriSize(context: Context, uri: Uri): Long? {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else null
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun queryContentUriDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && !cursor.isNull(idx)) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun formatUsedOfTotalLabel(info: StorageSpaceInfo): String {
        if (info.totalBytes <= 0L) return "0 B / 0 B"
        val gb = 1024.0 * 1024.0 * 1024.0
        val mb = 1024.0 * 1024.0
        return when {
            info.usedBytes >= gb && info.totalBytes >= gb -> {
                val usedStr = formatCompactUnitValue(info.usedBytes / gb)
                val totalStr = formatCompactUnitValue(info.totalBytes / gb)
                "$usedStr / $totalStr GB"
            }
            info.usedBytes >= mb && info.totalBytes >= mb && info.totalBytes < gb -> {
                val usedStr = formatCompactUnitValue(info.usedBytes / mb)
                val totalStr = formatCompactUnitValue(info.totalBytes / mb)
                "$usedStr / $totalStr MB"
            }
            else -> "${formatCompactStorage(info.usedBytes)} / ${formatCompactStorage(info.totalBytes)}"
        }
    }

    fun formatCompactStorage(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        val tb = gb * 1024.0
        return when {
            bytes >= tb -> "${formatCompactUnitValue(bytes / tb)} TB"
            bytes >= gb -> "${formatCompactUnitValue(bytes / gb)} GB"
            bytes >= mb -> "${formatCompactUnitValue(bytes / mb)} MB"
            bytes >= kb -> "${formatCompactUnitValue(bytes / kb)} KB"
            else -> "$bytes B"
        }
    }

    private fun formatCompactUnitValue(value: Double): String {
        return when {
            value >= 100.0 -> String.format(Locale.US, "%.0f", value)
            value >= 10.0 -> String.format(Locale.US, "%.1f", value)
            else -> String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
        }
    }

    fun extractFolderLabelFromUri(uriString: String): String {
        if (uriString.isBlank()) return ""
        return runCatching {
            val uri = Uri.parse(uriString)
            val rawSegment = uri.lastPathSegment.orEmpty()
            if (rawSegment.contains(':')) {
                val volume = rawSegment.substringBefore(':').trim()
                val subPath = rawSegment.substringAfter(':').trim('/')
                when {
                    volume.equals("raw", ignoreCase = true) -> {
                        val rawFileName = subPath.substringAfterLast('/', subPath)
                        rawFileName.ifBlank { "Downloads" }
                    }
                    volume.equals("home", ignoreCase = true) -> {
                        if (subPath.isNotBlank()) "Documents/$subPath" else "Documents"
                    }
                    subPath.isNotBlank() -> subPath
                    volume.equals("primary", ignoreCase = true) -> "Internal storage"
                    volume.isNotBlank() -> "SD card ($volume)"
                    else -> ""
                }
            } else {
                val cleaned = rawSegment.trim('/')
                when {
                    cleaned.equals("downloads", ignoreCase = true) -> "Downloads"
                    cleaned.isNotBlank() -> cleaned
                    else -> uri.host.orEmpty()
                }
            }
        }.getOrDefault("")
    }

    fun formatSafDisplayPath(uriString: String): String {
        if (uriString.isBlank()) return ""
        return runCatching {
            val uri = Uri.parse(uriString)
            val rawSegment = uri.lastPathSegment.orEmpty()
            when {
                rawSegment.contains(':') -> {
                    val volume = rawSegment.substringBefore(':').trim()
                    val subPath = rawSegment.substringAfter(':').trim('/')
                    if (volume.equals("raw", ignoreCase = true)) {
                        return@runCatching if (subPath.isNotBlank()) "/$subPath" else "Device storage"
                    }
                    val volumePrefix = when {
                        volume.equals("primary", ignoreCase = true) -> "Internal storage"
                        volume.equals("home", ignoreCase = true) -> "Internal storage › Documents"
                        volume.isNotBlank() -> "SD card ($volume)"
                        else -> "Device storage"
                    }
                    if (subPath.isNotBlank()) {
                        val readableSegments = subPath.split('/').filter { it.isNotBlank() }.joinToString(" › ")
                        "$volumePrefix › $readableSegments"
                    } else {
                        volumePrefix
                    }
                }
                rawSegment.equals("downloads", ignoreCase = true) -> "Device Downloads"
                rawSegment.isNotBlank() -> rawSegment.trim('/')
                else -> uriString
            }
        }.getOrDefault(uriString)
    }

    fun formatLocationSummary(
        context: Context,
        preferences: BrowserPreferences
    ): Pair<String, String> {
        return formatLocationSummary(
            context = context,
            mode = preferences.downloadLocationMode,
            customTreeUri = preferences.customDownloadTreeUri,
            customLabel = preferences.customDownloadFolderLabel
        )
    }

    fun formatLocationSummary(
        context: Context,
        mode: DownloadLocationMode,
        customTreeUri: String,
        customLabel: String
    ): Pair<String, String> {
        return when (mode) {
            DownloadLocationMode.APP_EXTERNAL -> {
                val path = getMobileDownloadsDisplayPath(context)
                "Downloads" to path
            }
            DownloadLocationMode.APP_INTERNAL -> {
                val path = resolveDirectory(context, DownloadLocationMode.APP_INTERNAL).absolutePath
                "Private storage" to path
            }
            DownloadLocationMode.CUSTOM_SAF -> {
                val label = customLabel.ifBlank { extractFolderLabelFromUri(customTreeUri) }
                    .ifBlank { "Selected folder" }
                val readablePath = formatSafDisplayPath(customTreeUri)
                    .ifBlank { getMobileDownloadsDisplayPath(context) }
                label to readablePath
            }
        }
    }

    fun formatSavedItemLocationLabel(
        context: Context,
        localFilePath: String,
        fileName: String
    ): String {
        val trimmed = localFilePath.trim()
        return when {
            trimmed.isBlank() -> "${getMobileDownloadsDisplayPath(context)}/$fileName"
            trimmed.startsWith("content://media/", ignoreCase = true) -> {
                "${getMobileDownloadsDisplayPath(context)}/$fileName"
            }
            trimmed.startsWith("content://", ignoreCase = true) -> {
                val folderPath = formatSafDisplayPath(trimmed)
                when {
                    folderPath.startsWith("content://", ignoreCase = true) -> fileName
                    fileName.isNotBlank() &&
                        (folderPath.endsWith("› $fileName") || folderPath.endsWith("/$fileName") || folderPath == fileName) -> {
                        folderPath
                    }
                    fileName.isNotBlank() -> "$folderPath › $fileName"
                    else -> folderPath
                }
            }
            else -> trimmed
        }
    }

    /**
     * Publishes a completed staging file into the phone's public Downloads folder:
     * - On Android 10+ (API 29+): inserts into `MediaStore.Downloads.EXTERNAL_CONTENT_URI`
     *   under `Environment.DIRECTORY_DOWNLOADS` with zero broad storage permissions, then
     *   removes the temporary staging file.
     * - On pre-Q devices (or test environments without MediaStore provider) where the public
     *   Downloads directory is directly writable: copies atomically to
     *   `Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)` and
     *   scans with MediaScannerConnection.
     */
    fun publishFileToPublicDownloads(
        context: Context,
        sourceFile: File,
        mimeType: String,
        fileName: String
    ): PublishedDownloadTarget? {
        if (!sourceFile.exists() || !sourceFile.isFile) return null
        val appContext = context.applicationContext
        val resolvedMime = FastDownloadEngine.resolveMimeType(fileName, mimeType)
            .ifBlank { "application/octet-stream" }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = appContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, resolvedMime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val destUri = runCatching { resolver.insert(collection, values) }.getOrNull()
            if (destUri != null) {
                val copied = runCatching {
                    resolver.openOutputStream(destUri)?.use { output ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(output, bufferSize = 64 * 1024)
                        }
                        output.flush()
                    } ?: throw IllegalStateException("Unable to open MediaStore output stream")

                    val readyValues = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    resolver.update(destUri, readyValues, null, null)
                }
                if (copied.isSuccess) {
                    val actualName = queryContentUriDisplayName(appContext, destUri) ?: fileName
                    runCatching { sourceFile.delete() }
                    return PublishedDownloadTarget(
                        locationPathOrUri = destUri.toString(),
                        resolvedFileName = actualName
                    )
                } else {
                    runCatching { resolver.delete(destUri, null, null) }
                }
            }
        }

        // Pre-Q (API < 29) or test filesystem fallback when public Downloads directory is writable.
        val publicDir = runCatching { resolvePublicDownloadsDirectory() }.getOrNull() ?: return null
        runCatching {
            if (!publicDir.exists()) {
                publicDir.mkdirs()
            }
        }
        if (publicDir.exists() && publicDir.isDirectory && publicDir.canWrite()) {
            val destFile = FastDownloadEngine.allocateUniqueFile(publicDir, fileName)
            val copied = runCatching {
                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                        output.flush()
                    }
                }
            }
            if (copied.isSuccess && destFile.exists() && destFile.length() == sourceFile.length()) {
                runCatching { sourceFile.delete() }
                runCatching {
                    MediaScannerConnection.scanFile(
                        appContext,
                        arrayOf(destFile.absolutePath),
                        arrayOf(resolvedMime),
                        null
                    )
                }
                return PublishedDownloadTarget(
                    locationPathOrUri = destFile.absolutePath,
                    resolvedFileName = destFile.name
                )
            } else {
                runCatching { destFile.delete() }
            }
        }

        return null
    }

    fun deleteDownloadArtifact(context: Context, localFilePath: String) {
        val trimmed = localFilePath.trim()
        if (trimmed.isEmpty()) return
        val appContext = context.applicationContext
        if (trimmed.startsWith("content://", ignoreCase = true)) {
            val uri = Uri.parse(trimmed)
            val resolver = appContext.contentResolver
            val deletedViaDoc = runCatching {
                DocumentsContract.isDocumentUri(appContext, uri) &&
                    DocumentsContract.deleteDocument(resolver, uri)
            }.getOrDefault(false)
            if (!deletedViaDoc) {
                runCatching { resolver.delete(uri, null, null) }
            }
        } else {
            runCatching { File(trimmed).delete() }
        }
    }

    fun copyFileToSafTree(
        context: Context,
        sourceFile: File,
        treeUriString: String,
        mimeType: String,
        fileName: String
    ): Uri? {
        if (!sourceFile.exists() || treeUriString.isBlank()) return null
        return runCatching {
            val resolver = context.applicationContext.contentResolver
            val treeUri = Uri.parse(treeUriString)
            val parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
            val safeMime = mimeType.ifBlank { "application/octet-stream" }
            val destUri = DocumentsContract.createDocument(
                resolver,
                parentDocUri,
                safeMime,
                fileName
            ) ?: return null

            resolver.openOutputStream(destUri)?.use { output ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
                output.flush()
            } ?: return null

            destUri
        }.getOrNull()
    }

    fun isSafeToAutoOpen(fileName: String, mimeType: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        if (ext in BLOCKED_AUTO_OPEN_EXTENSIONS) {
            return false
        }
        val lowerMime = mimeType.lowercase(Locale.US)
        if (lowerMime == "application/vnd.android.package-archive") {
            return false
        }
        return true
    }

    fun resolveContentUriForDownload(
        context: Context,
        localFilePath: String,
        fileName: String
    ): Uri? {
        if (localFilePath.isBlank()) return null
        val appContext = context.applicationContext
        if (localFilePath.startsWith("content://", ignoreCase = true)) {
            return Uri.parse(localFilePath)
        }
        val directFile = File(localFilePath)
        val resolvedFile = when {
            directFile.exists() && directFile.isFile -> directFile
            fileName.isNotBlank() -> {
                listOfNotNull(
                    runCatching { File(resolvePublicDownloadsDirectory(), fileName) }.getOrNull(),
                    File(resolveDirectory(appContext, DownloadLocationMode.APP_EXTERNAL), fileName),
                    File(resolveDirectory(appContext, DownloadLocationMode.APP_INTERNAL), fileName)
                ).firstOrNull { it.exists() && it.isFile }
            }
            else -> null
        } ?: return null

        return runCatching {
            FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                resolvedFile
            )
        }.getOrNull()
    }

    fun buildOpenFileIntent(
        context: Context,
        localFilePath: String,
        fileName: String,
        mimeType: String,
        userInitiated: Boolean = false
    ): Intent? {
        if (localFilePath.isBlank()) return null
        if (!userInitiated && !isSafeToAutoOpen(fileName, mimeType)) {
            return null
        }
        val appContext = context.applicationContext
        val uri = resolveContentUriForDownload(appContext, localFilePath, fileName) ?: return null
        val resolvedMime = FastDownloadEngine.resolveMimeType(fileName, mimeType)
            .ifBlank { "*/*" }

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, resolvedMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (userInitiated) {
            return viewIntent
        }
        return if (viewIntent.resolveActivity(appContext.packageManager) != null) {
            viewIntent
        } else {
            null
        }
    }

    fun openDownloadedFile(
        context: Context,
        localFilePath: String,
        fileName: String,
        mimeType: String
    ): OpenDownloadedFileResult {
        val uri = resolveContentUriForDownload(context, localFilePath, fileName)
            ?: return OpenDownloadedFileResult.Failed(
                context.getString(R.string.downloads_open_failed_missing)
            )
        val resolvedMime = FastDownloadEngine.resolveMimeType(fileName, mimeType)
            .ifBlank { "*/*" }

        val primaryIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, resolvedMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // First try direct viewer / system handler for the specific MIME type.
        if (tryStartActivity(context, primaryIntent)) {
            return OpenDownloadedFileResult.Opened
        }

        // Second try chooser with the specific MIME type (ensuring permission grant flags propagate).
        val specificChooser = Intent.createChooser(primaryIntent, fileName).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (tryStartActivity(context, specificChooser)) {
            return OpenDownloadedFileResult.Opened
        }

        // Third try wildcard chooser so Files / archive / document viewers can handle the URI.
        val wildcardIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val wildcardChooser = Intent.createChooser(wildcardIntent, fileName).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (tryStartActivity(context, wildcardChooser)) {
            return OpenDownloadedFileResult.Opened
        }

        // Final fallback for packages where direct APK install isn't permitted from third-party apps.
        val systemDownloadsIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (tryStartActivity(context, systemDownloadsIntent)) {
            return OpenDownloadedFileResult.Opened
        }

        return OpenDownloadedFileResult.Failed(
            context.getString(R.string.downloads_open_failed_no_viewer)
        )
    }

    private fun tryStartActivity(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}
