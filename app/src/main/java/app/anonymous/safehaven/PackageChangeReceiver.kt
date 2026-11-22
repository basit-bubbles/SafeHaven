package app.anonymous.safehaven

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Install listener. When an app is installed or updated:
 * - an app you installed that is not on your list is blocked immediately, right here;
 * - then the full blocking pass runs (which also handles built-in apps).
 * If the app cannot be looked up, it is treated as one you installed and blocked
 * (fail closed). The full pass releases it again if that was wrong.
 */
class PackageChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            val action = intent.action
            if (action != Intent.ACTION_PACKAGE_ADDED && action != Intent.ACTION_PACKAGE_REPLACED) return
            val pkg = intent.data?.schemeSpecificPart ?: return
            if (pkg == context.packageName) return

            // Never trust a cached flag here: re-read whether Monk Mind is Device Owner.
            runCatching { Privilege.updateStatus() }
            if (!Privilege.status.value.device || !SP.protectInstall) return

            if (!AppWhitelist.isWhitelistedSync(pkg) && !isBuiltIn(context, pkg)) {
                runCatching {
                    Privilege.DPM.setPackagesSuspended(Privilege.DAR, arrayOf(pkg), true)
                    Log.i(Watcher.TAG, "Blocked on install: $pkg")
                }.onFailure { Log.e(Watcher.TAG, "Instant block failed for $pkg", it) }
            }
        } catch (t: Throwable) {
            Log.e(Watcher.TAG, "Install listener error", t)
        } finally {
            runCatching { Watcher.request(context, "install", full = true) }
        }
    }

    /** True only when the app is confirmed to be a built-in (system) app. */
    private fun isBuiltIn(context: Context, pkg: String): Boolean = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)
        }
        (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }.getOrDefault(false)
}
