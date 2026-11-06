package net.trajano.cloudmediaproviderproxy.provider

import android.content.Context
import android.graphics.Point
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.CloudMediaProviderContract
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

internal object SafMediaCacheStore {
    private const val TAG = "SafMediaCacheStore"
    private val snapshotCaches = ConcurrentHashMap<String, SafMediaSnapshotCache>()
    private val scanSignals = mutableMapOf<String, CancellationSignal>()
    private val warmUpExecutor = Executors.newSingleThreadExecutor()
    private var warmUpSignal: CancellationSignal? = null
    private var refreshSignal = CancellationSignal()

    /** Share folder-lifetime cancellation with stale thumbnail refreshes. */
    @Synchronized
    fun backgroundPreviewSignal(): CancellationSignal = refreshSignal

    /** Index ahead of the picker and warm only a bounded batch of recent thumbnails. */
    @Synchronized
    fun warmUp(context: Context) {
        warmUpSignal?.cancel()
        val signal = CancellationSignal()
        warmUpSignal = signal
        val application = context.applicationContext
        val catalog = SafMediaCatalog(application)
        val root = catalog.configuredRootUri() ?: return
        val cache = cacheFor(application, root.toString(), catalog::queryMedia)
        warmUpExecutor.execute {
            try {
                signal.throwIfCanceled()
                val snapshot = cache.currentSnapshot()
                val extras = Bundle().apply {
                    putBoolean(CloudMediaProviderContract.EXTRA_PREVIEW_THUMBNAIL, true)
                }
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
    ): SafMediaSnapshotCache =
        snapshotCaches.computeIfAbsent(cacheKey) {
            val scanSignal = CancellationSignal()
            scanSignals[cacheKey] = scanSignal
            SqliteSafMediaSnapshotCache(
                context = context,
                cacheKey = cacheKey,
                snapshotLoader = { snapshotLoader(scanSignal) },
            )
        }

    @Synchronized
    fun clearAll(context: Context) {
        warmUpSignal?.cancel()
        refreshSignal.cancel()
        refreshSignal = CancellationSignal()
        scanSignals.values.forEach(CancellationSignal::cancel)
        scanSignals.clear()
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
        val previewsDeleted = previewRoot.deleteRecursively()
        Log.i(
            TAG,
            "SAF cache clear complete; databaseDeleted=$databaseDeleted, " +
                "databaseStillExists=${databasePath.exists()}, previewsDeleted=$previewsDeleted, " +
                "previewDirStillExists=${previewRoot.exists()}",
        )
    }

    fun previewDirectory(context: Context): File = File(context.cacheDir, PREVIEW_DIRECTORY_NAME)

    private const val PREVIEW_DIRECTORY_NAME = "previews"
}
