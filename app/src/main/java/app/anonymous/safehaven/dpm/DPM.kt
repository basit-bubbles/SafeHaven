package app.anonymous.safehaven.dpm

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import app.anonymous.safehaven.AppWhitelist
import app.anonymous.safehaven.BuildConfig
import app.anonymous.safehaven.Privilege
import app.anonymous.safehaven.Privilege.DAR
import app.anonymous.safehaven.Privilege.DPM
import app.anonymous.safehaven.SP
import app.anonymous.safehaven.StatusNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "MonkMind"

// Dynamically loaded from CI/CD Secrets
private val BATTERY_SAVER_FLAGS = BuildConfig.BATTERY_SAVER_FLAGS

// --- HARDENING: system apps that must always be suspended (unlaunchable + un-re-enableable) ---
// Edit this list to taste. Anything here is force-suspended on every sweep.
val SYSTEM_BLOCKLIST = arrayOf(
    // Browsers
    "com.android.chrome",
    "com.chrome.beta",
    "com.sec.android.app.sbrowser",
    "com.sec.android.app.sbrowser.beta",
    "com.microsoft.emmx",
    "org.mozilla.firefox",
    "com.opera.browser",
    "com.brave.browser",
    "com.duckduckgo.mobile.android",
    // Stores and installers
    "com.sec.android.app.samsungapps",
    "com.samsung.android.themestore",
    "com.facebook.system",
    "com.facebook.appmanager",
    "com.facebook.services",
    // Video, social and feeds
    "com.google.android.youtube",
    "com.google.android.apps.youtube.music",
    "com.google.android.apps.youtube.kids",
    "com.google.android.videos",
    "com.google.android.apps.magazines",
    "com.google.android.play.games",
    "com.facebook.katana",
    "com.instagram.android",
    "com.zhiliaoapp.musically",
    "com.netflix.mediaclient",
    "com.spotify.music",
    "com.linkedin.android",
    "com.samsung.android.app.spage",
    "com.samsung.android.game.gamehome",
    // Search and assistants
    "com.google.android.googlequicksearchbox",
    "com.google.android.apps.bard",
    "com.google.android.apps.googleassistant",
    "com.samsung.android.bixby.agent",
    // Other preloads
    "com.google.android.apps.docs",
    "com.google.android.apps.tachyon",
    "com.microsoft.skydrive",
    "com.samsung.android.spay",
    "com.samsung.knox.securefolder"
)

// --- DEFAULT DENY: preinstalled apps the phone needs to work. Every other app with a
// home-screen icon that is not on APP_WHITELIST and not in this set gets suspended.
val ESSENTIAL_SYSTEM_APPS = setOf(
    // Play Store and the Google services it needs to install and update apps
    "com.android.vending",
    "com.google.android.gms",
    "com.google.android.gsf",
    "com.google.android.webview",
    "com.google.android.modulemetadata",
    // Phone basics
    "com.android.settings",
    "com.samsung.android.app.telephonyui",
    "com.samsung.android.app.smartcapture",
    "com.samsung.android.lool",
    "com.samsung.android.fmm",
    "com.samsung.accessibility",
    "com.wssyncmldm",
    "com.sec.android.soagent",
    "com.samsung.android.dialer",
    "com.samsung.android.incallui",
    "com.android.phone",
    "com.android.server.telecom",
    "com.samsung.android.messaging",
    "com.samsung.android.app.contacts",
    "com.sec.android.app.camera",
    "com.sec.android.gallery3d",
    "com.sec.android.app.popupcalculator",
    "com.sec.android.app.myfiles",
    "com.sec.android.app.launcher",
    "com.samsung.android.emergency",
    "com.google.android.documentsui",
    "com.android.documentsui",
    "com.google.android.projection.gearhead",
    "com.android.stk",
    "com.android.stk2",
    "com.samsung.android.app.routines",
    "com.samsung.android.forest",
    "com.samsung.ssu",
    "com.android.cellbroadcastreceiver",
    "com.google.android.cellbroadcastreceiver",
    "com.samsung.android.cellbroadcastreceiver",
    "com.android.systemui",
    "com.samsung.android.honeyboard",
    "com.google.android.dialer",
    "com.google.android.apps.messaging",
    "com.google.android.contacts"
)

