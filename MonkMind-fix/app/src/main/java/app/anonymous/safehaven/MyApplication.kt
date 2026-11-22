package app.anonymous.safehaven

import android.app.Application
import android.util.Log

lateinit var SP: SharedPrefs
    private set

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SP = SharedPrefs(this)
        AppWhitelist.initializeDefaultWhitelist()
        Privilege.initialize(this)
        // Whatever woke the app up, make sure every protection layer is running.
        if (Privilege.status.value.device) {
            runCatching { Guard.ensureRunning(this) }.onFailure { Log.e(Watcher.TAG, "Startup guard failed", it) }
        }
    }
}
