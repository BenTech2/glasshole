package com.glasshole.glassee1

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.glasshole.glass.sdk.GlassPluginConstants

/**
 * Optional accessibility service that lets the user swipe down on the
 * Glass side touchpad from the stock Glass launcher ("clock" screen)
 * to send the display to standby — the gesture EE1/XE had natively but
 * EE2's Glass launcher doesn't implement.
 *
 * How it works:
 *  - Glass EE2 maps the side-touchpad swipe-down to KEYCODE_BACK
 *  - An accessibility service with `canRequestFilterKeyEvents` sees every
 *    key event before the focused activity does
 *  - We only act when the current foreground app is the Glass launcher,
 *    so we don't break the Back key anywhere else
 *  - To actually put the display to sleep we reuse the device plugin's
 *    SLEEP_NOW (which briefly drops SCREEN_OFF_TIMEOUT) because EE2
 *    doesn't ship GLOBAL_ACTION_LOCK_SCREEN (API 28+) or a user-
 *    callable PowerManager.goToSleep
 *
 * User enables this once under Settings ▸ Accessibility ▸ GlassHole
 * Swipe-Down Sleep.
 */
class SleepAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GlassHoleSleepA11y"
        private const val GLASS_LAUNCHER_PKG = "com.google.android.glass.launcher"
    }

    @Volatile private var currentPackage: String = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            currentPackage = event.packageName?.toString() ?: ""
        }
    }

    override fun onInterrupt() { /* no-op */ }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Only act on KEYCODE_BACK (swipe-down on EE2 touchpad) pressed
        // while the Glass launcher "clock" face is the foreground app.
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return false
        if (currentPackage != GLASS_LAUNCHER_PKG) return false

        Log.i(TAG, "Swipe-down on Glass launcher — triggering sleep")
        triggerSleep()
        return true  // consume so the launcher doesn't also get it
    }

    private fun triggerSleep() {
        // Prefer the live BT service's public hook — uses the AIDL binding
        // when available. If the service isn't up (shouldn't happen in
        // normal use, but safe to fall back), broadcast to the device
        // plugin directly.
        val svc = BluetoothListenerService.instance
        if (svc != null) {
            svc.sleepGlass()
            return
        }
        val intent = Intent(GlassPluginConstants.ACTION_MESSAGE_FROM_PHONE).apply {
            putExtra(GlassPluginConstants.EXTRA_PLUGIN_ID, "device")
            putExtra(GlassPluginConstants.EXTRA_MESSAGE_TYPE, "SLEEP_NOW")
            putExtra(GlassPluginConstants.EXTRA_PAYLOAD, "")
            `package` = "com.glasshole.plugin.device.glass"
        }
        sendBroadcast(intent)
    }
}