// Only one blocking pass runs at a time.
private val enforceMutex = Mutex()
// Packages Android refused to pause (for example the active home screen) -> time to try again.
// A refusal is retried after 10 minutes, so a one-off hiccup can never leave an app open for good.
private val retryBlockAfter = ConcurrentHashMap<String, Long>()
private const val RETRY_BLOCK_MS = 10 * 60 * 1000L
// Packages already released this run, so a release is not repeated every pass.
private val releasedThisRun: MutableSet<String> = ConcurrentHashMap.newKeySet()
// Packages Monk Mind itself has blocked this run. Each run blocks everything once on its own,
// even if another app (such as a focus mode) had already paused it.
private val blockedThisRun: MutableSet<String> = ConcurrentHashMap.newKeySet()

fun retrieveSecurityLogs() {
    CoroutineScope(Dispatchers.IO).launch { runCatching { DPM.retrieveSecurityLogs(DAR) } }
}

fun setDefaultAffiliationID() {
    if (SP.isDefaultAffiliationIdSet) return
    runCatching {
        DPM.setAffiliationIds(DAR, setOf("SafeHaven_default_affiliation_id"))
        SP.isDefaultAffiliationIdSet = true
    }
}

fun handlePrivilegeChange(context: Context) {
    val activated = Privilege.status.value.activated
    SP.shortcuts = activated
    if (!activated) {
        SP.isDefaultAffiliationIdSet = false
        return
    }
    setDefaultAffiliationID()
    if (Privilege.status.value.device) CoroutineScope(Dispatchers.IO).launch { enforceAllPolicies(context) }
}

suspend fun enforceAllPolicies(context: Context) = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        applyChromiumPolicies()
        enforcePrivateDns(context)
    }

    runCatching { DPM.setUninstallBlocked(DAR, context.packageName, true) }

    // enforceBatterySaverFlags(context)   // DISABLED — caused shutdowns
    // enforceNightLight(context)          // DISABLED — keep normal colors
    // enforceGrayscale(context)           // DISABLED — keep full color
    enforceAnimations(context)
    allowStatusNotification(context)
    squashDeveloperOptions(context)
    applyStrictRestrictions(context)
    enforceStrictWhitelist(context)
}

// Lets the quiet "Monk Mind is on" notification show, so you can see protection is running
// and reach the "Install or update" button.
private fun allowStatusNotification(context: Context) = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val permission = Manifest.permission.POST_NOTIFICATIONS
        val state = DPM.getPermissionGrantState(DAR, context.packageName, permission)
        if (state != DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) {
            DPM.setPermissionGrantState(
                DAR, context.packageName, permission, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
            Log.i(TAG, "Notification permission granted")
            // A notification posted before the grant was dropped, so post it again.
            StatusNotification.invalidate()
            StatusNotification.refresh(context)
        }
    }
}

/**
 * Cheap checks that run every 10 seconds: zero animations, the developer-options lock
 * (only after the seal) and the restriction list. Nothing is written unless something changed.
 */
fun enforceLockState(context: Context) {
    runCatching { Privilege.updateStatus() }
    if (!Privilege.status.value.device) return
    enforceAnimations(context)
    squashDeveloperOptions(context)
    applyStrictRestrictions(context)
}

private fun squashDeveloperOptions(context: Context) = runCatching {
    if (!SP.isSealed) return@runCatching
    val cr = context.contentResolver

    if (Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0) {
        Settings.Global.putInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)
    }
    if (Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) != 0) {
        Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 0)
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun enforcePrivateDns(context: Context) = runCatching {
    val cr = context.contentResolver
    Settings.Global.putString(cr, "private_dns_mode", "hostname")
    // Dynamically loaded from CI/CD Secrets
    Settings.Global.putString(cr, "private_dns_specifier", BuildConfig.PRIVATE_DNS)
    DPM.setGlobalSetting(DAR, "private_dns_mode", "hostname")
    DPM.setGlobalSetting(DAR, "private_dns_specifier", BuildConfig.PRIVATE_DNS)
}

