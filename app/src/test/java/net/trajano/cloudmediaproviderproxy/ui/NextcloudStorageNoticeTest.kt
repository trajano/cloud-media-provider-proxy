package net.trajano.cloudmediaproviderproxy.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextcloudStorageNoticeTest {
    @Test fun recognizesNextcloudAndItsBuildVariants() {
        assertTrue(isNextcloudDocumentsAuthority("org.nextcloud.documents"))
        assertTrue(isNextcloudDocumentsAuthority("org.nextcloud.documents.beta"))
    }

    @Test fun doesNotWarnForOtherDocumentProviders() {
        assertFalse(isNextcloudDocumentsAuthority("com.android.externalstorage.documents"))
        assertFalse(isNextcloudDocumentsAuthority("org.nextcloud.documents-unrelated"))
        assertFalse(isNextcloudDocumentsAuthority(null))
    }
}
