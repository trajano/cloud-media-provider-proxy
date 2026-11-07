package net.trajano.cloudmediaproviderproxy.provider

import org.junit.Assert.*
import org.junit.Test

class MediaWorkSelectionTest {
    @Test fun persistedJobsAreRejectedAfterFolderOrSettingsChanges() {
        val original = MediaWorkSelection("content://example/tree/photos", 12, true)
        assertTrue(original.matches(original.copy()))
        assertFalse(original.matches(original.copy(epoch = 13)))
        assertFalse(original.matches(original.copy(root = "content://example/tree/other")))
        assertFalse(original.matches(original.copy(compatible = false)))
        assertFalse(original.matches(null))
    }
}
