package net.trajano.cloudmediaproviderproxy.provider

import org.junit.Assert.assertEquals
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
}
