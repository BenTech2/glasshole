package com.glasshole.phone.plugins.notes

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.glasshole.phone.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import org.json.JSONObject

/**
 * Phone-side control panel for the glass teleprompter. The note text is
 * pushed to the glass in onCreate; afterwards every slider/button change
 * sends an incremental TELEPROMPTER_CONTROL message instead of restarting
 * the session.
 *
 * The glass echoes its current state back via TELEPROMPTER_STATE so local
 * changes (tap-to-pause on glass, swipe-to-adjust-speed) are reflected
 * in the UI here without the user having to keep both sides in their
 * head.
 */
class TeleprompterControlActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_NOTE_TITLE = "note_title"
        const val EXTRA_NOTE_TEXT = "note_text"

        private const val DEFAULT_SPEED = 40f
        private const val DEFAULT_FONT = 28f
    }

    private lateinit var playPauseButton: MaterialButton
    private lateinit var speedSlider: Slider
    private lateinit var fontSlider: Slider
    private lateinit var speedLabel: TextView
    private lateinit var fontLabel: TextView

    private var playing: Boolean = true
    /** Suppress the slider OnChangeListener echo loop when we set the
     *  value programmatically in response to a TELEPROMPTER_STATE
     *  message. Otherwise the slider would re-send the same value as
     *  a control message and the glass would feel sluggish. */
    private var applyingRemoteState: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teleprompter_control)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val title = intent.getStringExtra(EXTRA_NOTE_TITLE) ?: "Note"
        val text = intent.getStringExtra(EXTRA_NOTE_TEXT) ?: ""
        findViewById<TextView>(R.id.noteTitle).text = title

        playPauseButton = findViewById(R.id.playPauseButton)
        speedSlider = findViewById(R.id.speedSlider)
        fontSlider = findViewById(R.id.fontSlider)
        speedLabel = findViewById(R.id.speedLabel)
        fontLabel = findViewById(R.id.fontLabel)

        speedSlider.value = DEFAULT_SPEED
        fontSlider.value = DEFAULT_FONT
        updateSpeedLabel(DEFAULT_SPEED)
        updateFontLabel(DEFAULT_FONT)
        updatePlayPauseLabel()

        // Push the session to the glass. If the bridge isn't connected
        // the call returns false and we show a one-shot Toast — the
        // user can leave the panel up and the next slider change will
        // try again as soon as the link comes back.
        val plugin = NotesPlugin.instance
        if (plugin == null || !plugin.sendTeleprompterStart(
                text, DEFAULT_SPEED, DEFAULT_FONT, playing
            )
        ) {
            Toast.makeText(this, "Glass not connected — controls will queue",
                Toast.LENGTH_SHORT).show()
        }

        playPauseButton.setOnClickListener {
            playing = !playing
            updatePlayPauseLabel()
            NotesPlugin.instance?.sendTeleprompterControl(playing = playing)
        }

        speedSlider.addOnChangeListener { _, value, fromUser ->
            updateSpeedLabel(value)
            if (fromUser && !applyingRemoteState) {
                NotesPlugin.instance?.sendTeleprompterControl(speedPxPerSec = value)
            }
        }

        fontSlider.addOnChangeListener { _, value, fromUser ->
            updateFontLabel(value)
            if (fromUser && !applyingRemoteState) {
                NotesPlugin.instance?.sendTeleprompterControl(fontSp = value)
            }
        }

        findViewById<MaterialButton>(R.id.restartButton).setOnClickListener {
            NotesPlugin.instance?.sendTeleprompterControl(restart = true)
        }

        findViewById<MaterialButton>(R.id.stopButton).setOnClickListener {
            NotesPlugin.instance?.sendTeleprompterStop()
            finish()
        }

        // Reflect glass-side state echoes (tap-to-pause, local speed
        // tweaks) onto the UI so the panel stays in sync.
        NotesPlugin.instance?.onTeleprompterState = { payload ->
            runOnUiThread { applyRemoteState(payload) }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clear the listener so a backgrounded panel doesn't keep
        // receiving callbacks; the next activity to take over will
        // install its own.
        if (NotesPlugin.instance?.onTeleprompterState != null) {
            NotesPlugin.instance?.onTeleprompterState = null
        }
    }

    private fun applyRemoteState(payload: String) {
        try {
            val json = JSONObject(payload)
            applyingRemoteState = true
            if (json.has("playing")) {
                playing = json.getBoolean("playing")
                updatePlayPauseLabel()
            }
            if (json.has("speedPxPerSec")) {
                val v = json.getDouble("speedPxPerSec").toFloat()
                    .coerceIn(speedSlider.valueFrom, speedSlider.valueTo)
                speedSlider.value = v
                updateSpeedLabel(v)
            }
            if (json.has("fontSp")) {
                val v = json.getDouble("fontSp").toFloat()
                    .coerceIn(fontSlider.valueFrom, fontSlider.valueTo)
                fontSlider.value = v
                updateFontLabel(v)
            }
        } catch (_: Exception) {
        } finally {
            applyingRemoteState = false
        }
    }

    private fun updatePlayPauseLabel() {
        playPauseButton.text = if (playing) "Pause" else "Play"
    }

    private fun updateSpeedLabel(value: Float) {
        speedLabel.text = "${value.toInt()} px/s"
    }

    private fun updateFontLabel(value: Float) {
        fontLabel.text = "${value.toInt()} sp"
    }
}
