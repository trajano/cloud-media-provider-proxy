package net.trajano.cloudmediaproviderproxy.provider

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OriginalMediaFileStoreDeviceTest {
    @Test
    fun anOpenedOriginalRemainsReadableAfterItsCacheEntryIsEvicted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "original-eviction-test-${System.nanoTime()}")
        val store = OriginalMediaFileStore(directory, maxBytes = 8)
        var first: ParcelFileDescriptor? = null
        try {
            first = store.open("first", 1, 8, writer = { file, _ -> file.writeText("original") },
                reader = { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) })!!
            val file = directory.listFiles()!!.single()
            store.open("second", 1, 8, writer = { target, _ -> target.writeText("newbytes") },
                reader = { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) })!!.close()
            assertFalse(file.exists())
            val content = ParcelFileDescriptor.AutoCloseInputStream(first).use { it.readBytes().decodeToString() }
            first = null
            assertEquals("original", content)
        } finally {
            first?.close()
            store.clear()
            directory.deleteRecursively()
        }
    }
}
