package net.trajano.cloudmediaproviderproxy.provider

import android.content.Context
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.FileNotFoundException
import java.util.UUID

/** Resume from persisted sizes and snapshots, rather than an in-memory task queue. */
class MediaIndexWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    private val signal = CancellationSignal()

    override fun onStopped() { signal.cancel() }

    override fun doWork(): Result {
        val selection = MediaWorkSelection.fromData(inputData) ?: return Result.failure()
        if (!selection.matches(MediaBackgroundWork.selection(applicationContext))) return Result.success()
        var cache: SqliteSafMediaSnapshotCache? = null
        fun checkCurrent() {
            signal.throwIfCanceled()
            if (isStopped || !selection.matches(MediaBackgroundWork.selection(applicationContext))) {
                throw OperationCanceledException("Selected folder changed")
            }
        }
        try {
            val catalog = SafMediaCatalog(applicationContext)
            if (catalog.configuredRootUri()?.toString() != selection.root) return Result.success()
            cache = SafMediaCacheStore.cacheFor(applicationContext, selection.root, catalog::queryMedia)
            cache.beginScheduledRefresh()
            val previousToken = inputData.getString("source-token")
            val sourceToken = previousToken ?: UUID.randomUUID().toString()
            val source = if (previousToken != null) {
                // A newer source scan supersedes this continuation, including after process death.
                cache.preparationSource(previousToken) ?: return Result.success()
            } else {
                catalog.querySourceMedia(signal).also {
                    checkCurrent()
                    cache.savePreparationSource(sourceToken, it)
                }
            }
            checkCurrent()
            cache.publishSnapshot(catalog.readySnapshot(source))
            val copies = CompatiblePhotoStore(applicationContext)
            val pending = source.mediaItems.filter { catalog.needsCompatiblePhoto(it) && copies.knownSize(it) == null }
            for (item in pending.filter { copies.retryAtMillis(it) <= System.currentTimeMillis() }.take(4)) {
                checkCurrent()
                try {
                    copies.open(item, signal, checkActive = ::checkCurrent) {
                        applicationContext.contentResolver.openFileDescriptor(item.documentUri, "r", signal)
                            ?: throw FileNotFoundException("Cannot open photo for preparation")
                    }.close()
                } catch (error: Exception) {
                    checkCurrent()
                    copies.recordFailure(item)
                    Log.w("MediaIndexWorker", "Photo preparation will be retried", error)
                }
                checkCurrent()
                cache.publishSnapshot(catalog.readySnapshot(source))
            }
            checkCurrent()
            return continuePreparation(selection, sourceToken, pending, copies)
        } catch (error: Exception) {
            if (isStopped || !selection.matches(MediaBackgroundWork.selection(applicationContext))) return Result.success()
            Log.w("MediaIndexWorker", "Indexing will resume in a later attempt", error)
            return Result.retry()
        } finally { cache?.finishScheduledRefresh() }
    }

    private fun continuePreparation(selection: MediaWorkSelection, sourceToken: String,
        pending: List<SafMediaItem>, copies: CompatiblePhotoStore): Result {
        val remaining = pending.filter { copies.knownSize(it) == null }
        if (remaining.isEmpty()) return Result.success()
        if (remaining.none { copies.retryAtMillis(it) <= System.currentTimeMillis() }) return Result.retry()
        // Commit the continuation before completing this batch, including after a restart.
        MediaBackgroundWork.enqueue(applicationContext, selection, sourceToken).result.get()
        return Result.success()
    }
}
