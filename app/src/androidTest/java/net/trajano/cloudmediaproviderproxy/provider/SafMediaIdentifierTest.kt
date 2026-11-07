package net.trajano.cloudmediaproviderproxy.provider

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test

class SafMediaIdentifierTest {
    @Test
    fun compatiblePhotoIdsResolveToTheSourceDocumentWithoutUriEscaping() {
        val source = Uri.parse("content://example/photo")
        val identifier = SafMediaCatalog.mediaId(source) + CompatiblePhotoStore.ID_SUFFIX
        val base = Uri.parse("content://media/picker/0/example.cloud/media")
        assertEquals(source, SafMediaCatalog.decodeMediaId(identifier))
        assertEquals(Uri.parse("$base/$identifier"), base.buildUpon().appendPath(identifier).build())
    }
    @Test
    fun previouslyIssuedPaddedIdsStillDecode() {
        assertEquals(Uri.parse("content://example/a"),
            SafMediaCatalog.decodeMediaId("Y29udGVudDovL2V4YW1wbGUvYQ=="))
    }

    @Test
    fun mediaIdSurvivesThePickersSyntheticFilePathWithoutUriEscaping() {
        val pickerBase = Uri.parse("content://media/picker/0/example.cloud/media")
        for (suffix in listOf("a", "ab", "abc")) {
            val document = Uri.parse("content://example/$suffix")
            val mediaId = SafMediaCatalog.mediaId(document)
            // Android reconstructs this URI from the synthetic filename without encoding the ID.
            val reconstructed = Uri.parse("$pickerBase/$mediaId")
            val granted = pickerBase.buildUpon().appendPath(mediaId).build()
            assertEquals("Synthetic paths must retain the granted URI", granted, reconstructed)
            assertEquals(document, SafMediaCatalog.decodeMediaId(mediaId))
        }
    }
}
