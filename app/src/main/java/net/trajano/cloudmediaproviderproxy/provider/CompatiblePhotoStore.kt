package net.trajano.cloudmediaproviderproxy.provider

import android.content.Context
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import androidx.core.content.edit

internal class CompatiblePhotoStore(
    context: Context,
    private val store: OriginalMediaFileStore = OriginalMediaFileStore(directory(context)),
) {
    private val sizes = context.getSharedPreferences(METADATA_NAME, Context.MODE_PRIVATE)

    fun knownSize(item: SafMediaItem): Long? {
        synchronized(metadataLock) {
            sizes.getLong(metadataKey(item), -1L).takeIf { it in 1..MAX_BYTES }?.let { return it }
        }
        val epoch = synchronized(metadataLock) { sizes.getLong("_epoch", 0L) }
        return store.cachedSize("${item.documentUri}:$VERSION", item.dateTakenMillis ?: 0L)
            ?.takeIf { it <= MAX_BYTES }?.also { recordSize(item, it, epoch) }
    }

    private fun recordSize(item: SafMediaItem, size: Long, epoch: Long) = synchronized(metadataLock) {
        if (epoch == sizes.getLong("_epoch", 0L) && sizes.getLong(metadataKey(item), -1L) != size) {
            sizes.edit { putLong(metadataKey(item), size) }
        }
    }

    private fun metadataKey(item: SafMediaItem) = "${item.documentUri}:${item.dateTakenMillis}:$VERSION"

    fun retryAtMillis(item: SafMediaItem): Long = sizes.getLong("retry:${metadataKey(item)}", 0L)

    fun recordFailure(item: SafMediaItem) = synchronized(metadataLock) {
        sizes.edit { putLong("retry:${metadataKey(item)}", System.currentTimeMillis() + 30_000L) }
    }

    fun open(item: SafMediaItem, signal: CancellationSignal?, checkActive: () -> Unit = {},
        source: () -> ParcelFileDescriptor): ParcelFileDescriptor {
        val folderSignal = SafMediaCacheStore.folderCancellationSignal()
        val epoch = synchronized(metadataLock) { sizes.getLong("_epoch", 0L) }
        return store.open(
            mediaId = "${item.documentUri}:$VERSION",
            syncGeneration = item.dateTakenMillis ?: 0L,
            expectedSize = null,
            checkCanceled = { signal?.throwIfCanceled(); folderSignal.throwIfCanceled(); checkActive() },
            writer = { file, checkCanceled ->
                source().use { CompatiblePhotoEncoder.writeJpeg(it, file, MAX_BYTES, checkCanceled) }
            },
            reader = {
                recordSize(item, it.length(), epoch)
                ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
            },
        ) ?: throw IOException("Compatible photo cache is unavailable")
    }

    companion object {
        const val MAX_BYTES = 10_000_000L
        const val VERSION = "jpeg10-v1"
        const val ID_SUFFIX = ".jpeg10v1"
        private const val METADATA_NAME = "compatible-photo-sizes"
        private val metadataLock = Any()

        fun clearMetadata(context: Context) = synchronized(metadataLock) {
            val sizes = context.getSharedPreferences(METADATA_NAME, Context.MODE_PRIVATE)
            val epoch = sizes.getLong("_epoch", 0L) + 1L
            sizes.edit { clear(); putLong("_epoch", epoch) }
        }
        fun directory(context: Context) = File(context.cacheDir, "compatible-photos")
    }
}