private fun enforceNightLight(context: Context) = runCatching {
    val cr = context.contentResolver
    Settings.Secure.putInt(cr, "night_display_auto_mode", 1)
    Settings.Secure.putInt(cr, "night_display_custom_start_time", 79200000)
    Settings.Secure.putInt(cr, "night_display_custom_end_time", 21600000)
    Settings.Secure.putInt(cr, "night_display_color_temperature", 2500)

    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val isNightTime = hour >= 22 || hour < 6
    if (isNightTime) {
        Settings.Secure.putInt(cr, "night_display_activated", 1)
    }
}

private fun enforceGrayscale(context: Context) = runCatching {
    val cr = context.contentResolver
    val isEnabled = Settings.Secure.getInt(cr, "accessibility_display_daltonizer_enabled", 0) == 1
    val mode = Settings.Secure.getInt(cr, "accessibility_display_daltonizer", -1)

    if (!isEnabled || mode != 0) {
        Settings.Secure.putInt(cr, "accessibility_display_daltonizer", 0)
        Settings.Secure.putInt(cr, "accessibility_display_daltonizer_enabled", 1)
    }
}

private fun enforceAnimations(context: Context) = runCatching {
    val cr = context.contentResolver
    for (key in arrayOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")) {
        if (Settings.Global.getFloat(cr, key, 1.0f) != 0.0f) Settings.Global.putFloat(cr, key, 0.0f)
    }
}

private fun enforceBatterySaverFlags(context: Context) = runCatching {
    val cr = context.contentResolver
    Settings.Global.putString(cr, "battery_saver_constants", BATTERY_SAVER_FLAGS)
    Settings.Global.putString(cr, "battery_saver_device_specific_constants", BATTERY_SAVER_FLAGS)
    Settings.Global.putInt(cr, "automatic_power_save_mode", 0)
    Settings.Global.putInt(cr, "low_power_trigger_level", 100)
    Settings.Global.putInt(cr, "low_power", 1)
    Settings.Global.putInt(cr, "low_power_sticky", 1)
    Settings.Global.putInt(cr, "low_power_sticky_auto_disable_enabled", 0)
}

private fun applyChromiumPolicies() {
    val bundle = Bundle().apply {
        putString("DnsOverHttpsMode", "secure")
        val browserDnsRaw = BuildConfig.BROWSER_PRIVATE_DNS
        val dohTemplate = if (browserDnsRaw.startsWith("http://") || browserDnsRaw.startsWith("https://")) {
            browserDnsRaw
        } else {
            "https://$browserDnsRaw/dns-query"
        }
        putString("DnsOverHttpsTemplates", dohTemplate)
        putString("WebRtcIPHandling", "disable_non_proxied_udp")
        putBoolean("SitePerProcess", true)
        putInt("NetworkPredictionOptions", 2)

        putInt("IncognitoModeAvailability", 1)
        putBoolean("SearchSuggestEnabled", false)
        putBoolean("PromotionalTabsEnabled", false)

        putInt("BrowserSignin", 0)
        putBoolean("SyncDisabled", true)
        putBoolean("PasswordManagerEnabled", false)
        putBoolean("AutofillAddressEnabled", false)
        putBoolean("AutofillCreditCardEnabled", false)

        putBoolean("DisableScreenshots", true)
        putBoolean("MetricsReportingEnabled", false)
        putBoolean("UrlKeyedAnonymizedDataCollectionEnabled", false)

        val blocklistRaw = BuildConfig.URL_BLOCKLIST
        if (blocklistRaw.isNotBlank()) {
            val blocklistArray = blocklistRaw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toTypedArray()

            if (blocklistArray.isNotEmpty()) {
                putStringArray("URLBlocklist", blocklistArray)
                Log.d("ChromiumPolicies", "Injected ${blocklistArray.size} domain blocks into Chromium networking stack.")
            }
        }
    }

    arrayOf("com.android.chrome", "com.brave.browser").forEach { pkg ->
        runCatching { DPM.setApplicationRestrictions(DAR, pkg, bundle) }
    }
}

