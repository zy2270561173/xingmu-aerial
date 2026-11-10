package app.xingmu.aerial.cast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the agent at boot. A launcher normally starts on its own because the system launches the
 * HOME activity, but on firmware that keeps its own desktop (or when Aerial Lite is not the chosen
 * Home), nothing would ever start this app — and then the TV is invisible to the phone.
 */
class CastBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        CastConfig.init(context)
        if (CastConfig.enabled && CastConfig.configured) {
            runCatching { CastService.start(context) }
        }
    }
}
