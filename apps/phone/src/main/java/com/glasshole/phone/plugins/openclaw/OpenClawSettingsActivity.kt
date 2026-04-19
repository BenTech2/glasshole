package com.glasshole.phone.plugins.openclaw

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.glasshole.phone.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class OpenClawSettingsActivity : AppCompatActivity() {

    private lateinit var botTokenInput: TextInputEditText
    private lateinit var chatIdInput: TextInputEditText
    private lateinit var kickstartInput: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_openclaw_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "OpenClaw settings"

        botTokenInput = findViewById(R.id.botTokenInput)
        chatIdInput = findViewById(R.id.chatIdInput)
        kickstartInput = findViewById(R.id.kickstartInput)

        val prefs = getSharedPreferences(OpenClawPlugin.PREFS_NAME, Context.MODE_PRIVATE)
        botTokenInput.setText(prefs.getString(OpenClawPlugin.KEY_BOT_TOKEN, ""))
        chatIdInput.setText(prefs.getString(OpenClawPlugin.KEY_CHAT_ID, ""))
        kickstartInput.setText(
            prefs.getString(OpenClawPlugin.KEY_KICKSTART_MSG, OpenClawPlugin.DEFAULT_KICKSTART)
        )

        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            save()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.testButton).setOnClickListener {
            val token = botTokenInput.text?.toString()?.trim().orEmpty()
            val chatId = chatIdInput.text?.toString()?.trim().orEmpty()
            val text = kickstartInput.text?.toString()
                ?.takeIf { it.isNotBlank() } ?: OpenClawPlugin.DEFAULT_KICKSTART

            if (token.isEmpty() || chatId.isEmpty()) {
                Toast.makeText(this, "Enter bot token and chat ID first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val plugin = OpenClawPlugin.instance
            if (plugin == null) {
                Toast.makeText(this, "Plugin host not running", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Toast.makeText(this, "Sending…", Toast.LENGTH_SHORT).show()
            plugin.sendTest(token, chatId, text) { success, error ->
                runOnUiThread {
                    if (success) {
                        Toast.makeText(this, "Sent — check Telegram", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Failed: ${error ?: "unknown"}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun save() {
        getSharedPreferences(OpenClawPlugin.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(OpenClawPlugin.KEY_BOT_TOKEN, botTokenInput.text?.toString()?.trim().orEmpty())
            .putString(OpenClawPlugin.KEY_CHAT_ID, chatIdInput.text?.toString()?.trim().orEmpty())
            .putString(OpenClawPlugin.KEY_KICKSTART_MSG, kickstartInput.text?.toString().orEmpty())
            .apply()
    }
}
