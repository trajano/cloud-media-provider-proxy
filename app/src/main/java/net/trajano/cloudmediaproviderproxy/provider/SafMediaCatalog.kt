package net.trajano.cloudmediaproviderproxy.provider

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.graphics.Point
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.ImageDecoder
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
import java.io.IOException
import java.util.concurrent.CancellationException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import androidx.core.net.toUri
import androidx.core.database.getLongOrNull
import androidx.core.os.bundleOf

internal class SafMediaCatalog(
    private val context: Context,
    private val rootPreferences: SafRootPreferences = SafRootPreferences(context),
    private val previewFileStore: PreviewFileStore = PreviewFileStore(
        SafMediaCacheStore.previewDirectory(context),
    ),
    private val previewRefreshExecutor: ExecutorService = PREVIEW_REFRESH_EXECUTOR,
    private val originalFileStore: OriginalMediaFileStore = OriginalMediaFileStore(
        SafMediaCacheStore.originalsDirectory(context),
    ),
    private val thumbnailEncoder: PhotoPreviewEncoder = PhotoPreviewEncoder(),
) {
    private val compatiblePhotos by lazy { CompatiblePhotoStore(context) }

    fun collectionId(): String = mediaCollectionId(configuredRootUri()) +
        if (rootPreferences.compatiblePhotosEnabled()) ":${CompatiblePhotoStore.VERSION}" else ""

    private val localMediaLinks by lazy { LocalMediaLinkStore.forContext(context) }
    private val localMediaLinker by lazy {
        LocalMediaLinker(context, localMediaLinks, previewRefreshExecutor) {
            configuredRootUri()?.let { root ->
                SafMediaCacheStore.cacheFor(context, root.toString(), ::queryMedia).requestRefresh()
            }
        }
    }

    fun buildCollectionInfo(
        snapshot: SafMediaSnapshot = queryMedia(),
        lastSyncGeneration: Long = snapshot.lastSyncGeneration,
    ): Bundle {
        val rootUri = configuredRootUri()

        return Bundle().apply {
            putString(KEY_MEDIA_COLLECTION_ID, collectionId())
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

    fun queryMedia(signal: CancellationSignal? = null): SafMediaSnapshot = readySnapshot(querySourceMedia(signal))

    fun querySourceMedia(signal: CancellationSignal? = null): SafMediaSnapshot = snapshot(configuredRootUri(), signal)

    fun needsCompatiblePhoto(item: SafMediaItem): Boolean =
        rootPreferences.compatiblePhotosEnabled() && item.mimeType == "image/png" &&
            (item.sizeBytes == null || item.sizeBytes > CompatiblePhotoStore.MAX_BYTES)

    fun readySnapshot(source: SafMediaSnapshot): SafMediaSnapshot {
        val items = source.mediaItems.mapNotNull { item ->
            if (!needsCompatiblePhoto(item)) item
            else compatiblePhotos.knownSize(item)?.let { size ->
                item.copy(mediaId = item.mediaId + CompatiblePhotoStore.ID_SUFFIX,
                    displayName = item.displayName?.substringBeforeLast('.')?.plus(".jpg"),
                    mimeType = "image/jpeg", sizeBytes = size, mediaStoreUri = null)
            }
        }
        return SafMediaSnapshot(items, items.maxOfOrNull(SafMediaItem::syncGeneration) ?: 0L)
    }

    fun openMedia(
        mediaId: String,
        signal: CancellationSignal?,
        item: SafMediaItem? = null,
    ): ParcelFileDescriptor {
        val folderSignal = SafMediaCacheStore.folderCancellationSignal()
        val documentUri = resolveMediaUri(mediaId)
        signal?.throwIfCanceled()
        folderSignal.throwIfCanceled()
        if (mediaId.endsWith(CompatiblePhotoStore.ID_SUFFIX)) {
            if (!rootPreferences.compatiblePhotosEnabled() || item == null) {
                throw FileNotFoundException("Compatible photo is no longer available")
            }
            return compatiblePhotos.open(item, signal) { openRemoteMedia(documentUri, signal) }
        }
        if (item != null) {
            try {
                val cached = originalFileStore.open(
                    mediaId, item.syncGeneration, item.sizeBytes,
                    checkCanceled = {
                        signal?.throwIfCanceled()
                        folderSignal.throwIfCanceled()
                    },
                    writer = { target, checkCanceled ->
                        Log.i(TAG, "Fetching original for cache")
                        openRemoteMedia(documentUri, signal).use { descriptor ->
                            localMediaLinker.fromDescriptor(item, descriptor, folderSignal)
                            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                                FileOutputStream(target).use { output ->
                                    copyWithCancellation(input, output, originalFileStore.maxBytes,
                                        checkCanceled)
                                }
                            }
                        }
                    },
                    reader = { file -> ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) },
                )
                if (cached != null) {
                    localMediaLinker.forCachedOriginal(item, folderSignal) {
                        openRemoteMedia(documentUri, folderSignal)
                    }
                    Log.i(TAG, "Serving original from private cache")
                    return cached
                }
            } catch (_: CancellationException) {
                throw android.os.OperationCanceledException("Original cache was cleared")
            } catch (error: IOException) {
                Log.w(TAG, "Original cache unavailable; opening source directly", error)
            }
        }
        signal?.throwIfCanceled()
        folderSignal.throwIfCanceled()
        return openRemoteMedia(documentUri, signal).also { descriptor ->
            if (item != null) localMediaLinker.fromDescriptor(item, descriptor, folderSignal)
        }
    }

    private fun openRemoteMedia(documentUri: Uri, signal: CancellationSignal?): ParcelFileDescriptor {
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
        if (mediaId.endsWith(CompatiblePhotoStore.ID_SUFFIX)) {
            val item = SafMediaCacheStore.cacheFor(context, configuredRootUri().toString(), ::queryMedia)
                .currentSnapshot().mediaById[mediaId]
                ?: throw FileNotFoundException("Compatible photo is no longer indexed")
            val edge = previewEdge(size.x, size.y)
            return previewFileStore.withFile("$mediaId:thumbnail:$edge", syncGeneration,
                checkCanceled = { signal?.throwIfCanceled() }, writer = { target ->
                openMedia(mediaId, signal, item).use { descriptor ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > edge * 2) sample *= 2
                    val bitmap = BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null,
                        BitmapFactory.Options().apply { inSampleSize = sample })
                        ?: throw IOException("Cannot decode compatible preview")
                    try { target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
                    finally { bitmap.recycle() }
                }
            }, reader = ::openCachedPreview)
        }
        if (mimeType?.startsWith(VIDEO_MIME_PREFIX) == true &&
            extras?.getBoolean(CloudMediaProviderContract.EXTRA_PREVIEW_THUMBNAIL, false) != true
        ) {
            return openOriginalPreview(documentUri, signal)
        }
        val edge = previewEdge(size.x, size.y)
        val previewKey = "$mediaId:thumbnail:$edge"
        val exactPreview = PREVIEW_EDGES.asSequence().filter { it >= edge }.mapNotNull { cachedEdge ->
            previewFileStore.readCached("$mediaId:thumbnail:$cachedEdge", syncGeneration, ::openCachedPreview)
        }.firstOrNull()
        if (exactPreview != null) return exactPreview
        val stalePreview = previewFileStore.readLatest(previewKey, ::openCachedPreview)
        if (stalePreview != null) {
            refreshPreviewAsync(previewKey, syncGeneration, documentUri, edge)
            return stalePreview
        }
        val preview = try {
            previewFileStore.withFile(
                previewKey,
                syncGeneration,
                checkCanceled = { signal?.throwIfCanceled() },
                writer = { targetFile -> copyPreviewToFile(documentUri, signal, targetFile, edge,
                    allowOriginalFallback && mimeType?.startsWith(VIDEO_MIME_PREFIX) != true) },
                reader = ::openCachedPreview,
            )
        } catch (error: IOException) {
            if (!allowOriginalFallback || mimeType?.startsWith(VIDEO_MIME_PREFIX) == true) throw error
            // Let the consumer read the original directly instead of waiting for a full cache copy.
            return openOriginalPreview(documentUri, signal)
        }

        return preview
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
        val signal = SafMediaCacheStore.folderCancellationSignal()
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
        allowOriginalFallback: Boolean = false,
    ) {
        try {
            loadPreviewAssetFileDescriptor(documentUri, signal, edge).use { descriptor ->
                descriptor.createInputStream().use { inputStream ->
                    FileOutputStream(targetFile).use { outputStream ->
                        copyWithCancellation(inputStream, outputStream) { signal?.throwIfCanceled() }
                    }
                }
            }
            if (targetFile.length() == 0L) throw IOException("Source thumbnail is empty")
        } catch (error: IOException) {
            if (!allowOriginalFallback) throw error
            signal?.throwIfCanceled()
            thumbnailEncoder.write(ImageDecoder.createSource(context.contentResolver, documentUri), targetFile, edge) {
                signal?.throwIfCanceled()
            }
        }
    }

    private fun loadPreviewAssetFileDescriptor(
        documentUri: Uri,
        signal: CancellationSignal?,
        edge: Int,
    ): AssetFileDescriptor {
        val options = bundleOf(ContentResolver.EXTRA_SIZE to Point(edge, edge))

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
        private const val VIDEO_MIME_PREFIX = "video/"
        private const val DIRECTORY_MIME_TYPE = DocumentsContract.Document.MIME_TYPE_DIR
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
            // Reindex IDs without padding so Android's synthetic file paths match URI grants.
            rootUri?.toString()?.let { "tree-v3:${it.hashCode()}" } ?: "unconfigured"

        fun mediaId(documentUri: Uri): String =
            Base64.encodeToString(
                documentUri.toString().toByteArray(StandardCharsets.UTF_8),
                Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING,
            )

        fun decodeMediaId(mediaId: String): Uri =
            String(
                Base64.decode(mediaId.removeSuffix(CompatiblePhotoStore.ID_SUFFIX), Base64.NO_WRAP or Base64.URL_SAFE),
                StandardCharsets.UTF_8,
            ).toUri()

        internal fun shouldIndexMedia(mimeType: String): Boolean =
            mimeType.startsWith("image/") || mimeType.startsWith(VIDEO_MIME_PREFIX)
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

        Log.i(TAG, "Read ${sortedItems.size} source metadata items from ${rootUri.authority}")
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
        SafDirectoryReader(context.contentResolver).read(childrenUri, projection, signal) { cursor ->
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
                    shouldIndexMedia(mimeType) -> {
                        val item = SafMediaItem(
                            mediaId = mediaId(childDocumentUri),
                            documentUri = childDocumentUri,
                            displayName = displayName,
                            mimeType = mimeType,
                            dateTakenMillis = lastModified,
                            syncGeneration = lastModified?.coerceAtLeast(1L) ?: 1L,
                            sizeBytes = sizeBytes,
                        )
                        mediaItems += item.copy(mediaStoreUri = localMediaLinks.resolve(item))
                    }
                }
            }
        }
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

}

internal data class SafMediaItem(
    val mediaId: String,
    val documentUri: Uri,
    val displayName: String?,
    val mimeType: String,
    val dateTakenMillis: Long?,
    val syncGeneration: Long,
    val sizeBytes: Long?,
    val mediaStoreUri: Uri? = null,
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
