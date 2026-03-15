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

    override fun onGetMediaCollectionInfo(extras: Bundle): Bundle =
        Bundle().apply {
            // TODO: Replace these placeholders with collection metadata sourced from SAF.
            putString(
                CloudMediaProviderContract.MediaCollectionInfo.MEDIA_COLLECTION_ID,
                MEDIA_COLLECTION_ID,
            )
            putLong(CloudMediaProviderContract.MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION, 0L)
            putString(
                CloudMediaProviderContract.MediaCollectionInfo.ACCOUNT_NAME,
                "nextcloud-placeholder",
            )
            putString(
                CloudMediaProviderContract.MediaCollectionInfo.ACCOUNT_CONFIGURATION_INTENT,
                null,
            )
        }

    override fun onQueryMedia(extras: Bundle): Cursor {
        Log.i(TAG, "onQueryMedia called with extras=$extras")
        // TODO: Return mapped SAF documents as CloudMediaProvider rows.
        return emptyCursorWithCollectionId(
            CloudMediaProviderContract.MediaColumns.ID,
            CloudMediaProviderContract.MediaColumns.MEDIA_STORE_URI,
            CloudMediaProviderContract.MediaColumns.MIME_TYPE,
            CloudMediaProviderContract.MediaColumns.STANDARD_MIME_TYPE_EXTENSION,
            CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MILLIS,
            CloudMediaProviderContract.MediaColumns.SYNC_GENERATION,
            CloudMediaProviderContract.MediaColumns.SIZE_BYTES,
            CloudMediaProviderContract.MediaColumns.DURATION_MILLIS,
            CloudMediaProviderContract.MediaColumns.IS_FAVORITE,
        )
    }

    override fun onQueryDeletedMedia(extras: Bundle): Cursor {
        Log.i(TAG, "onQueryDeletedMedia called with extras=$extras")
        // TODO: Surface deletions when the SAF-backed source can track them.
        return emptyCursorWithCollectionId(CloudMediaProviderContract.MediaColumns.ID)
    }

    override fun onQueryAlbums(extras: Bundle): Cursor {
        Log.i(TAG, "onQueryAlbums called with extras=$extras")
        // TODO: Model SAF roots/folders as albums if that fits the staged design.
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
    ): ParcelFileDescriptor {
        throw FileNotFoundException("Media opening is not implemented yet for $mediaId")
    }

    override fun onOpenPreview(
        mediaId: String,
        size: Point,
        extras: Bundle?,
        signal: CancellationSignal?,
    ): AssetFileDescriptor {
        throw FileNotFoundException("Preview opening is not implemented yet for $mediaId")
    }

    private fun emptyCursorWithCollectionId(vararg columns: String): Cursor =
        MatrixCursor(columns).apply {
            extras = Bundle().apply {
                putString(CloudMediaProviderContract.EXTRA_MEDIA_COLLECTION_ID, MEDIA_COLLECTION_ID)
            }
        }

    private companion object {
        private const val TAG = "SafCloudMediaProvider"
        private const val MEDIA_COLLECTION_ID = "bootstrap"
    }
}
