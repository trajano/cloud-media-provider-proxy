package net.trajano.cloudmediaproviderproxy.provider

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.CloudMediaProvider
import android.provider.CloudMediaProviderContract
import android.util.Log
import java.io.FileNotFoundException

class SafCloudMediaProvider : CloudMediaProvider() {

    override fun onCreate(): Boolean {
        Log.i(TAG, "Cloud media provider created")
        return true
    }

    override fun onGetMediaCollectionInfo(extras: Bundle): Bundle = catalog()?.buildCollectionInfo()
        ?: Bundle().apply {
            putString(
                CloudMediaProviderContract.MediaCollectionInfo.MEDIA_COLLECTION_ID,
                MEDIA_COLLECTION_ID,
            )
            putLong(CloudMediaProviderContract.MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION, 0L)
            putString(
                CloudMediaProviderContract.MediaCollectionInfo.ACCOUNT_NAME,
                "Not configured",
            )
        }

    override fun onQueryMedia(extras: Bundle): Cursor {
        Log.i(TAG, "onQueryMedia called with extras=$extras")
        val cursor = MatrixCursor(
            arrayOf(
                CloudMediaProviderContract.MediaColumns.ID,
                CloudMediaProviderContract.MediaColumns.MEDIA_STORE_URI,
                CloudMediaProviderContract.MediaColumns.MIME_TYPE,
                CloudMediaProviderContract.MediaColumns.STANDARD_MIME_TYPE_EXTENSION,
                CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MILLIS,
                CloudMediaProviderContract.MediaColumns.SYNC_GENERATION,
                CloudMediaProviderContract.MediaColumns.SIZE_BYTES,
                CloudMediaProviderContract.MediaColumns.DURATION_MILLIS,
                CloudMediaProviderContract.MediaColumns.IS_FAVORITE,
            ),
        )

        val snapshot = catalog()?.queryMedia()
        snapshot?.mediaItems?.forEach { mediaItem ->
            cursor.addRow(
                arrayOf<Any?>(
                    mediaItem.mediaId,
                    null,
                    mediaItem.mimeType,
                    0,
                    mediaItem.dateTakenMillis,
                    mediaItem.syncGeneration,
                    mediaItem.sizeBytes,
                    null,
                    0,
                ),
            )
        }
        Log.i(TAG, "onQueryMedia returning ${snapshot?.mediaItems?.size ?: 0} items")

        return cursor.withCollectionId(collectionId())
    }

    override fun onQueryDeletedMedia(extras: Bundle): Cursor {
        Log.i(TAG, "onQueryDeletedMedia called with extras=$extras")
        return emptyCursorWithCollectionId(CloudMediaProviderContract.MediaColumns.ID)
    }

    override fun onQueryAlbums(extras: Bundle): Cursor {
        Log.i(TAG, "onQueryAlbums called with extras=$extras")
        return emptyCursorWithCollectionId(
            CloudMediaProviderContract.AlbumColumns.ID,
            CloudMediaProviderContract.AlbumColumns.DISPLAY_NAME,
            CloudMediaProviderContract.AlbumColumns.DATE_TAKEN_MILLIS,
            CloudMediaProviderContract.AlbumColumns.MEDIA_COVER_ID,
            CloudMediaProviderContract.AlbumColumns.MEDIA_COUNT,
        )
    }

    override fun onOpenMedia(
        mediaId: String,
        extras: Bundle?,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor = catalog()?.openMedia(mediaId)
        ?: throw FileNotFoundException("Media opening is unavailable without a provider context")

    override fun onOpenPreview(
        mediaId: String,
        size: Point,
        extras: Bundle?,
        signal: CancellationSignal?,
    ): AssetFileDescriptor = catalog()?.openPreview(mediaId, signal)
        ?: throw FileNotFoundException("Preview opening is unavailable without a provider context")

    private fun emptyCursorWithCollectionId(vararg columns: String): Cursor =
        MatrixCursor(columns).withCollectionId(collectionId())

    private fun MatrixCursor.withCollectionId(collectionId: String): MatrixCursor =
        apply {
            extras = Bundle().apply {
                putString(CloudMediaProviderContract.EXTRA_MEDIA_COLLECTION_ID, collectionId)
            }
        }

    private fun collectionId(): String = catalog()?.configuredRootUri()?.let(SafMediaCatalog::mediaCollectionId)
        ?: MEDIA_COLLECTION_ID

    private fun catalog(): SafMediaCatalog? = context?.let(::SafMediaCatalog)

    private companion object {
        private const val TAG = "SafCloudMediaProvider"
        private const val MEDIA_COLLECTION_ID = "unconfigured"
    }
}
