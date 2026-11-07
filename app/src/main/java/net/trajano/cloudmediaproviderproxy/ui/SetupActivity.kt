package net.trajano.cloudmediaproviderproxy.ui

import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import net.trajano.cloudmediaproviderproxy.R
import net.trajano.cloudmediaproviderproxy.config.SafRootPreferences
import net.trajano.cloudmediaproviderproxy.provider.SafMediaCacheStore

class SetupActivity : AppCompatActivity() {

    private val openDocumentTree =
        registerForActivityResult(SafFolderPicker()) { treeUri ->
            if (treeUri == null) return@registerForActivityResult
            val previousRootUri = rootPreferences.getRootUri()
            if (treeUri != previousRootUri) {
                Log.i(TAG, "SAF root changed; clearing caches. previous=$previousRootUri new=$treeUri")
                SafMediaCacheStore.clearAll(this)
            } else {
                Log.i(TAG, "SAF root unchanged; skipping cache clear for $treeUri")
            }
            persistTreePermission(treeUri)
            rootPreferences.saveRootUri(treeUri)
            SafMediaCacheStore.warmUp(this)
            refreshStatus()
        }

    private lateinit var rootPreferences: SafRootPreferences
    private lateinit var showAdvancedSwitch: MaterialSwitch
    private lateinit var providerIconView: AppCompatImageView
    private lateinit var statusTextView: AppCompatTextView
    private lateinit var pickRootButton: MaterialButton
    private lateinit var clearRootButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        rootPreferences = SafRootPreferences(this)
        showAdvancedSwitch = findViewById(R.id.show_advanced_switch)
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
        findViewById<MaterialButton>(R.id.open_nextcloud_button).setOnClickListener {
            val intent = nextcloudLaunchIntent(rootPreferences.getRootUri()?.authority)
            if (intent == null) {
                showNextcloudUnavailable()
            } else {
                try {
                    startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    showNextcloudUnavailable()
                } catch (_: SecurityException) {
                    showNextcloudUnavailable()
                }
            }
        }
        findViewById<MaterialSwitch>(R.id.compatible_photos_switch).apply {
            isChecked = rootPreferences.compatiblePhotosEnabled()
            setOnCheckedChangeListener { _, enabled ->
                SafMediaCacheStore.clearAll(this@SetupActivity, preserveLocalMediaLinks = true)
                rootPreferences.setCompatiblePhotosEnabled(enabled)
                SafMediaCacheStore.warmUp(this@SetupActivity)
            }
        }
        showAdvancedSwitch.isChecked = rootPreferences.showAdvancedRoots()
        showAdvancedSwitch.setOnCheckedChangeListener { _, isChecked ->
            rootPreferences.setShowAdvancedRoots(isChecked)
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshNextcloudStorageNotice()
    }

    private fun refreshNextcloudStorageNotice() {
        val authority = rootPreferences.getRootUri()?.authority
        val visible = isNextcloudDocumentsAuthority(authority)
        findViewById<View>(R.id.nextcloud_storage_notice).isVisible = visible
        findViewById<View>(R.id.open_nextcloud_button).isVisible = visible && nextcloudLaunchIntent(authority) != null
    }

    private fun nextcloudLaunchIntent(authority: String?): Intent? {
        if (!isNextcloudDocumentsAuthority(authority)) return null
        val provider = packageManager.resolveContentProvider(authority!!, PackageManager.MATCH_DISABLED_COMPONENTS)
            ?: return null
        return packageManager.getLaunchIntentForPackage(provider.packageName)
    }

    private fun showNextcloudUnavailable() {
        Toast.makeText(this, R.string.nextcloud_unavailable, Toast.LENGTH_SHORT).show()
    }

    private fun chooseSafRoot() {
        openDocumentTree.launch(rootPreferences.getRootUri())
    }

    private fun clearSafRoot() {
        Log.i(TAG, "Clearing SAF root and associated caches")
        rootPreferences.getRootUri()?.let { rootUri ->
            runCatching {
                contentResolver.releasePersistableUriPermission(
                    rootUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        SafMediaCacheStore.clearAll(this)
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
        refreshNextcloudStorageNotice()
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
            view.updatePadding(
                left = basePaddingLeft + systemBars.left,
                top = basePaddingTop + systemBars.top,
                right = basePaddingRight + systemBars.right,
                bottom = basePaddingBottom + systemBars.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(container)
    }

    private companion object {
        private const val TAG = "SetupActivity"
    }
}
