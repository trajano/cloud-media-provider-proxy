package net.trajano.cloudmediaproviderproxy.provider

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Point
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.CloudMediaProviderContract
import android.util.Base64
import android.util.Log
import net.trajano.cloudmediaproviderproxy.config.SafRootPreferences
import net.trajano.cloudmediaproviderproxy.ui.SetupActivity
import java.io.FileOutputStream
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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

    fun queryMedia(signal: CancellationSignal? = null): SafMediaSnapshot = snapshot(configuredRootUri(), signal)

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
        size: Point = Point(512, 512),
        extras: Bundle? = null,
        mimeType: String? = null,
        allowOriginalFallback: Boolean = true,
    ): AssetFileDescriptor {
        signal?.throwIfCanceled()
        val documentUri = resolveMediaUri(mediaId)
        if (mimeType?.startsWith("video/") == true &&
            extras?.getBoolean(CloudMediaProviderContract.EXTRA_PREVIEW_THUMBNAIL, false) != true
        ) {
            return openOriginalPreview(documentUri, signal)
        }
        val edge = previewEdge(size.x, size.y)
        val previewKey = "$mediaId:thumbnail:$edge"
        val exactPreviewFile = PREVIEW_EDGES.asSequence().filter { it >= edge }.mapNotNull { cachedEdge ->
            previewFileStore.cachedFileFor("$mediaId:thumbnail:$cachedEdge", syncGeneration)
        }.firstOrNull()
        if (exactPreviewFile != null) return openCachedPreview(exactPreviewFile)
        val stalePreviewFile = previewFileStore.latestCachedFileFor(previewKey)
        if (stalePreviewFile != null) {
            // Open before refreshing so replacement cannot remove the file before we acquire its descriptor.
            val descriptor = openCachedPreview(stalePreviewFile)
            refreshPreviewAsync(previewKey, syncGeneration, documentUri, edge)
            return descriptor
        }
        val resolvedPreviewFile = try {
            previewFileStore.fileFor(
                previewKey,
                syncGeneration,
                checkCanceled = { signal?.throwIfCanceled() },
            ) { targetFile ->
                copyPreviewToFile(documentUri, signal, targetFile, edge)
            }
        } catch (error: FileNotFoundException) {
            if (!allowOriginalFallback || mimeType?.startsWith("video/") == true) throw error
            // Let the consumer read the original directly instead of waiting for a full cache copy.
            return openOriginalPreview(documentUri, signal)
        }

        return openCachedPreview(resolvedPreviewFile)
    }

    /** Acquire a descriptor before a refresh is allowed to replace a cached generation. */
    private fun openCachedPreview(file: java.io.File): AssetFileDescriptor {
        val parcelFileDescriptor = ParcelFileDescriptor.open(
            file,
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
        edge: Int,
    ) {
        val refreshKey = "$mediaId:$syncGeneration"
        val signal = SafMediaCacheStore.backgroundPreviewSignal()
        if (ACTIVE_PREVIEW_REFRESHES.putIfAbsent(refreshKey, true) != null) {
            return
        }

        try {
            previewRefreshExecutor.execute {
                try {
                    previewFileStore.fileFor(
                        mediaId, syncGeneration, checkCanceled = signal::throwIfCanceled,
                    ) { targetFile ->
                        copyPreviewToFile(documentUri, signal, targetFile, edge)
                    }
                } catch (_: android.os.OperationCanceledException) {
                    // Folder changes invalidate pending background work.
                } catch (error: Exception) {
                    Log.w(TAG, "Failed to refresh cached thumbnail", error)
                } finally {
                    ACTIVE_PREVIEW_REFRESHES.remove(refreshKey)
                }
            }
        } catch (_: RejectedExecutionException) {
            ACTIVE_PREVIEW_REFRESHES.remove(refreshKey)
        }
    }

    private fun copyPreviewToFile(
        documentUri: Uri,
        signal: CancellationSignal?,
        targetFile: java.io.File,
        edge: Int,
    ) {
        loadPreviewAssetFileDescriptor(documentUri, signal, edge).use { descriptor ->
            descriptor.createInputStream().use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    copyWithCancellation(inputStream, outputStream) { signal?.throwIfCanceled() }
                }
            }
        }
    }

    private fun loadPreviewAssetFileDescriptor(
        documentUri: Uri,
        signal: CancellationSignal?,
        edge: Int,
    ): AssetFileDescriptor {
        val options = Bundle().apply {
            putParcelable(ContentResolver.EXTRA_SIZE, Point(edge, edge))
        }

        return context.contentResolver.openTypedAssetFileDescriptor(
            documentUri,
            "image/*",
            options,
            signal,
        ) ?: throw FileNotFoundException("Unable to open typed preview for $documentUri")
    }

    /** Return full media without first duplicating a remote download into our cache. */
    private fun openOriginalPreview(documentUri: Uri, signal: CancellationSignal?): AssetFileDescriptor {
        signal?.throwIfCanceled()
        return context.contentResolver.openAssetFileDescriptor(documentUri, "r", signal)
            ?: throw FileNotFoundException("Unable to open preview for $documentUri")
    }

    companion object {
        private const val TAG = "SafMediaCatalog"
        private const val DIRECTORY_MIME_TYPE = DocumentsContract.Document.MIME_TYPE_DIR
        private const val MAX_INDEXED_IMAGE_SIZE_BYTES = 10L * 1024L * 1024L
        private val PREVIEW_REFRESH_EXECUTOR: ExecutorService = ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(16),
            ThreadPoolExecutor.AbortPolicy(),
        )
        private val ACTIVE_PREVIEW_REFRESHES = ConcurrentHashMap<String, Boolean>()
        private val PREVIEW_EDGES = listOf(256, 512, 1024, 2048)

        /** Reuse a small set of thumbnail sizes without undersizing normal picker requests. */
        internal fun previewEdge(width: Int, height: Int): Int {
            val requested = maxOf(width, height, 1)
            return PREVIEW_EDGES.firstOrNull { it >= requested } ?: 2048
        }

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

        internal fun shouldIndexMedia(
            mimeType: String,
            sizeBytes: Long?,
        ): Boolean = when {
            mimeType.startsWith("video/") -> true
            mimeType.startsWith("image/") -> sizeBytes == null || sizeBytes <= MAX_INDEXED_IMAGE_SIZE_BYTES
            else -> false
        }
    }

    private fun snapshot(rootUri: Uri?, signal: CancellationSignal?): SafMediaSnapshot {
        if (rootUri == null) {
            return SafMediaSnapshot(emptyList(), 0L)
        }

        val rootDocumentUri = resolveRootDocumentUri(rootUri) ?: return SafMediaSnapshot(emptyList(), 0L)
        val mediaItems = mutableListOf<SafMediaItem>()
        walkDocumentTree(rootUri = rootUri, documentUri = rootDocumentUri, mediaItems = mediaItems, signal = signal)
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
        signal: CancellationSignal?,
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

        signal?.throwIfCanceled()
        context.contentResolver.query(childrenUri, projection, null, null, null, signal)?.use { cursor ->
            while (cursor.moveToNext()) {
                signal?.throwIfCanceled()
                val documentId = cursor.getString(0) ?: continue
                val displayName = cursor.getString(1)
                val lastModified = cursor.getLongOrNull(2)
                val mimeType = cursor.getString(3) ?: continue
                val sizeBytes = cursor.getLongOrNull(4)
                val childDocumentUri =
                    DocumentsContract.buildDocumentUriUsingTree(rootUri, documentId)

                when {
                    mimeType == DIRECTORY_MIME_TYPE -> walkDocumentTree(rootUri, childDocumentUri, mediaItems, signal)
                    shouldIndexMedia(mimeType, sizeBytes) -> {
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
) {
    /** Build the lookup once so opening a thumbnail does not scan the library. */
    val mediaById: Map<String, SafMediaItem> by lazy { mediaItems.associateBy(SafMediaItem::mediaId) }
}

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
