package net.trajano.cloudmediaproviderproxy.provider

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Base64
import android.util.Log
import net.trajano.cloudmediaproviderproxy.config.SafRootPreferences
import net.trajano.cloudmediaproviderproxy.ui.SetupActivity
import java.io.FileOutputStream
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.core.net.toUri

internal class SafMediaCatalog(
    private val context: Context,
    private val rootPreferences: SafRootPreferences = SafRootPreferences(context),
    private val previewFileStore: PreviewFileStore = PreviewFileStore(
        SafMediaCacheStore.previewDirectory(context),
    ),
    private val previewRefreshExecutor: ExecutorService = PREVIEW_REFRESH_EXECUTOR,
) {

    fun buildCollectionInfo(
        snapshot: SafMediaSnapshot = queryMedia(),
        lastSyncGeneration: Long = snapshot.lastSyncGeneration,
    ): Bundle {
        val rootUri = configuredRootUri()

        return Bundle().apply {
            putString(KEY_MEDIA_COLLECTION_ID, mediaCollectionId(rootUri))
            putLong(KEY_LAST_MEDIA_SYNC_GENERATION, lastSyncGeneration)
            putString(
                KEY_ACCOUNT_NAME,
                accountNameForDisplay(
                    authority = rootUri?.authority,
                    providerLabel = rootUri?.authority?.let(::resolveProviderLabel),
                ),
            )
            putParcelable(
                KEY_ACCOUNT_CONFIGURATION_INTENT,
                Intent(context, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun queryMedia(): SafMediaSnapshot = snapshot(configuredRootUri())

    fun openMedia(mediaId: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val documentUri = resolveMediaUri(mediaId)
        Log.i(TAG, "Opening media mediaId=$mediaId documentUri=$documentUri")
        return context.contentResolver.openFileDescriptor(documentUri, "r", signal)
            ?: throw FileNotFoundException("Unable to open $documentUri")
    }

    fun openPreview(
        mediaId: String,
        syncGeneration: Long,
        signal: CancellationSignal?,
    ): AssetFileDescriptor {
        val documentUri = resolveMediaUri(mediaId)
        val previewFile = previewFileStore.cachedFileFor(mediaId, syncGeneration)
            ?: previewFileStore.latestCachedFileFor(mediaId)?.also {
                refreshPreviewAsync(mediaId, syncGeneration, documentUri)
            }
            ?: previewFileStore.fileFor(mediaId, syncGeneration) { targetFile ->
                copyPreviewToFile(documentUri, signal, targetFile)
            }

        val parcelFileDescriptor = ParcelFileDescriptor.open(
            previewFile,
            ParcelFileDescriptor.MODE_READ_ONLY,
        )
        return AssetFileDescriptor(parcelFileDescriptor, 0L, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    fun configuredRootUri(): Uri? {
        val rootUri = rootPreferences.getRootUri() ?: return null
        if (!rootPreferences.hasPersistedReadPermission(context.contentResolver)) {
            return null
        }
        return rootUri
    }

    private fun resolveProviderLabel(authority: String): String? {
        val providerInfo = context.packageManager.resolveContentProvider(authority, 0) ?: return null
        return providerInfo.loadLabel(context.packageManager)?.toString()?.trim()
    }

    private fun refreshPreviewAsync(
        mediaId: String,
        syncGeneration: Long,
        documentUri: Uri,
    ) {
        val refreshKey = "$mediaId:$syncGeneration"
        if (ACTIVE_PREVIEW_REFRESHES.putIfAbsent(refreshKey, true) != null) {
            return
        }

        previewRefreshExecutor.execute {
            try {
                previewFileStore.fileFor(mediaId, syncGeneration) { targetFile ->
                    copyPreviewToFile(documentUri, signal = null, targetFile = targetFile)
                }
                Log.i(TAG, "Refreshed preview cache for mediaId=$mediaId generation=$syncGeneration")
            } catch (error: Exception) {
                Log.w(TAG, "Failed to refresh preview cache for mediaId=$mediaId generation=$syncGeneration", error)
            } finally {
                ACTIVE_PREVIEW_REFRESHES.remove(refreshKey)
            }
        }
    }

    private fun copyPreviewToFile(
        documentUri: Uri,
        signal: CancellationSignal?,
        targetFile: java.io.File,
    ) {
        loadPreviewAssetFileDescriptor(documentUri, signal).use { descriptor ->
            descriptor.createInputStream().use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
    }

    private fun loadPreviewAssetFileDescriptor(
        documentUri: Uri,
        signal: CancellationSignal?,
    ): AssetFileDescriptor {
        val options = Bundle().apply {
            putParcelable(ContentResolver.EXTRA_SIZE, android.graphics.Point(512, 512))
        }

        try {
            return context.contentResolver.openTypedAssetFileDescriptor(
                documentUri,
                "image/*",
                options,
                signal,
            ) ?: throw FileNotFoundException("Unable to open typed preview for $documentUri")
        } catch (e: OperationCanceledException) {
            throw e
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "Typed preview load failed for $documentUri; falling back to direct asset", e)
        }
        signal?.throwIfCanceled()

        return context.contentResolver.openAssetFileDescriptor(documentUri, "r", signal)
            ?: throw FileNotFoundException("Unable to open preview for $documentUri")
    }

    companion object {
        private const val TAG = "SafMediaCatalog"
        private const val DIRECTORY_MIME_TYPE = DocumentsContract.Document.MIME_TYPE_DIR
        private val PREVIEW_REFRESH_EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor()
        private val ACTIVE_PREVIEW_REFRESHES = ConcurrentHashMap<String, Boolean>()

        private const val KEY_MEDIA_COLLECTION_ID =
            android.provider.CloudMediaProviderContract.MediaCollectionInfo.MEDIA_COLLECTION_ID
        private const val KEY_LAST_MEDIA_SYNC_GENERATION =
            android.provider.CloudMediaProviderContract.MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION
        private const val KEY_ACCOUNT_NAME =
            android.provider.CloudMediaProviderContract.MediaCollectionInfo.ACCOUNT_NAME
        private const val KEY_ACCOUNT_CONFIGURATION_INTENT =
            android.provider.CloudMediaProviderContract.MediaCollectionInfo.ACCOUNT_CONFIGURATION_INTENT

        fun mediaCollectionId(rootUri: Uri?): String =
            rootUri?.toString()?.let { "tree:${it.hashCode()}" } ?: "unconfigured"

        fun mediaId(documentUri: Uri): String =
            Base64.encodeToString(
                documentUri.toString().toByteArray(StandardCharsets.UTF_8),
                Base64.NO_WRAP or Base64.URL_SAFE,
            )

        fun decodeMediaId(mediaId: String): Uri =
            String(
                Base64.decode(mediaId, Base64.NO_WRAP or Base64.URL_SAFE),
                StandardCharsets.UTF_8,
            ).toUri()
    }

    private fun snapshot(rootUri: Uri?): SafMediaSnapshot {
        if (rootUri == null) {
            return SafMediaSnapshot(emptyList(), 0L)
        }

        val rootDocumentUri = resolveRootDocumentUri(rootUri) ?: return SafMediaSnapshot(emptyList(), 0L)
        val mediaItems = mutableListOf<SafMediaItem>()
        walkDocumentTree(rootUri = rootUri, documentUri = rootDocumentUri, mediaItems = mediaItems)
        val sortedItems = mediaItems.sortedByDescending { it.dateTakenMillis ?: 0L }
        val lastSyncGeneration = sortedItems.maxOfOrNull(SafMediaItem::syncGeneration) ?: 0L

        Log.i(TAG, "Indexed ${sortedItems.size} SAF media items from ${rootUri.authority}")
        return SafMediaSnapshot(sortedItems, lastSyncGeneration)
    }

    private fun resolveMediaUri(mediaId: String): Uri {
        val documentUri = decodeMediaId(mediaId)
        val configuredRoot = configuredRootUri()
            ?: throw FileNotFoundException("SAF root is not configured")
        val configuredTreeId = DocumentsContract.getTreeDocumentId(configuredRoot)
        val documentTreeId = DocumentsContract.getTreeDocumentId(documentUri)

        if (configuredTreeId != documentTreeId) {
            throw FileNotFoundException("Media id does not belong to the configured SAF tree")
        }

        return documentUri
    }

    private fun walkDocumentTree(
        rootUri: Uri,
        documentUri: Uri,
        mediaItems: MutableList<SafMediaItem>,
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            rootUri,
            DocumentsContract.getDocumentId(documentUri),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )

        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(0) ?: continue
                val displayName = cursor.getString(1)
                val lastModified = cursor.getLongOrNull(2)
                val mimeType = cursor.getString(3) ?: continue
                val sizeBytes = cursor.getLongOrNull(4)
                val childDocumentUri =
                    DocumentsContract.buildDocumentUriUsingTree(rootUri, documentId)

                when {
                    mimeType == DIRECTORY_MIME_TYPE -> walkDocumentTree(rootUri, childDocumentUri, mediaItems)
                    mimeType.startsWith("image/") || mimeType.startsWith("video/") -> {
                        mediaItems += SafMediaItem(
                            mediaId = mediaId(childDocumentUri),
                            documentUri = childDocumentUri,
                            displayName = displayName,
                            mimeType = mimeType,
                            dateTakenMillis = lastModified,
                            syncGeneration = lastModified?.coerceAtLeast(1L) ?: 1L,
                            sizeBytes = sizeBytes,
                        )
                    }
                }
            }
        } ?: Log.w(TAG, "Unable to query SAF children for $documentUri")
    }

    private fun resolveRootDocumentUri(rootUri: Uri): Uri? =
        runCatching {
            DocumentsContract.buildDocumentUriUsingTree(
                rootUri,
                DocumentsContract.getTreeDocumentId(rootUri),
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to resolve SAF root document URI for $rootUri", error)
        }.getOrNull()

    private fun Cursor.getLongOrNull(columnIndex: Int): Long? =
        if (isNull(columnIndex)) {
            null
        } else {
            getLong(columnIndex)
        }
}

internal data class SafMediaItem(
    val mediaId: String,
    val documentUri: Uri,
    val displayName: String?,
    val mimeType: String,
    val dateTakenMillis: Long?,
    val syncGeneration: Long,
    val sizeBytes: Long?,
)

internal data class SafMediaSnapshot(
    val mediaItems: List<SafMediaItem>,
    val lastSyncGeneration: Long,
)

internal fun accountNameForDisplay(authority: String?, providerLabel: String?): String {
    val trimmedLabel = providerLabel?.trim().orEmpty()
    if (trimmedLabel.isNotEmpty()) {
        return trimmedLabel
    }

    val trimmedAuthority = authority?.trim().orEmpty()
    if (trimmedAuthority.isNotEmpty()) {
        return trimmedAuthority
    }

    return "Not configured"
}
