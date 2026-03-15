package net.trajano.cloudmediaproviderproxy.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log

class ProxyBridgeService : Service() {

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Proxy bridge service created")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Proxy bridge service started")
        // TODO: Move long-lived initialization here once the SAF proxy is implemented.
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Proxy bridge service destroyed")
        super.onDestroy()
    }

    inner class LocalBinder : Binder() {
        fun getService(): ProxyBridgeService = this@ProxyBridgeService
    }

    private companion object {
        private const val TAG = "ProxyBridgeService"
    }
}
