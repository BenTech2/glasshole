package com.glasshole.plugin.ssh.glass

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Glass-side picker for cached SSH profiles. Vertical list — Glass's
 * cover-flow widgets don't add value when the entries are pure text
 * and stay short; a tall list keeps DPAD navigation predictable.
 *
 * Tap (or DPAD_CENTER) → launches [TerminalActivity] with the profile
 * id; that activity handles the actual SSH dial.
 *
 * If no profiles are cached yet the activity shows a hint and gives
 * the user a "Manual entry" jump so they can connect ad-hoc without
 * having to add a profile from the phone first.
 */
class ProfilePickerActivity : Activity() {

    private var entries: List<ProfileStore.Profile> = emptyList()
    private var focusedIndex: Int = 0
    private val rows = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        entries = ProfileStore(this).list()

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        column.addView(header("SSH connections"))

        if (entries.isEmpty()) {
            column.addView(body(
                "No connections synced yet — add one in the phone's SSH page, " +
                "or tap below for ad-hoc manual entry."
            ))
            column.addView(actionRow("Manual entry") {
                startActivity(Intent(this, TerminalActivity::class.java))
                finish()
            })
        } else {
            entries.forEachIndexed { i, p ->
                column.addView(profileRow(i, p))
            }
            column.addView(actionRow("Manual entry") {
                startActivity(Intent(this, TerminalActivity::class.java))
                finish()
            })
        }

        scroll.addView(column)
        setContentView(scroll)

        if (rows.isNotEmpty()) {
            rows[0].requestFocus()
        }
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 22f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, 0, 0, dp(8))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(0xFFCCCCCC.toInt())
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun profileRow(index: Int, profile: ProfileStore.Profile): View {
        val tv = TextView(this).apply {
            text = "${profile.name}\n${profile.user}@${profile.host}:${profile.port}"
            textSize = 16f
            setTextColor(Color.WHITE)
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFF111111.toInt())
            setOnFocusChangeListener { v, hasFocus ->
                v.setBackgroundColor(if (hasFocus) 0xFF333333.toInt() else 0xFF111111.toInt())
                if (hasFocus) focusedIndex = index
            }
            setOnClickListener { launch(profile.id) }
        }
        // 4dp gap between rows by wrapping in a LinearLayout.LayoutParams
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) }
        tv.layoutParams = lp
        rows.add(tv)
        return tv
    }

    private fun actionRow(label: String, onClick: () -> Unit): View {
        val tv = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(0xFF99CCFF.toInt())
            isFocusable = true
            isFocusableInTouchMode = true
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnFocusChangeListener { v, hasFocus ->
                v.setBackgroundColor(if (hasFocus) 0xFF222244.toInt() else Color.TRANSPARENT)
            }
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
        tv.layoutParams = lp
        rows.add(tv)
        return tv
    }

    private fun launch(profileId: String) {
        startActivity(Intent(this, TerminalActivity::class.java).apply {
            putExtra(TerminalActivity.EXTRA_PROFILE_ID, profileId)
        })
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
