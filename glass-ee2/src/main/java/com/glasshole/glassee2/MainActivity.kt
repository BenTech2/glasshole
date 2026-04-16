package com.glasshole.glassee2

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.glasshole.glass.sdk.InstalledPluginScanner
import com.glasshole.glass.sdk.InstalledPluginScanner.InstalledPlugin

class MainActivity : Activity(), BluetoothListenerService.MessageListener {

    private lateinit var statusText: TextView
    private lateinit var launcherPane: LinearLayout
    private lateinit var carouselRow: LinearLayout
    private lateinit var focusedLabel: TextView
    private lateinit var carouselHint: TextView

    private val plugins = mutableListOf<InstalledPlugin>()
    private var focusedIndex = 0

    private var service: BluetoothListenerService? = null
    private var bound = false

    private var downX = 0f
    private var downY = 0f
    private var swiped = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as BluetoothListenerService.LocalBinder
            service = localBinder.getService()
            service?.messageListener = this@MainActivity
            bound = true
            if (service?.isPhoneConnected == true) onConnectionStateChanged(true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        launcherPane = findViewById(R.id.launcherPane)
        carouselRow = findViewById(R.id.carouselRow)
        focusedLabel = findViewById(R.id.focusedLabel)
        carouselHint = findViewById(R.id.carouselHint)

        val btIntent = Intent(this, BluetoothListenerService::class.java)
        startForegroundService(btIntent)
        bindService(btIntent, connection, Context.BIND_AUTO_CREATE)

        startService(Intent(this, PluginHostService::class.java))

        loadPlugins()
        renderCarousel()
    }

    override fun onResume() {
        super.onResume()
        loadPlugins()
        renderCarousel()
    }

    private fun loadPlugins() {
        plugins.clear()
        plugins.addAll(InstalledPluginScanner.scan(this))
        if (focusedIndex >= plugins.size) focusedIndex = 0
    }

    private fun renderCarousel() {
        carouselRow.removeAllViews()

        if (plugins.isEmpty()) {
            focusedLabel.text = "No plugins installed"
            carouselHint.text = "Install plugins from the phone APK Manager"
            return
        }

        val n = plugins.size
        val prevIdx = (focusedIndex - 1 + n) % n
        val nextIdx = (focusedIndex + 1) % n

        if (n >= 2) addIconSlot(plugins[prevIdx], focused = false)
        addIconSlot(plugins[focusedIndex], focused = true)
        if (n >= 3) addIconSlot(plugins[nextIdx], focused = false)

        focusedLabel.text = plugins[focusedIndex].label
        carouselHint.text = "Swipe · Tap"
    }

    private fun addIconSlot(plugin: InstalledPlugin, focused: Boolean) {
        val size = if (focused) dp(90) else dp(52)
        val margin = dp(12)

        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                leftMargin = margin
                rightMargin = margin
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (plugin.icon != null) setImageDrawable(plugin.icon)
            alpha = if (focused) 1.0f else 0.45f
        }
        carouselRow.addView(icon)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (handleTouchpad(event)) return true
        return super.onGenericMotionEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (handleTouchpad(event)) return true
        return super.onTouchEvent(event)
    }

    // EE2 touchpad translates gestures into synthetic keycodes before
    // onTouchEvent sees them — TAB for swipe (shift=back), DPAD_CENTER /
    // ENTER for tap, BACK for swipe-down.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_TAB -> {
                if (plugins.isEmpty()) return true
                focusedIndex = if (event?.isShiftPressed == true) {
                    (focusedIndex - 1 + plugins.size) % plugins.size
                } else {
                    (focusedIndex + 1) % plugins.size
                }
                renderCarousel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                launchFocusedPlugin()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleTouchpad(event: MotionEvent?): Boolean {
        event ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                swiped = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (dy > 80 && Math.abs(dy) > Math.abs(dx)) {
                    finish()
                    return true
                }
                if (!swiped && Math.abs(dx) > 35) {
                    swiped = true
                    if (plugins.isNotEmpty()) {
                        focusedIndex = if (dx > 0) {
                            (focusedIndex + 1) % plugins.size
                        } else {
                            (focusedIndex - 1 + plugins.size) % plugins.size
                        }
                        renderCarousel()
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!swiped) {
                    launchFocusedPlugin()
                    return true
                }
            }
        }
        return false
    }

    private fun launchFocusedPlugin() {
        val plugin = plugins.getOrNull(focusedIndex) ?: return
        try { startActivity(plugin.launchIntent) } catch (_: Exception) {}
    }

    override fun onMessageReceived(message: String) { /* no-op */ }

    override fun onConnectionStateChanged(connected: Boolean) {
        runOnUiThread {
            if (connected) {
                statusText.text = "Phone connected"
                statusText.setTextColor(0xFF43A047.toInt())
            } else {
                statusText.text = "Waiting for phone..."
                statusText.setTextColor(0xFFBBBBBB.toInt())
            }
        }
    }

    override fun onDestroy() {
        if (bound) {
            service?.messageListener = null
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}
