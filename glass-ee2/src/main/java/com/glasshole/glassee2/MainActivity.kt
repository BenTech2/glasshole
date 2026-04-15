package com.glasshole.glassee2

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity(), BluetoothListenerService.MessageListener {

    companion object {
        private const val TAG = "GlassHoleMain"
    }

    private lateinit var statusText: TextView
    private lateinit var lastMessageText: TextView
    private lateinit var hintText: TextView
    private lateinit var messageScroll: ScrollView
    private var service: BluetoothListenerService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as BluetoothListenerService.LocalBinder
            service = localBinder.getService()
            service?.messageListener = this@MainActivity
            bound = true

            val svc = service!!
            if (svc.isPhoneConnected) onConnectionStateChanged(true)
            if (svc.lastMessage.isNotEmpty()) onMessageReceived(svc.lastMessage)
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
        lastMessageText = findViewById(R.id.lastMessageText)
        hintText = findViewById(R.id.hintText)
        messageScroll = findViewById(R.id.messageScroll)

        // Start BT listener and plugin host
        val btIntent = Intent(this, BluetoothListenerService::class.java)
        startForegroundService(btIntent)
        bindService(btIntent, connection, Context.BIND_AUTO_CREATE)

        val pluginIntent = Intent(this, PluginHostService::class.java)
        startService(pluginIntent)
    }

    override fun onMessageReceived(message: String) {
        runOnUiThread {
            lastMessageText.text = message
            lastMessageText.setTextColor(0xFFFFFFFF.toInt())
        }
    }

    override fun onConnectionStateChanged(connected: Boolean) {
        runOnUiThread {
            if (connected) {
                statusText.text = "Phone connected"
                statusText.setTextColor(0xFF43A047.toInt())
                hintText.text = "Tap: voice reply"
                hintText.setTextColor(0xFF66BB6A.toInt())
            } else {
                statusText.text = "Waiting for phone..."
                statusText.setTextColor(0xFFBBBBBB.toInt())
                hintText.text = "Connect phone to start"
                hintText.setTextColor(0xFF888888.toInt())
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_TAB -> {
                if (event?.isShiftPressed == true) {
                    messageScroll.smoothScrollBy(0, -150)
                } else {
                    messageScroll.smoothScrollBy(0, 150)
                }
                return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                // Open plugin launcher or voice reply
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
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
