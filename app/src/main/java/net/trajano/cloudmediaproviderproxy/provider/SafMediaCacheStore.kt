package net.trajano.cloudmediaproviderproxy.provider

import android.content.Context
import android.database.ContentObserver
import android.graphics.Point
import android.os.CancellationSignal
import android.provider.CloudMediaProviderContract
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import net.trajano.cloudmediaproviderproxy.config.SafRootPreferences

internal object SafMediaCacheStore {
    private const val TAG = "SafMediaCacheStore"
    private val snapshotCaches = ConcurrentHashMap<String, SqliteSafMediaSnapshotCache>()
    private val scanSignals = mutableMapOf<String, CancellationSignal>()
    private val sourceObservers = mutableMapOf<String, ContentObserver>()
    private val warmUpExecutor = Executors.newSingleThreadExecutor()
    private var warmUpSignal: CancellationSignal? = null
    private var folderSignal = CancellationSignal()

    /** Cancel thumbnail refreshes and original transfers when the selected folder changes. */
    @Synchronized
    fun folderCancellationSignal(): CancellationSignal = folderSignal

    /** Index ahead of the picker and warm only a bounded batch of recent thumbnails. */
    @Synchronized
    fun warmUp(context: Context) {
        warmUpSignal?.cancel()
        val signal = CancellationSignal()
        warmUpSignal = signal
        val application = context.applicationContext
        val catalog = SafMediaCatalog(application)
        val root = catalog.configuredRootUri() ?: return
        MediaBackgroundWork.watch(application)
        val cache = cacheFor(application, root.toString(), catalog::queryMedia)
        warmUpExecutor.execute {
            try {
                signal.throwIfCanceled()
                val snapshot = cache.currentSnapshot()
                val extras = bundleOf(CloudMediaProviderContract.EXTRA_PREVIEW_THUMBNAIL to true)
                snapshot.mediaItems.sortedByDescending { it.dateTakenMillis ?: 0L }.take(12).forEach { item ->
                    signal.throwIfCanceled()
                    if (catalog.configuredRootUri() != root) return@execute
                    try {
                        catalog.openPreview(
                            item.mediaId, item.syncGeneration, signal, Point(512, 512), extras,
                            item.mimeType, allowOriginalFallback = false,
                        ).close()
                    } catch (canceled: android.os.OperationCanceledException) {
                        throw canceled
                    } catch (error: Exception) {
                        Log.d(TAG, "Skipping unavailable thumbnail during cache warming", error)
                    }
                }
            } catch (_: android.os.OperationCanceledException) {
                // Visible requests and folder changes take priority over speculative downloads.
            } catch (error: Exception) {
                Log.w(TAG, "Unable to warm media cache", error)
            }
        }
    }

    /** Cancel speculative transfers as soon as a visible preview is requested. */
    @Synchronized
    fun prioritizeVisiblePreviews() {
        warmUpSignal?.cancel()
    }

    @Synchronized
    fun cacheFor(
        context: Context,
        cacheKey: String,
        snapshotLoader: (CancellationSignal) -> SafMediaSnapshot,
    ): SqliteSafMediaSnapshotCache =
        snapshotCaches.computeIfAbsent(cacheKey) {
            val scanSignal = CancellationSignal()
            scanSignals[cacheKey] = scanSignal
            SqliteSafMediaSnapshotCache(
                context = context,
                initializeInBackground = true,
                requestBackgroundRefresh = { MediaBackgroundWork.request(context.applicationContext) },
                cacheKey = cacheKey + if (SafRootPreferences(context).compatiblePhotosEnabled()) {
                    ":${CompatiblePhotoStore.VERSION}"
                } else "",
                snapshotLoader = { snapshotLoader(scanSignal) },
                onSnapshotChanged = {
                    val resolver = context.contentResolver
                    val authority = "${context.packageName}.cloud"
                    try {
                        if (MediaStore.isCurrentCloudMediaProviderAuthority(resolver, authority)) {
                            MediaStore.notifyCloudMediaChangedEvent(
                                resolver, authority, SafMediaCatalog(context).collectionId(),
                            )
                        }
                    } catch (error: RuntimeException) {
                        Log.w(TAG, "Unable to notify photo picker of updated media", error)
                    }
                },
            ).also { cache ->
                cacheKey.toUri().authority?.let { authority ->
                    val observer = object : ContentObserver(null) {
                        override fun onChange(selfChange: Boolean) { cache.requestRefresh() }
                    }
                    context.contentResolver.registerContentObserver("content://$authority".toUri(), true, observer)
                    sourceObservers[cacheKey] = observer
                }
            }
        }

    @Synchronized
    fun clearAll(context: Context, preserveLocalMediaLinks: Boolean = false) {
        SafRootPreferences(context).advanceBackgroundWorkEpoch()
        MediaBackgroundWork.cancel(context)
        warmUpSignal?.cancel()
        folderSignal.cancel()
        folderSignal = CancellationSignal()
        scanSignals.values.forEach(CancellationSignal::cancel)
        scanSignals.clear()
        sourceObservers.values.forEach(context.contentResolver::unregisterContentObserver)
        sourceObservers.clear()
        val databasePath = context.getDatabasePath(SqliteSafMediaSnapshotCache.DATABASE_NAME)
        val previewRoot = previewDirectory(context)
        val previewFileCount = previewRoot.listFiles()?.size ?: 0
        Log.i(
            TAG,
            "Clearing SAF caches; inMemoryEntries=${snapshotCaches.size}, " +
                "databasePath=${databasePath.absolutePath}, databaseExists=${databasePath.exists()}, " +
                "previewDir=${previewRoot.absolutePath}, previewFiles=$previewFileCount",
        )
        snapshotCaches.values.forEach(SafMediaSnapshotCache::invalidate)
        snapshotCaches.clear()
        val databaseDeleted = context.deleteDatabase(SqliteSafMediaSnapshotCache.DATABASE_NAME)
        val previewsDeleted = PreviewFileStore.clearDirectory(previewRoot)
        OriginalMediaFileStore(originalsDirectory(context)).clear()
        OriginalMediaFileStore(CompatiblePhotoStore.directory(context)).clear()
        CompatiblePhotoStore.clearMetadata(context)
        if (!preserveLocalMediaLinks) LocalMediaLinkStore.forContext(context).clear()
        Log.i(
            TAG,
            "SAF cache clear complete; databaseDeleted=$databaseDeleted, " +
                "databaseStillExists=${databasePath.exists()}, previewsDeleted=$previewsDeleted, " +
                "previewDirStillExists=${previewRoot.exists()}",
        )
    }

    fun previewDirectory(context: Context): File = File(context.cacheDir, PREVIEW_DIRECTORY_NAME)

    fun originalsDirectory(context: Context): File = File(context.cacheDir, "originals")

    private const val PREVIEW_DIRECTORY_NAME = "previews"
}
