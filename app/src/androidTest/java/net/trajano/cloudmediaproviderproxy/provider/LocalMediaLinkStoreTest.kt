package net.trajano.cloudmediaproviderproxy.provider

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaLinkStoreTest {
    @Test
    fun clearingTheFolderRejectsAnOldDiscoveryCallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "local-link-clear-test-${System.nanoTime()}"
        try {
            val store = LocalMediaLinkStore(context.getSharedPreferences(name, Context.MODE_PRIVATE))
            val item = SafMediaItem("photo", Uri.parse("content://example/photo"), "photo.png",
                "image/png", 20L, 20L, 8L)
            val local = Uri.parse("content://media/external_primary/images/media/123")
            val oldEpoch = store.epoch()
            store.clear()
            assertFalse(store.record(item, local, oldEpoch))
            store.recordUnavailable(item, oldEpoch)
            assertFalse(store.hasResult(item))
            assertNull(store.resolve(item))
            assertTrue(store.record(item, local))
            assertEquals(local, store.resolve(item))
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test
    fun aPreparedLocalCopyIsLinkedUntilTheSourceContentChanges() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "local-link-test-${System.nanoTime()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            val item = SafMediaItem("photo", Uri.parse("content://example/photo"), "photo.png",
                "image/png", 20L, 20L, 8L)
            val local = Uri.parse("content://media/external_primary/images/media/123")
            LocalMediaLinkStore(preferences).record(item, local)
            val restored = LocalMediaLinkStore(preferences)
            assertEquals(local, restored.resolve(item))
            assertEquals(local, restored.resolve(item.copy(syncGeneration = 21L)))
            assertNull(restored.resolve(item.copy(sizeBytes = 9L)))
            assertNull(restored.resolve(item.copy(dateTakenMillis = 22L)))
            assertNull(restored.resolve(item.copy(documentUri = Uri.parse("content://example/other"))))
        } finally { context.deleteSharedPreferences(name) }
    }
}
