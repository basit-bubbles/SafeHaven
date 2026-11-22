package app.anonymous.safehaven

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import app.anonymous.safehaven.dpm.enforceAllPolicies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Runs after every restart and after every Monk Mind update.
 *
 * Two-stage lock:
 * - Restart while Developer options is ON (USB debugging on or off) = test restart.
 *   Nothing is sealed, so an accidental or overnight restart during testing is harmless.
 * - Restart after turning Developer options OFF completely = seal. From then on
 *   Developer options and USB debugging are switched off and blocked for good.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in arrayOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { Privilege.updateStatus() }
                if (Privilege.status.value.device) {
                    if (action != Intent.ACTION_MY_PACKAGE_REPLACED && !SP.isSealed) {
                        val cr = context.contentResolver
                        val devOptionsOn = Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0
                        val usbDebuggingOn = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) != 0
                        if (shouldSeal(action, SP.isSealed, devOptionsOn, usbDebuggingOn)) {
                            SP.isSealed = true
                            Log.i(Watcher.TAG, "Sealed: restarted with Developer options off.")
                        } else {
                            Log.i(Watcher.TAG, "Test restart: Developer options is still on, so not sealing.")
                        }
                    }
                    runCatching { Guard.ensureRunning(context) }
                    enforceAllPolicies(context)
                    runCatching { Watcher.request(context, "boot", full = true) }
                }
            } catch (t: Throwable) {
                Log.e(Watcher.TAG, "Startup enforcement failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/**
 * Pure rule (no Android calls), so it can be tested on its own.
 * Seal only on a real restart, only once, and only when Developer options is fully off.
 */
internal fun shouldSeal(action: String?, alreadySealed: Boolean, devOptionsOn: Boolean, usbDebuggingOn: Boolean): Boolean {
    if (alreadySealed) return false
    if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return false
    return !devOptionsOn && !usbDebuggingOn
}
