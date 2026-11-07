package net.trajano.cloudmediaproviderproxy.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import net.trajano.cloudmediaproviderproxy.config.SafRootPreferences

internal class SafFolderPicker : OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input).apply {
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            putExtra("android.content.extra.SHOW_ADVANCED", SafRootPreferences(context).showAdvancedRoots())
        }
}