fun applyStrictRestrictions(context: Context) {
    val desiredRestrictions = buildSet {
        add(UserManager.DISALLOW_ADD_USER)
        add(UserManager.DISALLOW_REMOVE_USER)
        add(UserManager.DISALLOW_SAFE_BOOT)
        add(UserManager.DISALLOW_FACTORY_RESET)
        add(UserManager.DISALLOW_CONFIG_DATE_TIME)
        add(UserManager.DISALLOW_ADD_MANAGED_PROFILE)
        // --- HARDENING: close the app-management + install loopholes ---
        add(UserManager.DISALLOW_APPS_CONTROL)              // can't enable/disable/force-stop apps in Settings
        add(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)   // no sideloading APKs
        add(UserManager.DISALLOW_UNINSTALL_APPS)            // can't uninstall to bypass
        // Install lock: nothing can be installed or updated, except in an update window.
        if (!installsAllowedNow(context)) add(UserManager.DISALLOW_INSTALL_APPS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            add(UserManager.DISALLOW_USER_SWITCH)
            if (Locale.getDefault().language == "de") {
                add(UserManager.DISALLOW_CONFIG_LOCALE)
                Log.d("Lockdown", "German verified. Locale locked.")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
        }
        if (SP.isSealed) {
            val cr = context.contentResolver
            val devOptionsOff = Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 0
            val adbOff = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 0

            if (devOptionsOff && adbOff) {
                add(UserManager.DISALLOW_DEBUGGING_FEATURES)
            }
        }
    }

    // Only touch what actually changed. Re-sending every rule every 10 seconds makes
    // Android rewrite its policy file each time.
    runCatching {
        val bundle = DPM.getUserRestrictions(DAR)
        val active = bundle.keySet().filter { bundle.getBoolean(it) }.toSet()
        (active - desiredRestrictions).forEach {
            runCatching { DPM.clearUserRestriction(DAR, it) }
            Log.i(TAG, "Restriction cleared: $it")
        }
        (desiredRestrictions - active).forEach {
            runCatching { DPM.addUserRestriction(DAR, it) }
            Log.i(TAG, "Restriction set: $it")
        }
    }.onFailure { Log.e(TAG, "Failed to sync restrictions", it) }
}

private fun hideLauncherIcon(context: Context) = runCatching {
    val componentName = ComponentName(context, "app.anonymous.safehaven.MainActivity")
    val pm = context.packageManager
    if (pm.getComponentEnabledSetting(componentName) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
        pm.setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }
}

/**
 * The blocking pass. Runs right after an install, every 2 minutes, and whenever the app list changes.
 * It only asks Android to change what is actually wrong, so a normal pass writes nothing.
 */
suspend fun enforceStrictWhitelist(context: Context) = withContext(Dispatchers.IO) {
    // Always re-read the Device Owner flag. A stale "false" once silently skipped all blocking.
    runCatching { Privilege.updateStatus() }
    if (!Privilege.status.value.device) return@withContext

    enforceMutex.withLock {
        Privilege.lockdownActive.value = true
        try {
            hideLauncherIcon(context)
            val pm = context.packageManager
            val self = context.packageName
            val whitelist = AppWhitelist.getWhitelist()

            val installed = installedApps(pm)
            if (installed.isEmpty()) return@withLock // never act on a failed read
            val launcherApps = activityPackages(pm, Intent.CATEGORY_LAUNCHER)
            val now = SystemClock.elapsedRealtime()
            val plan = planBlocking(
                installed = installed.map {
                    AppEntry(
                        pkg = it.packageName,
                        system = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        paused = (it.flags and ApplicationInfo.FLAG_SUSPENDED) != 0
                    )
                },
                whitelist = whitelist,
                blocklist = SYSTEM_BLOCKLIST.asList(),
                essential = ESSENTIAL_SYSTEM_APPS,
                homeApps = activityPackages(pm, Intent.CATEGORY_HOME),
                launcherApps = launcherApps,
                self = self,
                blockedThisRun = blockedThisRun,
                releasedThisRun = releasedThisRun,
                canTryBlock = { now >= (retryBlockAfter[it] ?: 0L) },
                openDuringSetup = if (inSetupMode(context)) setOf(SETUP_BROWSER) else emptySet()
            )
            val toBlock = plan.toBlock
            val toRelease = plan.toRelease

            // Block only what is not blocked yet.
            if (toBlock.isNotEmpty()) {
                runCatching {
                    val failed: Array<String>? = DPM.setPackagesSuspended(DAR, toBlock.toTypedArray(), true)
                    val failedSet = failed?.toSet() ?: emptySet()
                    failedSet.forEach { retryBlockAfter[it] = now + RETRY_BLOCK_MS }
                    blockedThisRun.addAll(toBlock.filter { it !in failedSet })
                    releasedThisRun.removeAll(toBlock.toSet())
                    Log.i(TAG, "Blocked: ${toBlock.filter { it !in failedSet }} could not block: $failedSet")
                }.onFailure { Log.e(TAG, "Block step failed", it) }
            }

            // Release anything paused that should not be (an app on your list, or a phone part
            // an older rule paused).
            if (toRelease.isNotEmpty()) {
                runCatching {
                    DPM.setPackagesSuspended(DAR, toRelease.toTypedArray(), false)
                    releasedThisRun.addAll(toRelease)
                    blockedThisRun.removeAll(toRelease.toSet())
                    Log.i(TAG, "Released: $toRelease")
                }.onFailure { Log.e(TAG, "Release step failed", it) }
            }
        } finally {
            Privilege.lockdownActive.value = false
        }
    }
}

private fun installedApps(pm: PackageManager): List<ApplicationInfo> = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION") pm.getInstalledApplications(0)
    }
}.getOrDefault(emptyList())

