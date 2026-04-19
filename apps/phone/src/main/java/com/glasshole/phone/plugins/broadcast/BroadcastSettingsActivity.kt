package com.glasshole.phone.plugins.broadcast

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.glasshole.phone.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class BroadcastSettingsActivity : AppCompatActivity() {

    private data class Resolution(val w: Int, val h: Int) {
        override fun toString() = "${w}×${h}"
    }

    private val resolutions = listOf(
        Resolution(640, 360),
        Resolution(854, 480),
        Resolution(1280, 720)
    )
    private val fpsOptions = listOf(24, 30)
    private val bitrateMinKbps = 300
    private val bitrateMaxKbps = 4_000
    private val bitrateStep = 100

    // Font size: 10..24sp in 2sp steps
    private val fontSizeMin = 10
    private val fontSizeStep = 2
    private val fontSizeStepsCount = 8

    // Chat cache: 50..1000 in 50-message steps
    private val maxMessagesMin = 50
    private val maxMessagesStep = 50
    private val maxMessagesCap = BroadcastPlugin.CHAT_MAX_MESSAGES_CAP

    private lateinit var urlInput: TextInputEditText
    private lateinit var resolutionSpinner: Spinner
    private lateinit var fpsSpinner: Spinner
    private lateinit var bitrateLabel: TextView
    private lateinit var bitrateSeek: SeekBar
    private lateinit var audioSwitch: SwitchMaterial
    private lateinit var displayGroup: RadioGroup

    private lateinit var chatOverlayCard: LinearLayout
    private lateinit var chatPlatformGroup: RadioGroup
    private lateinit var chatRadioTwitch: RadioButton
    private lateinit var chatRadioYouTube: RadioButton
    private lateinit var chatTwitchCard: LinearLayout
    private lateinit var chatYouTubeCard: LinearLayout
    private lateinit var chatTwitchChannelInput: TextInputEditText
    private lateinit var chatYouTubeApiKeyInput: TextInputEditText
    private lateinit var chatYouTubeVideoInput: TextInputEditText
    private lateinit var chatFontSizeSeek: SeekBar
    private lateinit var chatFontSizeLabel: TextView
    private lateinit var chatMaxMessagesSeek: SeekBar
    private lateinit var chatMaxMessagesLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_broadcast_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Broadcast settings"

        urlInput = findViewById(R.id.urlInput)
        resolutionSpinner = findViewById(R.id.resolutionSpinner)
        fpsSpinner = findViewById(R.id.fpsSpinner)
        bitrateLabel = findViewById(R.id.bitrateLabel)
        bitrateSeek = findViewById(R.id.bitrateSeek)
        audioSwitch = findViewById(R.id.audioSwitch)
        displayGroup = findViewById(R.id.displayGroup)

        chatOverlayCard = findViewById(R.id.chatOverlayCard)
        chatPlatformGroup = findViewById(R.id.chatPlatformGroup)
        chatRadioTwitch = findViewById(R.id.chatRadioTwitch)
        chatRadioYouTube = findViewById(R.id.chatRadioYouTube)
        chatTwitchCard = findViewById(R.id.chatTwitchCard)
        chatYouTubeCard = findViewById(R.id.chatYouTubeCard)
        chatTwitchChannelInput = findViewById(R.id.chatTwitchChannelInput)
        chatYouTubeApiKeyInput = findViewById(R.id.chatYouTubeApiKeyInput)
        chatYouTubeVideoInput = findViewById(R.id.chatYouTubeVideoInput)
        chatFontSizeSeek = findViewById(R.id.chatFontSizeSeek)
        chatFontSizeLabel = findViewById(R.id.chatFontSizeLabel)
        chatMaxMessagesSeek = findViewById(R.id.chatMaxMessagesSeek)
        chatMaxMessagesLabel = findViewById(R.id.chatMaxMessagesLabel)

        resolutionSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, resolutions
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        fpsSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, fpsOptions
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        bitrateSeek.max = (bitrateMaxKbps - bitrateMinKbps) / bitrateStep
        bitrateSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                bitrateLabel.text = "Bitrate: ${bitrateFromProgress(progress)} kbps"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        chatFontSizeSeek.max = fontSizeStepsCount - 1
        chatFontSizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                chatFontSizeLabel.text = "Font size: ${fontSizeFromProgress(progress)}sp"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        chatMaxMessagesSeek.max = (maxMessagesCap - maxMessagesMin) / maxMessagesStep
        chatMaxMessagesSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                chatMaxMessagesLabel.text =
                    "Chat cache: ${maxMessagesFromProgress(progress)} messages"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        chatPlatformGroup.setOnCheckedChangeListener { _, id ->
            applyChatPlatformVisibility(if (id == R.id.chatRadioYouTube) "youtube" else "twitch")
        }

        // Collapse the chat overlay card when the display mode is something
        // other than "chat" so the settings screen isn't visually noisy.
        displayGroup.setOnCheckedChangeListener { _, id ->
            chatOverlayCard.visibility = if (id == R.id.radioChat) View.VISIBLE else View.GONE
        }

        load()

        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            save()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun bitrateFromProgress(progress: Int): Int =
        bitrateMinKbps + progress * bitrateStep

    private fun progressFromBitrate(kbps: Int): Int =
        ((kbps - bitrateMinKbps) / bitrateStep).coerceIn(0, bitrateSeek.max)

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

    private fun applyChatPlatformVisibility(platform: String) {
        if (platform == "youtube") {
            chatTwitchCard.visibility = View.GONE
            chatYouTubeCard.visibility = View.VISIBLE
        } else {
            chatTwitchCard.visibility = View.VISIBLE
            chatYouTubeCard.visibility = View.GONE
        }
    }

    private fun load() {
        val prefs = getSharedPreferences(BroadcastPlugin.PREFS_NAME, Context.MODE_PRIVATE)
        urlInput.setText(prefs.getString(BroadcastPlugin.KEY_URL, ""))

        val w = prefs.getInt(BroadcastPlugin.KEY_WIDTH, 1280)
        val h = prefs.getInt(BroadcastPlugin.KEY_HEIGHT, 720)
        resolutions.indexOfFirst { it.w == w && it.h == h }
            .takeIf { it >= 0 }
            ?.let { resolutionSpinner.setSelection(it) }

        val fps = prefs.getInt(BroadcastPlugin.KEY_FPS, 30)
        fpsOptions.indexOf(fps).takeIf { it >= 0 }?.let { fpsSpinner.setSelection(it) }

        val bitrate = prefs.getInt(BroadcastPlugin.KEY_BITRATE_KBPS, 1500)
        bitrateSeek.progress = progressFromBitrate(bitrate)
        bitrateLabel.text = "Bitrate: $bitrate kbps"

        audioSwitch.isChecked = prefs.getBoolean(BroadcastPlugin.KEY_AUDIO, true)

        val display = prefs.getString(BroadcastPlugin.KEY_DISPLAY, "viewfinder")
        when (display) {
            "preview_off" -> findViewById<RadioButton>(R.id.radioPreviewOff).isChecked = true
            "screen_off" -> findViewById<RadioButton>(R.id.radioScreenOff).isChecked = true
            "chat" -> findViewById<RadioButton>(R.id.radioChat).isChecked = true
            else -> findViewById<RadioButton>(R.id.radioViewfinder).isChecked = true
        }
        chatOverlayCard.visibility = if (display == "chat") View.VISIBLE else View.GONE

        // Chat overlay settings
        val chatPlatform = prefs.getString(BroadcastPlugin.KEY_CHAT_PLATFORM, "twitch") ?: "twitch"
        if (chatPlatform == "youtube") chatRadioYouTube.isChecked = true
        else chatRadioTwitch.isChecked = true
        applyChatPlatformVisibility(chatPlatform)

        chatTwitchChannelInput.setText(
            prefs.getString(BroadcastPlugin.KEY_CHAT_TWITCH_CHANNEL, "")
        )
        chatYouTubeApiKeyInput.setText(
            prefs.getString(BroadcastPlugin.KEY_CHAT_YOUTUBE_API_KEY, "")
        )
        chatYouTubeVideoInput.setText(
            prefs.getString(BroadcastPlugin.KEY_CHAT_YOUTUBE_VIDEO, "")
        )

        val savedSize = prefs.getInt(
            BroadcastPlugin.KEY_CHAT_FONT_SIZE, BroadcastPlugin.DEFAULT_CHAT_FONT_SIZE
        )
        chatFontSizeSeek.progress = progressFromFontSize(savedSize)
        chatFontSizeLabel.text = "Font size: ${savedSize}sp"

        val savedCache = prefs.getInt(
            BroadcastPlugin.KEY_CHAT_MAX_MESSAGES, BroadcastPlugin.DEFAULT_CHAT_MAX_MESSAGES
        )
        chatMaxMessagesSeek.progress = progressFromMaxMessages(savedCache)
        chatMaxMessagesLabel.text = "Chat cache: $savedCache messages"
    }

    private fun save() {
        val res = resolutionSpinner.selectedItem as? Resolution ?: resolutions[2]
        val fps = fpsSpinner.selectedItem as? Int ?: 30
        val display = when (displayGroup.checkedRadioButtonId) {
            R.id.radioPreviewOff -> "preview_off"
            R.id.radioScreenOff -> "screen_off"
            R.id.radioChat -> "chat"
            else -> "viewfinder"
        }
        val chatPlatform = if (chatRadioYouTube.isChecked) "youtube" else "twitch"

        getSharedPreferences(BroadcastPlugin.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(BroadcastPlugin.KEY_URL, urlInput.text?.toString()?.trim().orEmpty())
            .putInt(BroadcastPlugin.KEY_WIDTH, res.w)
            .putInt(BroadcastPlugin.KEY_HEIGHT, res.h)
            .putInt(BroadcastPlugin.KEY_FPS, fps)
            .putInt(BroadcastPlugin.KEY_BITRATE_KBPS, bitrateFromProgress(bitrateSeek.progress))
            .putBoolean(BroadcastPlugin.KEY_AUDIO, audioSwitch.isChecked)
            .putString(BroadcastPlugin.KEY_DISPLAY, display)
            .putString(BroadcastPlugin.KEY_CHAT_PLATFORM, chatPlatform)
            .putString(
                BroadcastPlugin.KEY_CHAT_TWITCH_CHANNEL,
                chatTwitchChannelInput.text?.toString()?.trim()?.removePrefix("#").orEmpty()
            )
            .putString(
                BroadcastPlugin.KEY_CHAT_YOUTUBE_API_KEY,
                chatYouTubeApiKeyInput.text?.toString()?.trim().orEmpty()
            )
            .putString(
                BroadcastPlugin.KEY_CHAT_YOUTUBE_VIDEO,
                chatYouTubeVideoInput.text?.toString()?.trim().orEmpty()
            )
            .putInt(
                BroadcastPlugin.KEY_CHAT_FONT_SIZE,
                fontSizeFromProgress(chatFontSizeSeek.progress)
            )
            .putInt(
                BroadcastPlugin.KEY_CHAT_MAX_MESSAGES,
                maxMessagesFromProgress(chatMaxMessagesSeek.progress)
            )
            .apply()
    }
}
