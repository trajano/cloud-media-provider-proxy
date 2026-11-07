package net.trajano.cloudmediaproviderproxy.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafMediaCatalogTest {

    /** Request sufficient detail while reusing nearby cached thumbnail sizes. */
    @Test
    fun previewSizesUseBoundedBuckets() {
        assertEquals(256, SafMediaCatalog.previewEdge(120, 200))
        assertEquals(512, SafMediaCatalog.previewEdge(300, 512))
        assertEquals(1024, SafMediaCatalog.previewEdge(800, 600))
        assertEquals(2048, SafMediaCatalog.previewEdge(10_000, 10_000))
        assertEquals(256, SafMediaCatalog.previewEdge(0, -1))
    }

    @Test
    fun accountNameForDisplayPrefersResolvedProviderLabel() {
        assertEquals(
            "Nextcloud",
            accountNameForDisplay(
                authority = "org.nextcloud.documents",
                providerLabel = "Nextcloud",
            ),
        )
    }

    @Test
    fun accountNameForDisplayFallsBackToAuthorityWhenLabelMissing() {
        assertEquals(
            "org.nextcloud.documents",
            accountNameForDisplay(
                authority = "org.nextcloud.documents",
                providerLabel = "",
            ),
        )
    }

    @Test
    fun indexesImages() {
        assertTrue(SafMediaCatalog.shouldIndexMedia("image/jpeg"))
        assertTrue(SafMediaCatalog.shouldIndexMedia("image/png"))
    }

    @Test
    fun doesNotIndexDocuments() {
        assertFalse(SafMediaCatalog.shouldIndexMedia("application/pdf"))
        assertFalse(SafMediaCatalog.shouldIndexMedia("application/json"))
    }

    @Test
    fun keepsVideosRegardlessOfSize() {
        assertTrue(SafMediaCatalog.shouldIndexMedia("video/mp4"))
    }

    @Test
    fun doesNotIndexDirectoriesAsMedia() {
        assertFalse(SafMediaCatalog.shouldIndexMedia("vnd.android.document/directory"))
    }
}