private fun activityPackages(pm: PackageManager, category: String): Set<String> = runCatching {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
    // Both flags = apps that run before unlock and apps that only run after it.
    // No hidden or disabled parts: those are system pieces such as the file picker.
    val flags = PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE
    val found = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
    } else {
        @Suppress("DEPRECATION") pm.queryIntentActivities(intent, flags)
    }
    found.mapNotNull { it.activityInfo?.packageName }.toSet()
}.getOrDefault(emptySet())

internal data class AppEntry(val pkg: String, val system: Boolean, val paused: Boolean)
internal data class BlockPlan(val shouldBlock: Set<String>, val toBlock: List<String>, val toRelease: List<String>)

/**
 * Pure decision logic (no Android calls), so it can be tested on its own.
 * - Block: apps you installed that are not on your list; built-in distractions on the block list;
 *   any other app with a home-screen icon that is not on your list and not essential.
 * - Never block: Monk Mind itself, anything on your list, essential phone apps, home screens.
 * - During setup only (USB debugging on, not sealed): Chrome stays open for web sign-ins.
 * - Release: anything paused that should not be. Skipped when the icon lookup came back
 *   empty (a failed read), so a glitch can never open the blocked apps.
 */
internal fun planBlocking(
    installed: List<AppEntry>,
    whitelist: Set<String>,
    blocklist: List<String>,
    essential: Set<String>,
    homeApps: Set<String>,
    launcherApps: Set<String>,
    self: String,
    blockedThisRun: Set<String>,
    releasedThisRun: Set<String>,
    canTryBlock: (String) -> Boolean,
    openDuringSetup: Set<String> = emptySet()
): BlockPlan {
    val installedNames = installed.map { it.pkg }.toHashSet()
    val pausedNow = installed.filter { it.paused }.map { it.pkg }.toHashSet()

    val shouldBlock = HashSet<String>()
    installed.filter { !it.system && it.pkg !in whitelist }.mapTo(shouldBlock) { it.pkg }
    blocklist.filterTo(shouldBlock) { it !in whitelist && it in installedNames }
    launcherApps.filterTo(shouldBlock) {
        it in installedNames && it !in whitelist && it !in essential && it !in homeApps
    }
    shouldBlock.remove(self)
    shouldBlock.removeAll(openDuringSetup)

    val toBlock = shouldBlock
        .filter { !(it in pausedNow && it in blockedThisRun) && canTryBlock(it) }
        .sorted()
    val toRelease = if (launcherApps.isEmpty()) emptyList() else pausedNow
        .filter { it !in shouldBlock && it != self && it !in releasedThisRun }
        .sorted()
    return BlockPlan(shouldBlock, toBlock, toRelease)
}

