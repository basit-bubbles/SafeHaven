package app.anonymous.safehaven

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import android.util.Log
import androidx.core.content.edit
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Lightweight SharedPreferences wrapper using device-protected storage.
 */
class SharedPrefs(context: Context) {

    val sharedPrefs: SharedPreferences

    init {
        val deContext = context.createDeviceProtectedStorageContext()
        runCatching {
            if (context.getSystemService(UserManager::class.java)?.isUserUnlocked == true) {
                deContext.moveSharedPreferencesFrom(context, "data")
            }
        }.onFailure { Log.e("SharedPrefs", "Storage migration error", it) }
        sharedPrefs = deContext.getSharedPreferences("data", Context.MODE_PRIVATE)
    }

    var isDefaultAffiliationIdSet by BooleanSharedPref("default_affiliation_id_set")
    var shortcuts by BooleanSharedPref("shortcuts")
    var appWhitelist by StringSharedPref("app_whitelist")
    var protectInstall by BooleanSharedPref("protect_install", true)
    var hasRebootedSinceSetup by BooleanSharedPref("has_rebooted_since_setup", false)

    // Monk Mind lock ("seal"). Set only when the phone restarts with Developer options
    // switched OFF completely. Any restart while Developer options is on is a test restart.
    // New key on purpose, so state from older builds can never seal a phone by accident.
    var isSealed: Boolean
        get() = sharedPrefs.getBoolean("monk_sealed_v2", false)
        set(value) { sharedPrefs.edit().putBoolean("monk_sealed_v2", value).commit() }

    // End time (wall clock, ms) of an "Update apps" window opened from the Monk Mind notification.
    var updateWindowUntil: Long
        get() = sharedPrefs.getLong("monk_update_window_until", 0L)
        set(value) { sharedPrefs.edit().putLong("monk_update_window_until", value).commit() }

    private inner class BooleanSharedPref(val key: String, val defValue: Boolean = false) : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = sharedPrefs.getBoolean(key, defValue)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) = sharedPrefs.edit { putBoolean(key, value) }
    }

    private inner class StringSharedPref(val key: String) : ReadWriteProperty<Any?, String?> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String? = sharedPrefs.getString(key, null)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String?) = sharedPrefs.edit { putString(key, value) }
    }
}