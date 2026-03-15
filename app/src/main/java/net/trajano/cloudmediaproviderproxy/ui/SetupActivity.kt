package net.trajano.cloudmediaproviderproxy.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import net.trajano.cloudmediaproviderproxy.R
import net.trajano.cloudmediaproviderproxy.config.SafRootPreferences

class SetupActivity : AppCompatActivity() {

    private lateinit var rootPreferences: SafRootPreferences
    private lateinit var statusTextView: TextView
    private lateinit var pickRootButton: MaterialButton
    private lateinit var clearRootButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        rootPreferences = SafRootPreferences(this)
        statusTextView = findViewById(R.id.setup_status)
        pickRootButton = findViewById(R.id.pick_root_button)
        clearRootButton = findViewById(R.id.clear_root_button)
        applyWindowInsets(findViewById(R.id.setup_container))
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.setup_toolbar))

        findViewById<MaterialToolbar>(R.id.setup_toolbar).setNavigationOnClickListener {
            finish()
        }
        pickRootButton.setOnClickListener {
            chooseSafRoot()
        }
        clearRootButton.setOnClickListener {
            clearSafRoot()
        }

        refreshStatus()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_OPEN_DOCUMENT_TREE || resultCode != RESULT_OK) {
            return
        }

        val treeUri = data?.data ?: return
        persistTreePermission(treeUri, data.flags)
        rootPreferences.saveRootUri(treeUri)
        refreshStatus()
    }

    private fun chooseSafRoot() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
            putExtra("android.content.extra.SHOW_ADVANCED", true)
        }
        startActivityForResult(intent, REQUEST_OPEN_DOCUMENT_TREE)
    }

    private fun clearSafRoot() {
        rootPreferences.getRootUri()?.let { rootUri ->
            runCatching {
                contentResolver.releasePersistableUriPermission(
                    rootUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        rootPreferences.clearRootUri()
        refreshStatus()
    }

    private fun persistTreePermission(treeUri: Uri, flags: Int) {
        val persistableFlags =
            flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

        contentResolver.takePersistableUriPermission(
            treeUri,
            persistableFlags or Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }

    private fun refreshStatus() {
        val rootUri = rootPreferences.getRootUri()
        if (rootUri == null) {
            statusTextView.text = getString(R.string.setup_status_unconfigured)
            pickRootButton.text = getString(R.string.pick_root_button)
            clearRootButton.visibility = View.GONE
            return
        }

        if (!rootPreferences.hasPersistedReadPermission(contentResolver)) {
            statusTextView.text = getString(R.string.setup_status_permission_lost)
            pickRootButton.text = getString(R.string.change_root_button)
            clearRootButton.visibility = View.VISIBLE
            return
        }

        val treeId = runCatching { DocumentsContract.getTreeDocumentId(rootUri) }.getOrNull()
        val authority = rootUri.authority ?: getString(R.string.setup_status_unknown)
        val providerLabel = resolveProviderLabel(authority)
        val folderName = resolveFolderName(rootUri)

        statusTextView.text = getString(
            R.string.setup_status_configured,
            providerLabel,
            authority,
            folderName,
            treeId ?: getString(R.string.setup_status_unknown),
        )
        pickRootButton.text = getString(R.string.change_root_button)
        clearRootButton.visibility = View.VISIBLE
    }

    private fun resolveProviderLabel(authority: String): String {
        val providerInfo = packageManager.resolveContentProvider(authority, PackageManager.MATCH_DISABLED_COMPONENTS)
        val label = providerInfo?.loadLabel(packageManager)?.toString()?.trim().orEmpty()
        return if (label.isNotEmpty()) {
            label
        } else {
            authority
        }
    }

    private fun resolveFolderName(rootUri: Uri): String {
        val name = DocumentFile.fromTreeUri(this, rootUri)?.name?.trim().orEmpty()
        return if (name.isNotEmpty()) {
            name
        } else {
            runCatching { DocumentsContract.getTreeDocumentId(rootUri) }
                .getOrElse { getString(R.string.setup_status_unknown) }
        }
    }

    private fun applyWindowInsets(container: View) {
        val basePaddingLeft = container.paddingLeft
        val basePaddingTop = container.paddingTop
        val basePaddingRight = container.paddingRight
        val basePaddingBottom = container.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                basePaddingLeft + systemBars.left,
                basePaddingTop + systemBars.top,
                basePaddingRight + systemBars.right,
                basePaddingBottom + systemBars.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(container)
    }

    companion object {
        private const val REQUEST_OPEN_DOCUMENT_TREE = 1001
    }
}