// ---------------------------------------------------------------------------------------------
// Setup mode
// Before the seal, while USB debugging is on, installing and Chrome stay open so apps that sign
// in through a web page (like SoundCloud) can be set up. Turning USB debugging off ends it at once.
// ---------------------------------------------------------------------------------------------

const val SETUP_BROWSER = "com.android.chrome"

fun inSetupMode(context: Context): Boolean = !SP.isSealed && runCatching {
    Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) != 0
}.getOrDefault(false)

// ---------------------------------------------------------------------------------------------
// Install lock
// The Play Store cannot be paused (Android protects it, because it is also the phone's app
// safety checker), so Monk Mind locks INSTALLING instead. Android itself then refuses every
// install and update, even if Monk Mind were not running. The lock opens only in update windows.
// ---------------------------------------------------------------------------------------------

const val UPDATE_WINDOW_MS = 15 * 60 * 1000L

/**
 * Pure rule (no Android calls), so it can be tested on its own.
 * Installing and updating apps is allowed only:
 * - while USB debugging is on, before the seal (setup and testing);
 * - during an "Install or update" window from the Monk Mind notification (15 minutes);
 * - while the phone is charging with the screen off (when the Play Store auto-updates, usually overnight).
 */
internal fun installsAllowed(
    usbDebuggingOn: Boolean,
    sealed: Boolean,
    nowMs: Long,
    windowUntilMs: Long,
    screenOn: Boolean,
    pluggedIn: Boolean
): Boolean {
    if (usbDebuggingOn && !sealed) return true
    if (nowMs < windowUntilMs) return true
    if (!screenOn && pluggedIn) return true
    return false
}

/** Reads the phone's state. Anything that cannot be read counts as "locked". */
fun installsAllowedNow(context: Context): Boolean {
    val cr = context.contentResolver
    val usbDebuggingOn = runCatching {
        Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) != 0
    }.getOrDefault(false)
    val screenOn = runCatching {
        context.getSystemService(PowerManager::class.java)?.isInteractive
    }.getOrNull() ?: true
    val pluggedIn = runCatching {
        val battery = ContextCompat.registerReceiver(
            context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }.getOrDefault(false)
    return installsAllowed(
        usbDebuggingOn, SP.isSealed, System.currentTimeMillis(), SP.updateWindowUntil, screenOn, pluggedIn
    )
}

/** Fast path for screen and charger changes: sets only the install lock. */
fun enforceInstallLock(context: Context) {
    if (!Privilege.status.value.device) return
    runCatching {
        val lockWanted = !installsAllowedNow(context)
        val lockOn = DPM.getUserRestrictions(DAR).getBoolean(UserManager.DISALLOW_INSTALL_APPS)
        if (lockWanted && !lockOn) {
            DPM.addUserRestriction(DAR, UserManager.DISALLOW_INSTALL_APPS)
            Log.i(TAG, "Install lock on")
        } else if (!lockWanted && lockOn) {
            DPM.clearUserRestriction(DAR, UserManager.DISALLOW_INSTALL_APPS)
            Log.i(TAG, "Install lock off (update window)")
        }
    }.onFailure { Log.e(TAG, "Install lock failed", it) }
}
