package app.anonymous.safehaven

import android.app.admin.DeviceAdminService
import android.util.Log

/**
 * Android itself keeps this service connected for the Device Owner.
 * It is one of several ways the watcher gets started; the watcher keeps running
 * in the app even if Android reconnects this service (it does so at every unlock).
 */
class SafeHavenDeviceAdminService : DeviceAdminService() {

    override fun onCreate() {
        super.onCreate()
        Log.i(Watcher.TAG, "Admin service connected")
        runCatching { Watcher.start(this) }
        runCatching { Guard.ensureRunning(this) }
        runCatching { Watcher.request(this, "admin-service", full = true) }
    }

    override fun onDestroy() {
        Log.i(Watcher.TAG, "Admin service disconnected")
        super.onDestroy()
    }
}
