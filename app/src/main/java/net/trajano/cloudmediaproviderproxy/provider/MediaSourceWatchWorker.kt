package net.trajano.cloudmediaproviderproxy.provider

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/** A one-shot OS notification trigger, rearmed before scheduling the source scan. */
class MediaSourceWatchWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val selection = MediaWorkSelection.fromData(inputData) ?: return Result.failure()
        if (isStopped || !selection.matches(MediaBackgroundWork.selection(applicationContext))) return Result.success()
        return try {
            MediaBackgroundWork.watch(applicationContext, selection, rearm = true).result.get()
            if (!isStopped && selection.matches(MediaBackgroundWork.selection(applicationContext))) {
                MediaBackgroundWork.enqueue(applicationContext, selection).result.get()
            }
            Result.success()
        } catch (error: Exception) {
            Log.w("MediaSourceWatchWorker", "Unable to schedule source change refresh", error)
            Result.retry()
        }
    }
}
