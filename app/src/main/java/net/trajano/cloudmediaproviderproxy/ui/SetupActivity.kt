package net.trajano.cloudmediaproviderproxy.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
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

    private val openDocumentTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            treeUri ?: return@registerForActivityResult
            persistTreePermission(treeUri)
            rootPreferences.saveRootUri(treeUri)
            refreshStatus()
        }

    private lateinit var rootPreferences: SafRootPreferences
    private lateinit var providerIconView: ImageView
    private lateinit var statusTextView: TextView
    private lateinit var pickRootButton: MaterialButton
    private lateinit var clearRootButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        rootPreferences = SafRootPreferences(this)
        providerIconView = findViewById(R.id.setup_provider_icon)
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

    private fun chooseSafRoot() {
        openDocumentTree.launch(rootPreferences.getRootUri())
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

    private fun persistTreePermission(treeUri: Uri) {
        contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }

    private fun refreshStatus() {
        val rootUri = rootPreferences.getRootUri()
        if (rootUri == null) {
            providerIconView.setImageDrawable(null)
            providerIconView.visibility = View.GONE
            statusTextView.text = getString(R.string.setup_status_unconfigured)
            pickRootButton.text = getString(R.string.pick_root_button)
            clearRootButton.visibility = View.GONE
            return
        }

        if (!rootPreferences.hasPersistedReadPermission(contentResolver)) {
            providerIconView.setImageDrawable(null)
            providerIconView.visibility = View.GONE
            statusTextView.text = getString(R.string.setup_status_permission_lost)
            pickRootButton.text = getString(R.string.change_root_button)
            clearRootButton.visibility = View.VISIBLE
            return
        }

        val treeId = runCatching { DocumentsContract.getTreeDocumentId(rootUri) }.getOrNull()
        val authority = rootUri.authority ?: getString(R.string.setup_status_unknown)
        val providerLabel = resolveProviderLabel(authority)
        val folderName = resolveFolderName(rootUri)
        bindProviderIcon(authority, providerLabel)

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

    private fun bindProviderIcon(authority: String, providerLabel: String) {
        val icon = resolveProviderIcon(authority)
        if (icon == null) {
            providerIconView.setImageDrawable(null)
            providerIconView.visibility = View.GONE
            providerIconView.contentDescription = null
            return
        }

        providerIconView.setImageDrawable(icon)
        providerIconView.visibility = View.VISIBLE
        providerIconView.contentDescription = getString(R.string.setup_provider_icon_content_description, providerLabel)
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

    private fun resolveProviderIcon(authority: String): Drawable? {
        val providerInfo = packageManager.resolveContentProvider(authority, PackageManager.MATCH_DISABLED_COMPONENTS)
            ?: return null
        return runCatching { providerInfo.loadIcon(packageManager) }.getOrNull()
            ?: runCatching { packageManager.getApplicationIcon(providerInfo.packageName) }.getOrNull()
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
}
