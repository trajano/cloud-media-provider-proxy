package net.trajano.cloudmediaproviderproxy.provider

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import net.trajano.cloudmediaproviderproxy.config.SafRootPreferences
import java.util.UUID
import java.util.concurrent.TimeUnit

internal object MediaBackgroundWork {
    private const val TAG = "media-indexing"
    private const val WATCH_TAG = "media-source-watch"

    fun selection(context: Context): MediaWorkSelection? {
        val preferences = SafRootPreferences(context)
        return preferences.getRootUri()?.let {
            MediaWorkSelection(it.toString(), preferences.backgroundWorkEpoch(), preferences.compatiblePhotosEnabled())
        }
    }

    fun request(context: Context) {
        selection(context)?.let { enqueue(context, it) }
    }

    fun enqueue(context: Context, selection: MediaWorkSelection, sourceToken: String? = null): Operation {
        val request = newRequest(selection, sourceToken)
        val key = "${selection.root}\n${selection.epoch}\n${selection.compatible}"
        val name = "$TAG:${UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8))}"
        // Appending preserves source changes arriving while a worker is finishing.
        return WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun newRequest(selection: MediaWorkSelection, sourceToken: String? = null): OneTimeWorkRequest =
        OneTimeWorkRequest.Builder(MediaIndexWorker::class.java)
            .setInputData(androidx.work.Data.Builder().putAll(selection.toData())
                .putString("source-token", sourceToken).build()).addTag(TAG)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()

    fun watch(context: Context) {
        selection(context)?.let { watch(context, it) }
    }

    fun watch(context: Context, selection: MediaWorkSelection, rearm: Boolean = false): Operation {
        val key = "${selection.root}\n${selection.epoch}\n${selection.compatible}"
        val name = "$WATCH_TAG:${UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8))}"
        return WorkManager.getInstance(context).enqueueUniqueWork(name,
            if (rearm) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            newWatchRequest(selection))
    }

    fun newWatchRequest(selection: MediaWorkSelection): OneTimeWorkRequest {
        // SAF notifications can use document or roots URIs outside the granted tree URI.
        val authority = requireNotNull(selection.root.toUri().authority)
        val constraints = Constraints.Builder()
            .addContentUriTrigger(Uri.Builder().scheme("content").authority(authority).build(), true)
            .setTriggerContentUpdateDelay(10, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(60, TimeUnit.SECONDS)
            .setRequiresBatteryNotLow(true).build()
        return OneTimeWorkRequest.Builder(MediaSourceWatchWorker::class.java)
            .setInputData(selection.toData()).setConstraints(constraints)
            .addTag(TAG).addTag(WATCH_TAG).build()
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }
}
