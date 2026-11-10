package app.xingmu.aerial.cast

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import app.xingmu.aerial.BuildConfig

/**
 * Persisted 星投屏 agent settings, kept apart from Lite's own prefs so a restore of the launcher
 * settings never wipes the device token.
 *
 * The token is a credential: it is stored in the app's private SharedPreferences and never put in a
 * log line or in the phone page.
 */
object CastConfig {
    private const val PREFS = "cast"

    private lateinit var prefs: SharedPreferences

    /** Base URL of the backend, without a trailing slash. Empty means "not configured". */
    var backendUrl: String = ""
        private set

    var deviceId: String = ""
        private set

    var deviceToken: String = ""
        private set

    var deviceName: String = ""
        private set

    /** 127.0.0.1 port of the TV's adbd; 5555 is what every box with "ADB 调试" uses. */
    var adbPort: Int = 5555
        private set

    /** Whether the agent should run at boot. On by default once a URL is configured. */
    var enabled: Boolean = true
        private set

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        backendUrl = (prefs.getString("backend_url", null) ?: BuildConfig.CAST_BACKEND_URL).trimEnd('/')
        deviceId = prefs.getString("device_id", null) ?: defaultDeviceId(context)
        deviceToken = prefs.getString("device_token", null).orEmpty()
        deviceName = prefs.getString("device_name", null) ?: defaultDeviceName()
        enabled = prefs.getBoolean("enabled", true)
        adbPort = prefs.getInt("adb_port", 5555).takeIf { it in 1..65535 } ?: 5555
    }

    val configured: Boolean get() = backendUrl.isNotEmpty()

    fun setBackendUrl(value: String) {
        backendUrl = value.trim().trimEnd('/')
        prefs.edit().putString("backend_url", backendUrl).apply()
    }

    fun setDeviceName(value: String) {
        val clean = value.trim().ifEmpty { defaultDeviceName() }
        deviceName = clean
        prefs.edit().putString("device_name", clean).apply()
    }

    fun setAdbPort(port: Int) {
        if (port !in 1..65535) return
        adbPort = port
        prefs.edit().putInt("adb_port", port).apply()
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        prefs.edit().putBoolean("enabled", value).apply()
    }

    fun storeToken(token: String) {
        deviceToken = token
        prefs.edit().putString("device_token", token).apply()
    }

    /** Bumping this makes the backend treat the TV as a new device (e.g. after a factory reset). */
    fun rotateIdentity() {
        deviceId = defaultDeviceId(null)
        deviceToken = ""
        prefs.edit().putString("device_id", deviceId).remove("device_token").apply()
    }

    @SuppressLint("HardwareIds")
    private fun defaultDeviceId(context: Context?): String {
        val raw = if (context != null) {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } else {
            System.currentTimeMillis().toString(16)
        }
        val tail = (raw ?: "").lowercase().filter { it.isLetterOrDigit() }.takeLast(12)
        return "lite-" + if (tail.isEmpty()) System.currentTimeMillis().toString(16) else tail
    }

    private fun defaultDeviceName(): String {
        val model = android.os.Build.MODEL.orEmpty().trim()
        return if (model.isEmpty()) "客厅电视" else "星幕-$model"
    }
}
