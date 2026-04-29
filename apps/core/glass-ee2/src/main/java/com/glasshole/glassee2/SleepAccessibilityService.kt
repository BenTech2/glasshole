package com.glasshole.glassee2

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.glasshole.glass.sdk.GlassPluginConstants

/**
 * Optional accessibility service. Two unrelated jobs both required global
 * key handling, so they live together to avoid asking the user to enable
 * two separate services:
 *
 * 1. Swipe-down sleep on the stock Glass launcher.
 *    Glass EE2 maps the touchpad swipe-down gesture to KEYCODE_BACK.
 *    We watch for it while the foreground app is the stock launcher
 *    and trigger the device plugin's SLEEP_NOW so the user gets the
 *    timeline-card sleep gesture EE1/XE shipped natively.
 *
 * 2. Camera key — global capture + hold-to-record.
 *    From sleep / home / anywhere, a short press fires a still capture
 *    via ACTION_CAMERA_BUTTON; press-and-hold past the long-press
 *    threshold fires ACTION_RECORD_HOLD which opens the camera plugin
 *    straight into recording mode, and the matching keyUp fires
 *    ACTION_STOP_RECORDING which saves the clip. Glass's InputDispatcher
 *    eats the wake-key event before any normal listener sees it, so the
 *    accessibility-service path is the only userspace hook that catches
 *    the press from sleep.
 *
 * User enables this once under Settings ▸ Accessibility ▸ GlassHole
 * Swipe-Down Sleep.
 */
class SleepAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GlassHoleSleepA11y"
        private const val GLASS_LAUNCHER_PKG = "com.google.android.glass.launcher"
        private const val CAMERA_PLUGIN_PKG = "com.glasshole.plugin.camera2.glass"
        private const val LONG_PRESS_THRESHOLD_MS = 500L
    }

    @Volatile private var currentPackage: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraKeyDownTime = 0L
    @Volatile private var longPressFired = false
    private val longPressTrigger = Runnable {
        longPressFired = true
        Log.i(TAG, "Camera key long-press fired — launching record")
        launchCamera(CameraAction.RECORD_HOLD)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Belt-and-suspenders: the XML config sets
        // flagRequestFilterKeyEvents, but force it on at runtime too in
        // case a system that ignored the XML flag still respects the
        // programmatic info update. Without this flag, onKeyEvent never
        // fires no matter how many capabilities the service holds.
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = info
        Log.i(TAG, "Service connected — flags=0x${info.flags.toString(16)}, capabilities=0x${info.capabilities.toString(16)}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            currentPackage = event.packageName?.toString() ?: ""
        }
    }

    override fun onInterrupt() { /* no-op */ }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_CAMERA -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            cameraKeyDownTime = System.currentTimeMillis()
                            longPressFired = false
                            mainHandler.removeCallbacks(longPressTrigger)
                            mainHandler.postDelayed(longPressTrigger, LONG_PRESS_THRESHOLD_MS)
                        }
                        return true  // consume globally
                    }
                    KeyEvent.ACTION_UP -> {
                        mainHandler.removeCallbacks(longPressTrigger)
                        if (longPressFired) {
                            Log.i(TAG, "Camera key released after long-press — stopping record")
                            launchCamera(CameraAction.STOP_RECORDING)
                        } else {
                            Log.i(TAG, "Camera key short-press — launching still capture")
                            launchCamera(CameraAction.STILL)
                        }
                        longPressFired = false
                        return true
                    }
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (event.action != KeyEvent.ACTION_DOWN) return false
                if (currentPackage != GLASS_LAUNCHER_PKG) return false
                Log.i(TAG, "Swipe-down on Glass launcher — triggering sleep")
                triggerSleep()
                return true
            }
        }
        return false
    }

    private enum class CameraAction { STILL, RECORD_HOLD, STOP_RECORDING }

    private fun launchCamera(action: CameraAction) {
        val actionString = when (action) {
            CameraAction.STILL -> Intent.ACTION_CAMERA_BUTTON
            CameraAction.RECORD_HOLD -> "com.glasshole.plugin.camera2.glass.action.RECORD_HOLD"
            CameraAction.STOP_RECORDING -> "com.glasshole.plugin.camera2.glass.action.STOP_RECORDING"
        }
        val intent = Intent(actionString).apply {
            setPackage(CAMERA_PLUGIN_PKG)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Camera launch ($action) failed: ${e.message}")
        }
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
