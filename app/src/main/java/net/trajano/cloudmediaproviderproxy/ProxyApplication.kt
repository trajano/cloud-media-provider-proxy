package net.trajano.cloudmediaproviderproxy

import android.app.Application
import android.util.Log

class ProxyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Cloud media provider proxy process created")
    }

    private companion object {
        private const val TAG = "ProxyApplication"
    }
}
