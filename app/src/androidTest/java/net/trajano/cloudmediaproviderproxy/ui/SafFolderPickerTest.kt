package net.trajano.cloudmediaproviderproxy.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import net.trajano.cloudmediaproviderproxy.config.SafRootPreferences
import org.junit.Assert.*
import org.junit.Test

class SafFolderPickerTest {
    @Test fun pickerPreservesInitialFolderReadGrantAndAdvancedRootsPreference() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = SafRootPreferences(context)
        val original = preferences.showAdvancedRoots()
        val root = "content://example/tree/photos".toUri()
        try {
            for (advanced in listOf(false, true)) {
                preferences.setShowAdvancedRoots(advanced)
                val intent = SafFolderPicker().createIntent(context, root)
                assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)
                assertEquals(root, IntentCompat.getParcelableExtra(intent, DocumentsContract.EXTRA_INITIAL_URI, Uri::class.java))
                assertEquals(advanced, intent.getBooleanExtra("android.content.extra.SHOW_ADVANCED", !advanced))
                val grants = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                assertEquals(grants, intent.flags and grants)
            }
        } finally { preferences.setShowAdvancedRoots(original) }
    }

    @Test fun cancellationDoesNotReturnAFolder() {
        val contract = SafFolderPicker()
        val root = "content://example/tree/photos".toUri()
        val result = Intent().setData(root)
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, result))
        assertEquals(root, contract.parseResult(Activity.RESULT_OK, result))
        assertNull(contract.parseResult(Activity.RESULT_OK, null))
    }
}
