package com.glasshole.glassxe

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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

    // Gesture tracking
    private var downX = 0f
    private var downY = 0f
    private var swiped = false
    private var longPressFired = false
    private val longPressHandler = Handler()
    private val longPressRunnable = Runnable {
        longPressFired = true
        showPluginDetails()
    }

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
        startService(btIntent)
        bindService(btIntent, connection, Context.BIND_AUTO_CREATE)

        startService(Intent(this, PluginHostService::class.java))

        loadPlugins()
        renderCarousel()
    }

    override fun onResume() {
        super.onResume()
        // Pick up freshly installed or uninstalled plugins.
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

        // Left side (prev): only show if we have ≥2 plugins.
        if (n >= 2) addIconSlot(plugins[prevIdx], focused = false)
        addIconSlot(plugins[focusedIndex], focused = true)
        if (n >= 3) addIconSlot(plugins[nextIdx], focused = false)

        focusedLabel.text = plugins[focusedIndex].label
        carouselHint.text = "Swipe · Tap · Hold for info"
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

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    // --- Touch handling ---
    // XE/EE1 deliver touchpad events via onGenericMotionEvent (raw cyttsp5).
    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (handleTouchpad(event)) return true
        return super.onGenericMotionEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (handleTouchpad(event)) return true
        return super.onTouchEvent(event)
    }

    private fun handleTouchpad(event: MotionEvent?): Boolean {
        event ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                swiped = false
                longPressFired = false
                longPressHandler.postDelayed(longPressRunnable, 500)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                // Vertical down swipe = close
                if (dy > 80 && Math.abs(dy) > Math.abs(dx)) {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    finish()
                    return true
                }
                if (!swiped && Math.abs(dx) > 35) {
                    swiped = true
                    longPressHandler.removeCallbacks(longPressRunnable)
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
                longPressHandler.removeCallbacks(longPressRunnable)
                if (!swiped && !longPressFired) {
                    launchFocusedPlugin()
                    return true
                }
            }
        }
        return false
    }

    private fun launchFocusedPlugin() {
        val plugin = plugins.getOrNull(focusedIndex) ?: return
        try {
            startActivity(plugin.launchIntent)
        } catch (_: Exception) {}
    }

    private fun showPluginDetails() {
        val plugin = plugins.getOrNull(focusedIndex) ?: return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            setBackgroundColor(Color.BLACK)
        }

        container.addView(TextView(this).apply {
            text = plugin.label
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        container.addView(detailRow("Version", plugin.versionName.ifEmpty { "?" }))
        container.addView(detailRow("Variant", plugin.variant))
        container.addView(detailRow("Size", InstalledPluginScanner.formatSize(plugin.sizeBytes)))
        container.addView(detailRow("Package", plugin.packageName))
        container.addView(TextView(this).apply {
            text = "Tap anywhere to close"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        })

        val dialog = AlertDialog.Builder(this).setView(container).create()
        container.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun detailRow(label: String, value: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        row.addView(TextView(this).apply {
            text = "$label: "
            textSize = 14f
            setTextColor(0xFF888888.toInt())
        })
        row.addView(TextView(this).apply {
            text = value
            textSize = 14f
            setTextColor(Color.WHITE)
        })
        return row
    }

    // --- BluetoothListenerService.MessageListener ---
    override fun onMessageReceived(message: String) { /* no-op — no message UI on main screen */ }

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
        longPressHandler.removeCallbacks(longPressRunnable)
        if (bound) {
            service?.messageListener = null
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}
