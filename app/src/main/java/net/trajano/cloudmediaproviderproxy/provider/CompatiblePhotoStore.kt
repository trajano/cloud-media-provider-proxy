package net.trajano.cloudmediaproviderproxy.provider

import android.content.Context
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException

internal class CompatiblePhotoStore(context: Context) {
    private val store = OriginalMediaFileStore(directory(context))

    fun open(item: SafMediaItem, signal: CancellationSignal?,
        source: () -> ParcelFileDescriptor): ParcelFileDescriptor {
        val folderSignal = SafMediaCacheStore.folderCancellationSignal()
        return store.open(
            mediaId = "${item.documentUri}:$VERSION",
            syncGeneration = item.dateTakenMillis ?: 0L,
            expectedSize = null,
            checkCanceled = { signal?.throwIfCanceled(); folderSignal.throwIfCanceled() },
            writer = { file, checkCanceled ->
                source().use { CompatiblePhotoEncoder.writeJpeg(it, file, MAX_BYTES, checkCanceled) }
            },
            reader = { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) },
        ) ?: throw IOException("Compatible photo cache is unavailable")
    }

    companion object {
        const val MAX_BYTES = 10_000_000L
        const val VERSION = "jpeg10-v1"
        const val ID_SUFFIX = ".jpeg10v1"
        fun directory(context: Context) = File(context.cacheDir, "compatible-photos")
    }
}
