package app.anonymous.safehaven

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import app.anonymous.safehaven.dpm.UPDATE_WINDOW_MS
import app.anonymous.safehaven.dpm.enforceInstallLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Keeps Monk Mind running all the time, independent of anything else.
 * A Device Owner app is allowed to run this kind of service ("system exempted"),
 * so Android does not put it to sleep or close it in the background.
 * It shows one quiet notification: "Monk Mind is on", with an "Install or update" button.
 */
class GuardService : Service() {

    override fun onCreate() {
        super.onCreate()
        goForeground()
        runCatching { Watcher.start(this) }
        Log.i(Watcher.TAG, "Guard started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must run after every start request, or Android closes the app.
        goForeground()
        runCatching { Watcher.request(this, "guard", full = false) }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.w(Watcher.TAG, "Guard stopped")
        super.onDestroy()
    }

    private fun goForeground() {
        runCatching { StatusNotification.ensureChannel(this) }
            .onFailure { Log.e(Watcher.TAG, "Channel setup failed", it) }
        val notification = runCatching { StatusNotification.build(this) }.getOrElse { first ->
            Log.e(Watcher.TAG, "Notification build failed, using a plain one", first)
            runCatching { StatusNotification.plain(this) }.getOrNull() ?: return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(StatusNotification.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            } else {
                startForeground(StatusNotification.ID, notification)
            }
        } catch (first: Throwable) {
            Log.e(Watcher.TAG, "System-exempted start failed, trying special-use", first)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(StatusNotification.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                }
            } catch (second: Throwable) {
                Log.e(Watcher.TAG, "Guard could not go foreground", second)
            }
        }
    }
}

/**
 * The "Monk Mind is on" notification. Three states:
 * - setup mode: USB debugging is on (before the seal), installing and Chrome are open;
 * - locked: installing is locked, with an "Install or update (15 min)" button.
 *   Before the seal the title says "test mode, not sealed", so you can see the seal happened;
 * - update window: installing is open until a set time; new apps still get blocked.
 */
object StatusNotification {
    const val ID = 7
    private const val CHANNEL_ID = "monk_mind_status"
    @Volatile private var lastShown = ""

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Monk Mind status", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shows that Monk Mind protection is running"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun newBuilder(context: Context): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(context)
        }

    fun plain(context: Context): Notification =
        newBuilder(context).setSmallIcon(android.R.drawable.ic_lock_lock).build()

    private fun state(context: Context): Pair<String, String> {
        val usbDebuggingOn = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) != 0
        }.getOrDefault(false)
        val windowUntil = SP.updateWindowUntil
        return when {
            usbDebuggingOn && !SP.isSealed -> "setup" to ""
            System.currentTimeMillis() < windowUntil ->
                "window" to DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(windowUntil))
            SP.isSealed -> "locked" to ""
            else -> "locked" to "test"
        }
    }

    /** Forces the next refresh to re-post, even if nothing changed. */
    fun invalidate() {
        lastShown = ""
    }

    fun build(context: Context): Notification {
        val (mode, detail) = state(context)
        lastShown = mode + detail
        val builder = newBuilder(context)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
        when (mode) {
            "setup" -> builder
                .setContentTitle("Monk Mind is on (setup mode)")
                .setContentText("USB debugging is on, so installing and Chrome are open.")
            "window" -> builder
                .setContentTitle("Installing is open until $detail")
                .setContentText("Install or update apps from your list now. Anything else still gets blocked.")
            else -> {
                builder
                    .setContentTitle(if (detail == "test") "Monk Mind is on (test mode, not sealed)" else "Monk Mind is on")
                    .setContentText("Installing is locked. Apps update overnight while charging.")
                val tap = PendingIntent.getBroadcast(
                    context, 1,
                    Intent(context, UpdateWindowReceiver::class.java).setAction(UpdateWindowReceiver.ACTION),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                @Suppress("DEPRECATION")
                builder.addAction(Notification.Action.Builder(null, "Install or update (15 min)", tap).build())
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    /** Re-posts the notification only when its state changed. */
    fun refresh(context: Context) {
        runCatching {
            val (mode, detail) = state(context)
            if (mode + detail == lastShown) return
            ensureChannel(context)
            context.getSystemService(NotificationManager::class.java)?.notify(ID, build(context))
        }.onFailure { Log.e(Watcher.TAG, "Notification refresh failed", it) }
    }
}

/** "Install or update (15 min)" button: opens installing for 15 minutes (apps on your list only open). */
class UpdateWindowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        SP.updateWindowUntil = System.currentTimeMillis() + UPDATE_WINDOW_MS
        Log.i(Watcher.TAG, "Update window opened for 15 minutes")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                enforceInstallLock(context)
                StatusNotification.refresh(context)
            } finally {
                pending.finish()
            }
        }
        runCatching { Watcher.request(context, "update-window", full = false) }
    }

    companion object {
        const val ACTION = "app.anonymous.safehaven.OPEN_UPDATE_WINDOW"
    }
}

/**
 * Backup alarm clock: Android runs this every 15 minutes, even after a restart.
 * It restarts the guard and runs a full check, in case anything stopped it.
 */
class GuardJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        runCatching { Guard.ensureRunning(this) }
        runCatching { Watcher.request(this, "job", full = true) }
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}

/** Starts every layer of protection. Safe to call often, from anywhere. */
object Guard {
    private const val JOB_ID = 4242
    private const val PERIOD_MS = 15 * 60 * 1000L

    fun ensureRunning(context: Context) {
        val app = context.applicationContext
        runCatching { Privilege.updateStatus() }
        if (!Privilege.status.value.device) return

        runCatching { Watcher.start(app) }

        // Layer 1: the always-on foreground guard.
        runCatching {
            val intent = Intent(app, GuardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent) else app.startService(intent)
        }.onFailure { Log.e(Watcher.TAG, "Could not start guard", it) }

        // Layer 2: the 15-minute backup job (survives restarts).
        runCatching {
            val js = app.getSystemService(JobScheduler::class.java) ?: return@runCatching
            if (js.getPendingJob(JOB_ID) == null) {
                val job = JobInfo.Builder(JOB_ID, ComponentName(app, GuardJobService::class.java))
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)
                    .build()
                js.schedule(job)
                Log.i(Watcher.TAG, "Backup job scheduled")
            }
        }.onFailure { Log.e(Watcher.TAG, "Could not schedule backup job", it) }
    }
}
