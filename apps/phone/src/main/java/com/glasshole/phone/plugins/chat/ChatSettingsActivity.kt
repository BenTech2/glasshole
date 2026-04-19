package com.glasshole.phone.plugins.chat

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.glasshole.phone.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class ChatSettingsActivity : AppCompatActivity() {

    private lateinit var platformGroup: RadioGroup
    private lateinit var radioTwitch: RadioButton
    private lateinit var radioYouTube: RadioButton
    private lateinit var twitchCard: LinearLayout
    private lateinit var youtubeCard: LinearLayout
    private lateinit var twitchChannelInput: TextInputEditText
    private lateinit var youtubeApiKeyInput: TextInputEditText
    private lateinit var youtubeVideoInput: TextInputEditText
    private lateinit var fontSizeSeek: SeekBar
    private lateinit var fontSizeLabel: TextView
    private lateinit var maxMessagesSeek: SeekBar
    private lateinit var maxMessagesLabel: TextView

    // Font size slider is discrete: 10, 12, 14, 16, 18, 20, 22, 24sp
    private val fontSizeMin = 10
    private val fontSizeStep = 2
    private val fontSizeStepsCount = 8

    // Chat cache slider: 50 → 1000 in 50-msg steps
    private val maxMessagesMin = 50
    private val maxMessagesStep = 50
    private val maxMessagesCap = 1000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Chat viewer settings"

        platformGroup = findViewById(R.id.platformGroup)
        radioTwitch = findViewById(R.id.radioTwitch)
        radioYouTube = findViewById(R.id.radioYouTube)
        twitchCard = findViewById(R.id.twitchCard)
        youtubeCard = findViewById(R.id.youtubeCard)
        twitchChannelInput = findViewById(R.id.twitchChannelInput)
        youtubeApiKeyInput = findViewById(R.id.youtubeApiKeyInput)
        youtubeVideoInput = findViewById(R.id.youtubeVideoInput)
        fontSizeSeek = findViewById(R.id.fontSizeSeek)
        fontSizeLabel = findViewById(R.id.fontSizeLabel)
        fontSizeSeek.max = fontSizeStepsCount - 1
        fontSizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                fontSizeLabel.text = "Font size: ${fontSizeFromProgress(progress)}sp"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        maxMessagesSeek = findViewById(R.id.maxMessagesSeek)
        maxMessagesLabel = findViewById(R.id.maxMessagesLabel)
        maxMessagesSeek.max = (maxMessagesCap - maxMessagesMin) / maxMessagesStep
        maxMessagesSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                maxMessagesLabel.text = "Chat cache: ${maxMessagesFromProgress(progress)} messages"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val prefs = getSharedPreferences(ChatPlugin.PREFS_NAME, Context.MODE_PRIVATE)
        val platform = prefs.getString(ChatPlugin.KEY_PLATFORM, "twitch") ?: "twitch"
        twitchChannelInput.setText(prefs.getString(ChatPlugin.KEY_TWITCH_CHANNEL, ""))
        youtubeApiKeyInput.setText(prefs.getString(ChatPlugin.KEY_YOUTUBE_API_KEY, ""))
        youtubeVideoInput.setText(prefs.getString(ChatPlugin.KEY_YOUTUBE_VIDEO, ""))

        val savedSize = prefs.getInt(ChatPlugin.KEY_FONT_SIZE, ChatPlugin.DEFAULT_FONT_SIZE)
        fontSizeSeek.progress = progressFromFontSize(savedSize)
        fontSizeLabel.text = "Font size: ${savedSize}sp"

        val savedCache = prefs.getInt(
            ChatPlugin.KEY_MAX_MESSAGES, ChatPlugin.DEFAULT_MAX_MESSAGES
        )
        maxMessagesSeek.progress = progressFromMaxMessages(savedCache)
        maxMessagesLabel.text = "Chat cache: $savedCache messages"

        if (platform == "youtube") radioYouTube.isChecked = true else radioTwitch.isChecked = true
        applyVisibility(platform)

        platformGroup.setOnCheckedChangeListener { _, id ->
            applyVisibility(if (id == R.id.radioYouTube) "youtube" else "twitch")
        }

        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            save()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // Only one platform card is visible at once — reinforces the "one at a
    // time" rule from settings so users can't be confused about which set
    // of fields is actually live.
    private fun applyVisibility(platform: String) {
        if (platform == "youtube") {
            twitchCard.visibility = View.GONE
            youtubeCard.visibility = View.VISIBLE
        } else {
            twitchCard.visibility = View.VISIBLE
            youtubeCard.visibility = View.GONE
        }
    }

    private fun save() {
        val platform = if (radioYouTube.isChecked) "youtube" else "twitch"
        getSharedPreferences(ChatPlugin.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(ChatPlugin.KEY_PLATFORM, platform)
            .putString(ChatPlugin.KEY_TWITCH_CHANNEL,
                twitchChannelInput.text?.toString()?.trim()?.removePrefix("#").orEmpty())
            .putString(ChatPlugin.KEY_YOUTUBE_API_KEY,
                youtubeApiKeyInput.text?.toString()?.trim().orEmpty())
            .putString(ChatPlugin.KEY_YOUTUBE_VIDEO,
                youtubeVideoInput.text?.toString()?.trim().orEmpty())
            .putInt(ChatPlugin.KEY_FONT_SIZE, fontSizeFromProgress(fontSizeSeek.progress))
            .putInt(
                ChatPlugin.KEY_MAX_MESSAGES,
                maxMessagesFromProgress(maxMessagesSeek.progress)
            )
            .apply()
    }

    private fun fontSizeFromProgress(progress: Int): Int =
        fontSizeMin + progress.coerceIn(0, fontSizeStepsCount - 1) * fontSizeStep

    private fun progressFromFontSize(size: Int): Int =
        ((size - fontSizeMin) / fontSizeStep).coerceIn(0, fontSizeStepsCount - 1)

    private fun maxMessagesFromProgress(progress: Int): Int {
        val steps = (maxMessagesCap - maxMessagesMin) / maxMessagesStep
        return maxMessagesMin + progress.coerceIn(0, steps) * maxMessagesStep
    }

    private fun progressFromMaxMessages(value: Int): Int {
        val steps = (maxMessagesCap - maxMessagesMin) / maxMessagesStep
        return ((value - maxMessagesMin) / maxMessagesStep).coerceIn(0, steps)
    }
}
