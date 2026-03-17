package net.trajano.cloudmediaproviderproxy.provider

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal object SafMediaCacheStore {
    private const val TAG = "SafMediaCacheStore"
    private val snapshotCaches = ConcurrentHashMap<String, SafMediaSnapshotCache>()

    fun cacheFor(
        context: Context,
        cacheKey: String,
        snapshotLoader: () -> SafMediaSnapshot,
    ): SafMediaSnapshotCache =
        snapshotCaches.computeIfAbsent(cacheKey) {
            SqliteSafMediaSnapshotCache(
                context = context,
                cacheKey = cacheKey,
                snapshotLoader = snapshotLoader,
            )
        }

    fun clearAll(context: Context) {
        val databasePath = context.getDatabasePath(SqliteSafMediaSnapshotCache.DATABASE_NAME)
        val previewRoot = previewDirectory(context)
        val previewFileCount = previewRoot.listFiles()?.size ?: 0
        Log.i(
            TAG,
            "Clearing SAF caches; inMemoryEntries=${snapshotCaches.size}, " +
                "databasePath=${databasePath.absolutePath}, databaseExists=${databasePath.exists()}, " +
                "previewDir=${previewRoot.absolutePath}, previewFiles=$previewFileCount",
        )
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
