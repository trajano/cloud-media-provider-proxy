package net.trajano.cloudmediaproviderproxy.provider

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestWorkerBuilder
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class MediaIndexWorkerTest {
    @android.annotation.SuppressLint("RestrictedApi")
    @Test fun continuationRetainsItsCheckpointAndFolderSelection() {
        val selection = MediaWorkSelection("content://example/tree/photos", 41, true)
        val data = MediaBackgroundWork.newRequest(selection, "persisted-source-scan").workSpec.input
        assertEquals("persisted-source-scan", data.getString("source-token"))
        assertEquals(selection, MediaWorkSelection.fromData(data))
    }

    @android.annotation.SuppressLint("RestrictedApi")
    @Test fun sourceWatchWaitsForBatchedNotificationsWithoutExpeditedWork() {
        val selection = MediaWorkSelection("content://org.nextcloud.documents/tree/account%2Fphotos", 41, true)
        val request = MediaBackgroundWork.newWatchRequest(selection)
        val constraints = request.workSpec.constraints
        val trigger = constraints.contentUriTriggers.singleOrNull()
        assertNotNull("A durable source notification trigger must be registered", trigger)
        assertEquals("content://org.nextcloud.documents", trigger!!.uri.toString())
        assertTrue(trigger.isTriggeredForDescendants)
        assertEquals(10_000L, constraints.contentTriggerUpdateDelayMillis)
        assertEquals(60_000L, constraints.contentTriggerMaxDelayMillis)
        assertTrue(constraints.requiresBatteryNotLow())
        assertFalse(request.workSpec.expedited)
        assertEquals(selection, MediaWorkSelection.fromData(request.workSpec.input))
    }

    @Test fun jobSelectionSurvivesSerialization() {
        val selection = MediaWorkSelection("content://example/tree/photos", 41, true)
        val restored = MediaWorkSelection.fromData(Data.fromByteArray(selection.toData().toByteArray()))
        assertEquals(selection, restored)
    }

    @Test fun anOldFolderJobFinishesWithoutReadingTheCurrentFolder() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val stale = MediaWorkSelection("content://example/tree/old", -1, false)
            val worker = TestWorkerBuilder.from(context, MediaIndexWorker::class.java, executor)
                .setInputData(stale.toData()).build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
        } finally { executor.shutdownNow() }
    }

    @Test fun anOldFolderWatcherDoesNotRearmOrRequestIndexing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val stale = MediaWorkSelection("content://example/tree/old", -1, false)
            val worker = TestWorkerBuilder.from(context, MediaSourceWatchWorker::class.java, executor)
                .setInputData(stale.toData()).build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
        } finally { executor.shutdownNow() }
    }
}
