package app.xingmu.aerial.cast

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * The only way a plain app on these TVs can drive the system without root or ADB: an
 * accessibility service performing global actions (back, home, recents, notifications).
 *
 * It deliberately does not read window content (see res/xml/cast_accessibility.xml), so enabling
 * it grants no access to what is on screen — only the four global actions a remote's keys map to.
 */
class CastA11yService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isNotEmpty() && pkg != foreground) foreground = pkg
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: CastA11yService? = null

    /** Foreground package seen by the service; the agent reports it as current_activity. */
    @Volatile var foreground: String = ""
        private set

        val connected: Boolean get() = instance != null

        fun perform(globalAction: Int): Boolean {
            // performGlobalAction arrived in API 16; Aerial Lite still ships to 4.0/4.1 (14/15),
            // where the D-pad path through adbd is the only option.
            if (android.os.Build.VERSION.SDK_INT < 16) return false
            val service = instance ?: return false
            return runCatching { service.performGlobalAction(globalAction) }.getOrDefault(false)
        }
    }
}
