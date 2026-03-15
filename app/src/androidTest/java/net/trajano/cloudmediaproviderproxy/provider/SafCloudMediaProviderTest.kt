package net.trajano.cloudmediaproviderproxy.provider

import android.os.Bundle
import android.provider.CloudMediaProviderContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafCloudMediaProviderTest {

    @Test
    fun queryMediaSetsMediaCollectionIdInCursorExtras() {
        val provider = SafCloudMediaProvider()

        provider.onQueryMedia(Bundle()).use { cursor ->
            assertEquals(
                "unconfigured",
                cursor.extras.getString(CloudMediaProviderContract.EXTRA_MEDIA_COLLECTION_ID),
            )
        }
    }

    @Test
    fun queryDeletedMediaSetsMediaCollectionIdInCursorExtras() {
        val provider = SafCloudMediaProvider()

        provider.onQueryDeletedMedia(Bundle()).use { cursor ->
            assertEquals(
                "unconfigured",
                cursor.extras.getString(CloudMediaProviderContract.EXTRA_MEDIA_COLLECTION_ID),
            )
        }
    }

    @Test
    fun queryAlbumsSetsMediaCollectionIdInCursorExtras() {
        val provider = SafCloudMediaProvider()

        provider.onQueryAlbums(Bundle()).use { cursor ->
            assertEquals(
                "unconfigured",
                cursor.extras.getString(CloudMediaProviderContract.EXTRA_MEDIA_COLLECTION_ID),
            )
        }
    }
}
