package com.glasshole.phone.plugins.device

import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.glasshole.phone.R
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeviceActivity : AppCompatActivity() {

    private lateinit var batteryText: TextView
    private lateinit var glassTimeText: TextView

    private lateinit var brightnessSeek: SeekBar
    private lateinit var brightnessLabel: TextView
    private lateinit var brightnessAutoSwitch: SwitchMaterial

    private lateinit var volumeSeek: SeekBar
    private lateinit var volumeLabel: TextView

    private lateinit var timeoutSeek: SeekBar
    private lateinit var timeoutLabel: TextView

    private lateinit var wakeButton: Button
    private lateinit var syncTimeButton: Button
    private lateinit var refreshButton: Button
    private lateinit var statusText: TextView

    // Timeout slider uses discrete steps (seconds)
    private val timeoutSteps = intArrayOf(15, 30, 60, 120, 300, 600, 1200, 1800)

    private val dateFmt = SimpleDateFormat("EEE d MMM yyyy, HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device)

        batteryText = findViewById(R.id.batteryText)
        glassTimeText = findViewById(R.id.glassTimeText)
        brightnessSeek = findViewById(R.id.brightnessSeek)
        brightnessLabel = findViewById(R.id.brightnessLabel)
        brightnessAutoSwitch = findViewById(R.id.brightnessAutoSwitch)
        volumeSeek = findViewById(R.id.volumeSeek)
        volumeLabel = findViewById(R.id.volumeLabel)
        timeoutSeek = findViewById(R.id.timeoutSeek)
        timeoutLabel = findViewById(R.id.timeoutLabel)
        wakeButton = findViewById(R.id.wakeButton)
        syncTimeButton = findViewById(R.id.syncTimeButton)
        refreshButton = findViewById(R.id.refreshButton)
        statusText = findViewById(R.id.statusText)

        timeoutSeek.max = timeoutSteps.size - 1

        wakeButton.setOnClickListener {
            val sent = DevicePlugin.instance?.wakeGlass() ?: false
            toast(if (sent) "Wake sent" else "Glass not connected")
        }

        syncTimeButton.setOnClickListener {
            val sent = DevicePlugin.instance?.syncTime() ?: false
            toast(if (sent) "Time sync sent" else "Glass not connected")
        }

        refreshButton.setOnClickListener {
            val sent = DevicePlugin.instance?.requestState() ?: false
            if (!sent) toast("Glass not connected")
        }

        brightnessSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                brightnessLabel.text = "Brightness: $progress / 255"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                // Moving the slider implicitly disables auto mode
                DevicePlugin.instance?.setBrightness(sb?.progress ?: 0)
            }
        })

        brightnessAutoSwitch.setOnCheckedChangeListener { _, isChecked ->
            DevicePlugin.instance?.setBrightnessAuto(isChecked)
            brightnessSeek.isEnabled = !isChecked
        }

        volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val max = DevicePlugin.instance?.latestState?.volumeMax ?: 15
                volumeLabel.text = "Volume: $progress / $max"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                DevicePlugin.instance?.setVolume(sb?.progress ?: 0)
            }
        })

        timeoutSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val seconds = timeoutSteps[progress.coerceIn(0, timeoutSteps.size - 1)]
                timeoutLabel.text = "Screen timeout: ${formatTimeout(seconds)}"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val seconds = timeoutSteps[(sb?.progress ?: 0).coerceIn(0, timeoutSteps.size - 1)]
                DevicePlugin.instance?.setTimeout(seconds * 1000)
            }
        })
    }

    override fun onStart() {
        super.onStart()
        val plugin = DevicePlugin.instance
        if (plugin == null) {
            statusText.text = "GlassHole service not ready"
            return
        }
        plugin.onStateChanged = { state -> runOnUiThread { renderState(state) } }
        plugin.onTimeSyncResult = { success, method ->
            runOnUiThread {
                statusText.text = when {
                    success && method.isNotEmpty() -> "Time synced via $method"
                    success -> "Time synced"
                    else -> "Time sync unavailable (needs root or signed system app)"
                }
            }
        }
        plugin.latestState?.let(::renderState)
        plugin.requestState()
    }

    override fun onStop() {
        super.onStop()
        DevicePlugin.instance?.onStateChanged = null
        DevicePlugin.instance?.onTimeSyncResult = null
    }

    private fun renderState(state: DeviceState) {
        batteryText.text = if (state.battery >= 0) {
            "Battery: ${state.battery}%${if (state.charging) " (charging)" else ""}"
        } else "Battery: --"

        glassTimeText.text = if (state.glassTimeMillis > 0) {
            "Glass time: ${dateFmt.format(Date(state.glassTimeMillis))}"
        } else "Glass time: --"

        brightnessSeek.max = state.brightnessMax.coerceAtLeast(1)
        if (state.brightness in 0..state.brightnessMax) {
            brightnessSeek.progress = state.brightness
            brightnessLabel.text = "Brightness: ${state.brightness} / ${state.brightnessMax}"
        }
        brightnessSeek.isEnabled = !state.brightnessAuto
        // Avoid firing the listener while syncing state from glass
        brightnessAutoSwitch.setOnCheckedChangeListener(null)
        brightnessAutoSwitch.isChecked = state.brightnessAuto
        brightnessAutoSwitch.setOnCheckedChangeListener { _, isChecked ->
            DevicePlugin.instance?.setBrightnessAuto(isChecked)
            brightnessSeek.isEnabled = !isChecked
        }

        volumeSeek.max = state.volumeMax.coerceAtLeast(1)
        volumeSeek.progress = state.volume.coerceIn(0, state.volumeMax)
        volumeLabel.text = "Volume: ${state.volume} / ${state.volumeMax}"

        val currentSeconds = state.timeoutMs / 1000
        val closestIndex = timeoutSteps.indices.minByOrNull {
            kotlin.math.abs(timeoutSteps[it] - currentSeconds)
        } ?: 0
        timeoutSeek.progress = closestIndex
        timeoutLabel.text = "Screen timeout: ${formatTimeout(timeoutSteps[closestIndex])}"

        statusText.text = "Connected"
    }

    private fun formatTimeout(seconds: Int): String {
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m".trim()
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
