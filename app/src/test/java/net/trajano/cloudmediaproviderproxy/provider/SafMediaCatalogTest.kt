package net.trajano.cloudmediaproviderproxy.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafMediaCatalogTest {

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
    fun indexesImagesUpToTenMegabytes() {
        assertTrue(SafMediaCatalog.shouldIndexMedia("image/jpeg", 10L * 1024L * 1024L))
    }

    @Test
    fun excludesImagesOverTenMegabytes() {
        assertFalse(SafMediaCatalog.shouldIndexMedia("image/jpeg", (10L * 1024L * 1024L) + 1L))
    }

    @Test
    fun keepsVideosRegardlessOfSize() {
        assertTrue(SafMediaCatalog.shouldIndexMedia("video/mp4", Long.MAX_VALUE))
    }

    @Test
    fun keepsImagesWhenSizeIsUnknown() {
        assertTrue(SafMediaCatalog.shouldIndexMedia("image/jpeg", null))
    }
}
