package net.trajano.cloudmediaproviderproxy.provider

import android.content.Context
import android.content.ContentUris
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.Os
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

/** Link only the specific source file opened through the user's SAF grant. */
internal class LocalMediaLinker(
    private val context: Context,
    private val store: LocalMediaLinkStore,
    private val executor: ExecutorService,
    private val onLinkReady: () -> Unit,
) {
    fun fromDescriptor(item: SafMediaItem, descriptor: ParcelFileDescriptor, signal: CancellationSignal) {
        if (store.hasResult(item) || !pending.add(store.key(item))) return
        discover(item, descriptor, signal)
    }

    /** Recover provenance for originals cached before local links were supported. */
    fun forCachedOriginal(item: SafMediaItem, signal: CancellationSignal, source: () -> ParcelFileDescriptor) {
        val key = store.key(item)
        if (store.hasResult(item) || !pending.add(key)) return
        try {
            executor.execute {
                try {
                    signal.throwIfCanceled()
                    source().use { discover(item, it, signal) }
                } catch (error: Exception) {
                    pending.remove(key)
                    if (!signal.isCanceled) Log.d(TAG, "Local copy discovery unavailable", error)
                }
            }
        } catch (_: RejectedExecutionException) { pending.remove(key) }
    }

    private fun discover(item: SafMediaItem, descriptor: ParcelFileDescriptor, signal: CancellationSignal) {
        val key = store.key(item)
        val epoch = store.epoch()
        try {
            signal.throwIfCanceled()
            val path = Os.readlink("/proc/self/fd/${descriptor.fd}")
            if (!path.startsWith("/storage/")) {
                if (!signal.isCanceled) store.recordUnavailable(item, epoch)
                return
            }
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val uri = context.contentResolver.query(collection,
                arrayOf(MediaStore.Files.FileColumns._ID),
                "${MediaStore.MediaColumns.DATA} = ?", arrayOf(path), null, signal)?.use { cursor ->
                if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
            }
            if (!signal.isCanceled) {
                if (uri != null && store.record(item, uri, epoch)) {
                    Log.i(TAG, "Linked original to an existing local media entry")
                    onLinkReady()
                } else if (uri == null) store.recordUnavailable(item, epoch)
            }
        } catch (error: Exception) {
            if (!signal.isCanceled) Log.d(TAG, "Source has no discoverable local media copy", error)
        } finally { pending.remove(key) }
    }

    companion object {
        private const val TAG = "LocalMediaLinker"
        private val pending = ConcurrentHashMap.newKeySet<String>()
    }
}
