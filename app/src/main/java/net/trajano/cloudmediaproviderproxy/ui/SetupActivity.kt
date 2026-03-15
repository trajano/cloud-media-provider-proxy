package net.trajano.cloudmediaproviderproxy.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import net.trajano.cloudmediaproviderproxy.R
import net.trajano.cloudmediaproviderproxy.config.SafRootPreferences

class SetupActivity : AppCompatActivity() {

    private lateinit var rootPreferences: SafRootPreferences
    private lateinit var statusTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        rootPreferences = SafRootPreferences(this)
        statusTextView = findViewById(R.id.setup_status)
        applyWindowInsets(findViewById(R.id.setup_container))

        findViewById<Button>(R.id.pick_root_button).setOnClickListener {
            chooseSafRoot()
        }
        findViewById<Button>(R.id.clear_root_button).setOnClickListener {
            clearSafRoot()
        }

        refreshStatus()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
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
            return
        }

        if (!rootPreferences.hasPersistedReadPermission(contentResolver)) {
            statusTextView.text = getString(R.string.setup_status_permission_lost)
            return
        }

        val treeId = runCatching { DocumentsContract.getTreeDocumentId(rootUri) }.getOrNull()
        val authority = rootUri.authority ?: getString(R.string.setup_status_unknown)

        statusTextView.text = getString(
            R.string.setup_status_configured,
            authority,
            treeId ?: getString(R.string.setup_status_unknown),
        )
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
