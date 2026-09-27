package app.orionmd.btvoicetype

import android.content.Context
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Shared OrionMD activation-key gate.
 *
 * Rules (per OrionMD standing design):
 *  - No key: 3-day free trial from first launch, then locked until a key is entered.
 *  - Key entered: verified once against the shared backend. After that, the app is allowed to
 *    run offline for up to 14 days since the last successful online verification. Past 14 days
 *    with no successful re-check, the app locks itself out until it can verify again.
 *  - Once claimed by this app, a key cannot be reused to activate a different OrionMD app
 *    (enforced server-side).
 */
object ActivationGate {

    private const val PREFS = "orionmd_activation"
    private const val KEY_FIRST_LAUNCH = "first_launch_ms"
    private const val KEY_ACTIVATION_KEY = "activation_key"
    private const val KEY_LAST_VERIFIED = "last_verified_ms"
    private const val KEY_ACTIVATED = "activated"

    private const val APP_ID = "btvoicetype"
    private const val BACKEND_URL =
        "https://script.google.com/macros/s/AKfycbyUNYqPBX7XBHBu19aHw25sumXoNA1kT9ohDdOKXjFcM77LauF3TrugqajntUddRC68/exec"

    private const val TRIAL_DAYS = 3L
    private const val OFFLINE_GRACE_DAYS = 14L
    private val DAY_MS = 24L * 60 * 60 * 1000

    enum class Status { TRIAL_ACTIVE, TRIAL_EXPIRED, GRACE_ACTIVE, GRACE_EXPIRED }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Call once at app/service startup to record first-launch time. */
    fun ensureFirstLaunchRecorded(context: Context) {
        val p = prefs(context)
        if (!p.contains(KEY_FIRST_LAUNCH)) {
            p.edit().putLong(KEY_FIRST_LAUNCH, System.currentTimeMillis()).apply()
        }
    }

    fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"

    fun currentStatus(context: Context): Status {
        val p = prefs(context)
        val activated = p.getBoolean(KEY_ACTIVATED, false)
        return if (activated) {
            val lastVerified = p.getLong(KEY_LAST_VERIFIED, 0L)
            val age = System.currentTimeMillis() - lastVerified
            if (age <= OFFLINE_GRACE_DAYS * DAY_MS) Status.GRACE_ACTIVE else Status.GRACE_EXPIRED
        } else {
            val firstLaunch = p.getLong(KEY_FIRST_LAUNCH, System.currentTimeMillis())
            val age = System.currentTimeMillis() - firstLaunch
            if (age <= TRIAL_DAYS * DAY_MS) Status.TRIAL_ACTIVE else Status.TRIAL_EXPIRED
        }
    }

    /** True if dictation/keyboard use is currently allowed. */
    fun isUsageAllowed(context: Context): Boolean {
        return when (currentStatus(context)) {
            Status.TRIAL_ACTIVE, Status.GRACE_ACTIVE -> true
            Status.TRIAL_EXPIRED, Status.GRACE_EXPIRED -> false
        }
    }

    fun statusLabel(context: Context): String {
        val p = prefs(context)
        return when (currentStatus(context)) {
            Status.TRIAL_ACTIVE -> {
                val firstLaunch = p.getLong(KEY_FIRST_LAUNCH, System.currentTimeMillis())
                val daysLeft = TRIAL_DAYS - (System.currentTimeMillis() - firstLaunch) / DAY_MS
                "Free trial — ${daysLeft.coerceAtLeast(0)} day(s) left"
            }
            Status.TRIAL_EXPIRED -> "Trial expired — enter an activation key to continue"
            Status.GRACE_ACTIVE -> "Activated"
            Status.GRACE_EXPIRED -> "Activation lapsed — connect to the internet to re-verify"
        }
    }

    /**
     * Verify a key against the shared backend. Calls [callback] on a background thread with
     * (success, message).
     */
    fun activate(context: Context, key: String, callback: (Boolean, String) -> Unit) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            callback(false, "Enter a key first")
            return
        }
        Executors.newSingleThreadExecutor().execute {
            try {
                val url = URL(
                    "$BACKEND_URL?key=${trimmed}&device=${deviceId(context)}&app=$APP_ID"
                )
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.requestMethod = "GET"
                val response = conn.inputStream.bufferedReader().readText().trim()
                conn.disconnect()

                when (response) {
                    "OK" -> {
                        prefs(context).edit()
                            .putBoolean(KEY_ACTIVATED, true)
                            .putString(KEY_ACTIVATION_KEY, trimmed)
                            .putLong(KEY_LAST_VERIFIED, System.currentTimeMillis())
                            .apply()
                        callback(true, "Activated successfully")
                    }
                    "INVALID" -> callback(false, "That key is not valid")
                    "USED" -> callback(false, "That key has already been used")
                    else -> callback(false, "Server error, try again later")
                }
            } catch (e: Exception) {
                callback(false, "No internet connection — try again when online")
            }
        }
    }

    /** Re-verify a previously activated key in the background to refresh the 14-day grace clock. */
    fun silentReverify(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean(KEY_ACTIVATED, false)) return
        val key = p.getString(KEY_ACTIVATION_KEY, null) ?: return
        activate(context, key) { _, _ -> }
    }
}
