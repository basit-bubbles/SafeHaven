package app.anonymous.safehaven

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import android.content.BroadcastReceiver
import app.anonymous.safehaven.dpm.enforceAllPolicies
import app.anonymous.safehaven.dpm.enforceInstallLock
import app.anonymous.safehaven.dpm.enforceLockState
import app.anonymous.safehaven.dpm.enforceStrictWhitelist
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The always-on watcher. There is one per app process, and every entry point starts it:
 * the system admin service, the foreground guard, the backup job, a restart and app start.
 * Starting it twice does nothing, so it is safe to call from all of them.
 *
 * - Blocks a new install the moment it lands (install listener).
 * - Checks every 10 seconds; runs the full blocking pass when the app list changed,
 *   and at least every 2 minutes either way.
 */
object Watcher {
    const val TAG = "MonkMind"
    private const val TICK_MS = 10_000L
    private const val FULL_PASS_MS = 120_000L

    @Volatile private var app: Context? = null
    private val started = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    @Volatile private var again = false
    @Volatile private var fullRequested = true
    @Volatile private var lastFullPass = 0L
    @Volatile private var lastSequence = 0
    @Volatile private var allPoliciesApplied = false

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "Watcher task failed", e) }
    )

    private val tick = object : Runnable {
        override fun run() {
            try {
                app?.let { request(it, "timer", full = false) }
            } catch (t: Throwable) {
                Log.e(TAG, "Timer failed", t)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            app?.let { request(it, "settings", full = false) }
        }
    }

    // Screen on/off and charger in/out: re-check the install lock right away.
    // Screen on = locked again before you can even unlock the phone.
    private val screenAndPowerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val ctx = app ?: return
            scope.launch {
                runCatching { enforceInstallLock(ctx) }
                runCatching { StatusNotification.refresh(ctx) }
            }
            request(ctx, "screen-or-power", full = false)
        }
    }

    fun start(context: Context) {
        if (app == null) app = context.applicationContext
        if (!started.compareAndSet(false, true)) return
        val ctx = app ?: return

        // 1. Install listener: blocks a new app the moment it is installed.
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
                priority = 999
            }
            ContextCompat.registerReceiver(ctx, PackageChangeReceiver(), filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure { Log.e(TAG, "Install listener failed", it) }

        // 2. Screen and charger changes drive the install lock.
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            ContextCompat.registerReceiver(ctx, screenAndPowerReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure { Log.e(TAG, "Screen listener failed", it) }

        // 3. React at once if developer options, USB debugging or animations are changed.
        runCatching {
            val cr = ctx.contentResolver
            listOf(
                Settings.Global.getUriFor(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
                Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),
                Settings.Global.getUriFor("window_animation_scale"),
                Settings.Global.getUriFor("transition_animation_scale"),
                Settings.Global.getUriFor("animator_duration_scale")
            ).forEach { cr.registerContentObserver(it, false, settingsObserver) }
        }.onFailure { Log.e(TAG, "Settings listener failed", it) }

        // 4. The 10-second timer.
        handler.post(tick)
        Log.i(TAG, "Watcher started")
    }

    /** Ask for a check now. Safe from any thread. [full] forces the full blocking pass. */
    fun request(context: Context, reason: String, full: Boolean) {
        start(context)
        if (full) fullRequested = true
        if (!running.compareAndSet(false, true)) {
            again = true
            return
        }
        scope.launch {
            try {
                do {
                    again = false
                    runOnce(reason)
                } while (again)
            } finally {
                running.set(false)
            }
            // A request that arrived in the gap above gets picked up here.
            if (again) app?.let { request(it, "follow-up", full = false) }
        }
    }

    private suspend fun runOnce(reason: String) {
        val ctx = app ?: return
        // Once per app start: every policy (DNS, notification permission, restrictions,
        // blocking), as soon as Monk Mind is Device Owner.
        if (!allPoliciesApplied) {
            runCatching { Privilege.updateStatus() }
            if (Privilege.status.value.device) {
                allPoliciesApplied = true
                runCatching { enforceAllPolicies(ctx) }.onFailure { Log.e(TAG, "Full policy pass failed", it) }
            }
        }
        val now = SystemClock.elapsedRealtime()
        val doFull = fullRequested || packagesChanged(ctx) || now - lastFullPass >= FULL_PASS_MS
        if (doFull) {
            fullRequested = false
            runCatching { enforceStrictWhitelist(ctx) }.onFailure { Log.e(TAG, "Blocking pass failed ($reason)", it) }
            lastFullPass = SystemClock.elapsedRealtime()
        }
        runCatching { enforceLockState(ctx) }.onFailure { Log.e(TAG, "Lock check failed ($reason)", it) }
        runCatching { StatusNotification.refresh(ctx) }
    }

    /** True when any app was installed, updated, removed or changed since the last look. */
    private fun packagesChanged(ctx: Context): Boolean = try {
        val changed = ctx.packageManager.getChangedPackages(lastSequence)
        if (changed == null) {
            false
        } else {
            lastSequence = changed.sequenceNumber
            true
        }
    } catch (t: Throwable) {
        true
    }
}
