package com.glasshole.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.glasshole.phone.service.BridgeService

class DebugActivity : AppCompatActivity() {

    private lateinit var appInput: EditText
    private lateinit var titleInput: EditText
    private lateinit var textInput: EditText
    private lateinit var sendButton: Button
    private lateinit var statusText: TextView

    private var bridgeService: BridgeService? = null
    private var bridgeBound = false

    private val bridgeConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bridgeService = (binder as BridgeService.LocalBinder).getService()
            bridgeBound = true
            updateStatus()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bridgeService = null
            bridgeBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)

        appInput = findViewById(R.id.debugAppInput)
        titleInput = findViewById(R.id.debugTitleInput)
        textInput = findViewById(R.id.debugTextInput)
        sendButton = findViewById(R.id.debugSendButton)
        statusText = findViewById(R.id.debugStatusText)

        sendButton.setOnClickListener { sendTestNotification() }

        bindService(
            Intent(this, BridgeService::class.java),
            bridgeConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        if (bridgeBound) {
            unbindService(bridgeConnection)
            bridgeBound = false
        }
        super.onDestroy()
    }

    private fun updateStatus() {
        val bridge = bridgeService
        statusText.text = when {
            bridge == null -> "BridgeService not bound"
            bridge.isConnected -> "Glass connected"
            else -> "Glass not connected — tap Connect on the main screen first"
        }
        sendButton.isEnabled = bridge?.isConnected == true
    }

    private fun sendTestNotification() {
        val bridge = bridgeService
        if (bridge == null || !bridge.isConnected) {
            toast("Glass not connected")
            updateStatus()
            return
        }

        val app = appInput.text.toString().ifBlank { "GlassHole" }
        val title = titleInput.text.toString().trim()
        val text = textInput.text.toString().ifBlank { "Test notification from the debug screen" }

        // Match the format wireNotificationListener uses so the glass side
        // parses it into app/title/text correctly:
        //   "$appName: $title - $text"  (title is optional)
        val formatted = if (title.isNotEmpty()) {
            "$app: $title - $text"
        } else {
            "$app: $text"
        }

        val sent = bridge.sendToGlass(formatted)
        toast(if (sent) "Sent to glass" else "Send failed")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
