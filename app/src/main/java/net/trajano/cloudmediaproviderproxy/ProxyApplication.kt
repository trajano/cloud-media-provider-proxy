package net.trajano.cloudmediaproviderproxy

import android.app.Application
import android.util.Log
import com.google.android.material.color.DynamicColors

class ProxyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        Log.i(TAG, "Cloud media provider proxy process created")
    }

    private companion object {
        private const val TAG = "ProxyApplication"
    }
}
