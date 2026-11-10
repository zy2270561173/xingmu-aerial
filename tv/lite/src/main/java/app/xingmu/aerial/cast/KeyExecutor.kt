package app.xingmu.aerial.cast

import android.accessibilityservice.AccessibilityService
import android.content.Context

/**
 * One place for "press this key on the TV", shared by the WebSocket command channel and the P2P
 * channel: accessibility for the four global actions, adbd for every real key code.
 */
object KeyExecutor {
    /**
     * The phone sends Android key names (DPAD_CENTER, HOME, MEDIA_PLAY_PAUSE); older clients sent
     * short names (ok, home). Normalise both instead of only matching the short ones, which is why
     * every D-pad button except HOME used to be reported as an unknown key.
     */
    fun keyCodeFor(name: String): Int {
        var key = name.trim().lowercase()
        if (key.startsWith("keycode_")) key = key.removePrefix("keycode_")
        if (key.startsWith("dpad_")) key = key.removePrefix("dpad_")
        return when (key) {
            "up" -> 19
            "down" -> 20
            "left" -> 21
            "right" -> 22
            "ok", "center", "enter", "select" -> 23
            "back" -> 4
            "home" -> 3
            "menu" -> 82
            "recents", "app_switch" -> 187
            "volume_up" -> 24
            "volume_down" -> 25
            "volume_mute", "mute" -> 164
            "power" -> 26
            "play_pause", "media_play_pause", "headset_hook" -> 85
            "media_play" -> 126
            "media_pause" -> 127
            "media_stop" -> 86
            "media_next" -> 87
            "media_previous" -> 88
            "media_rewind" -> 89
            "media_fast_forward" -> 90
            else -> 0
        }
    }

    fun press(context: Context?, name: String, code: Int, labelled: String): Pair<Boolean, String> {
        val global = when (code) {
            4 -> AccessibilityService.GLOBAL_ACTION_BACK
            3 -> AccessibilityService.GLOBAL_ACTION_HOME
            187 -> AccessibilityService.GLOBAL_ACTION_RECENTS
            83 -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            else -> 0
        }
        // performGlobalAction is API 16; Aerial Lite still ships to Android 4.0/4.1.
        if (global != 0 && android.os.Build.VERSION.SDK_INT >= 16 && CastA11yService.connected &&
            CastA11yService.perform(global)
        ) {
            return true to "已发送按键：$labelled"
        }
        if (context != null) {
            val sent = runCatching { LegacyAdbClient.sendKey(context, CastConfig.adbPort, code) }
                .getOrDefault(false)
            if (sent) return true to "已通过 ADB 发送按键：$labelled"
        }
        // Last resort: the `input` binary works on the 4.4 box even when the app cannot reach adbd
        // (that is why only HOME used to work: it went through the accessibility global action).
        if (pressByInput(code)) return true to "已通过 input 注入按键：$labelled"
        return false to "按键发送失败：请开启无障碍（返回/主页/最近任务），或在电视上启用 ADB TCP（端口 " +
            CastConfig.adbPort + "）"
    }

    /** Runs Android's own `input keyevent` in-process, no adbd needed. */
    private fun pressByInput(code: Int): Boolean = runCatching {
        val process = ProcessBuilder("input", "keyevent", code.toString())
            .redirectErrorStream(true)
            .start()
        process.waitFor()
        process.exitValue() == 0
    }.getOrDefault(false)
}
