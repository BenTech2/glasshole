package com.glasshole.plugin.ssh.glass

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.text.InputType
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * Phase-3 terminal: prompts for SSH credentials (prefilled from
 * SharedPreferences on subsequent launches), then opens an interactive
 * shell channel via JSch. Bytes flow:
 *
 *   • emulator → OutboundSink → [SshConnection.write] → SSH stdin
 *   • SSH stdout → pump thread → [TerminalSession.feed] → emulator
 *
 * Window size is computed from the first measured layout pass — once
 * we know cell width/height we set the pty size on the remote so
 * Claude Code / vim / htop pick the right column count.
 *
 * Profile management + key auth land in phase 4; this is a
 * password-only proof-of-life so we can verify Claude Code over SSH
 * actually renders correctly before investing in the UI.
 */
class TerminalActivity : Activity() {

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        // Encrypted manual-entry remembered creds. The user explicitly
        // asked for the password to persist between launches — we
        // store it AES-GCM-encrypted (Keystore-wrapped key) instead of
        // skipping persistence like the original phase-3 design did.
        private const val PREFS = "ssh_terminal_enc"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_USER = "user"
        private const val KEY_PASSWORD = "password"
    }

    private lateinit var terminalView: TerminalView
    private lateinit var session: TerminalSession
    private var ssh: SshConnection? = null
    private val ui = Handler(Looper.getMainLooper())

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: com.termux.terminal.TerminalSession) {
            terminalView.onScreenUpdated()
        }
    }

    private val viewClient = object : TerminalViewClient {
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Wake the screen on launch — the wake-lock the plugin service
        // grabs before startActivity is only good for ~3s; these flags
        // keep the activity visible once it lands. FLAG_KEEP_SCREEN_ON
        // is also added below conditionally via applyKeepScreenOnFlag().
        @Suppress("DEPRECATION")
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
        applyKeepScreenOnFlag()

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        terminalView = TerminalView(this, null).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setTerminalViewClient(viewClient)
            val density = resources.displayMetrics.density
            setTextSize((14 * density).toInt())
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(terminalView)
        setContentView(root)

        // Outbound sink stays as a ref-cycle-safe lambda — at construct
        // time `ssh` is null; once SshConnection.connect() is invoked
        // from the dialog callback below, the lambda picks up the
        // populated reference on each call.
        session = TerminalSession(
            { data, offset, count -> ssh?.write(data, offset, count) },
            sessionClient
        )
        terminalView.attachSession(session)
        terminalView.requestFocus()

        feedBanner("GlassHole SSH\r\n")

        // Defer connect until after the first layout pass so the
        // emulator has a real cols/rows — we need those for setPtySize.
        terminalView.post {
            val profileId = intent?.getStringExtra(EXTRA_PROFILE_ID)
            if (!profileId.isNullOrEmpty()) {
                connectFromProfile(profileId)
            } else {
                promptAndConnect()
            }
        }
    }

    /** Direct-dispatch path: skip the manual creds dialog and dial the
     *  remote with whatever the profile carries. Used by phone "Quick
     *  Connect" (via SshPluginService.OPEN) and the on-glass profile
     *  picker. */
    private fun connectFromProfile(profileId: String) {
        val profile = ProfileStore(this).get(profileId)
        if (profile == null) {
            feedBanner("[Profile $profileId not found — falling back to manual entry]\r\n")
            promptAndConnect()
            return
        }
        kickoffConnect(
            host = profile.host,
            port = profile.port,
            user = profile.user,
            password = profile.password.orEmpty(),
            privateKeyPem = profile.privateKeyPem,
            keyPassphrase = profile.keyPassphrase
        )
    }

    private fun promptAndConnect() {
        val prefs = EncryptedPrefs.get(this, PREFS)
        val hostBox = EditText(this).apply {
            hint = "host"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString(KEY_HOST, ""))
        }
        val portBox = EditText(this).apply {
            hint = "port"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt(KEY_PORT, 22).toString())
        }
        val userBox = EditText(this).apply {
            hint = "user"
            setText(prefs.getString(KEY_USER, ""))
        }
        val passBox = EditText(this).apply {
            hint = "password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString(KEY_PASSWORD, ""))
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(hostBox)
            addView(portBox)
            addView(userBox)
            addView(passBox)
        }

        AlertDialog.Builder(this)
            .setTitle("SSH connect")
            .setView(container)
            .setPositiveButton("Connect") { _, _ ->
                val host = hostBox.text.toString().trim()
                val port = portBox.text.toString().trim().toIntOrNull() ?: 22
                val user = userBox.text.toString().trim()
                val pass = passBox.text.toString()
                if (host.isEmpty() || user.isEmpty()) {
                    feedBanner("\r\nMissing host or user.\r\n")
                    return@setPositiveButton
                }
                prefs.edit()
                    .putString(KEY_HOST, host)
                    .putInt(KEY_PORT, port)
                    .putString(KEY_USER, user)
                    .putString(KEY_PASSWORD, pass)
                    .apply()
                kickoffConnect(host, port, user, pass, null, null)
                returnFocusToTerminal()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun kickoffConnect(
        host: String,
        port: Int,
        user: String,
        password: String,
        privateKeyPem: String?,
        keyPassphrase: String?
    ) {
        val emulator = session.emulator ?: run {
            feedBanner("Emulator not ready, retrying…\r\n")
            ui.postDelayed({
                kickoffConnect(host, port, user, password, privateKeyPem, keyPassphrase)
            }, 200)
            return
        }
        val cols = emulator.mColumns
        val rows = emulator.mRows
        val cellW = terminalView.mRenderer?.let { it.fontWidthInt() } ?: 8
        val cellH = terminalView.mRenderer?.let { it.fontHeightInt() } ?: 16

        ssh = SshConnection(
            host = host,
            port = port,
            user = user,
            password = password.takeIf { privateKeyPem == null },
            privateKeyPem = privateKeyPem,
            keyPassphrase = keyPassphrase,
            cols = cols,
            rows = rows,
            cellWidth = cellW,
            cellHeight = cellH,
            onBytes = { data, n ->
                // SSH pump thread is OK to call session.feed — it
                // marshals onto the main thread internally before
                // touching the emulator state.
                val slice = ByteArray(n)
                System.arraycopy(data, 0, slice, 0, n)
                session.feed(slice, n)
            },
            onStatus = { msg -> ui.post { feedBanner("\r\n[$msg]\r\n") } },
            onClosed = { code ->
                ui.post {
                    feedBanner("\r\n[Connection closed (exit $code). Swipe down to exit.]\r\n")
                    session.notifyRemoteExited(code)
                }
            }
        ).also { it.connect() }
    }

    /** Emit a banner string into the emulator without going through
     *  the SSH channel (used for status / error messages). */
    private fun feedBanner(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        session.feed(bytes, bytes.size)
    }

    /** AlertDialog's EditTexts grab focus and pop the IME; once the
     *  dialog dismisses Android does NOT return focus to TerminalView
     *  on its own, so hardware key events fall on the floor. Hide the
     *  soft keyboard, toggle focusable state, and re-request focus —
     *  the toggle is needed because requestFocus on an already-focused-
     *  but-window-detached view is a no-op. */
    private fun returnFocusToTerminal() {
        ui.post {
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(terminalView.windowToken, 0)
            terminalView.isFocusable = false
            terminalView.isFocusable = true
            terminalView.isFocusableInTouchMode = true
            terminalView.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        // Pick up any toggle flips that happened while the terminal
        // was already open — the user might have changed the setting
        // from the phone mid-session.
        applyKeepScreenOnFlag()
    }

    /** Glass EE2's screen-surface temple swipes arrive as raw
     *  MotionEvents on dispatchTouchEvent — same pattern the chat
     *  plugin uses. We hijack them before TerminalView's own gesture
     *  recognizer sees them so the touchpad becomes a scroll/exit
     *  affordance instead of being interpreted as taps on the
     *  emulator. BT keyboard input is unaffected because that flows
     *  through dispatchKeyEvent.
     *
     *  Mapping:
     *    horizontal drag → scroll by rows
     *      swipe forward (right) → scroll toward newer / live
     *      swipe back   (left)  → scroll into history
     *    vertical drag down beyond the gate → confirm-disconnect dialog
     */
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeLastX = 0f

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (handleSwipe(event)) return true
        return super.dispatchTouchEvent(event)
    }

    private fun handleSwipe(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = event.x
                swipeDownY = event.y
                swipeLastX = event.x
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - swipeLastX
                if (kotlin.math.abs(dx) > 4f) {
                    val rowH = (terminalView.mRenderer?.mFontLineSpacing ?: 16)
                        .coerceAtLeast(1)
                    // Pixels traveled / row height = rows to shift.
                    // Forward swipe (dx > 0) → scroll toward live (positive
                    // rows on scrollByRows); back swipe → into history.
                    val rows = (dx / rowH).toInt()
                    if (rows != 0) {
                        terminalView.scrollByRows(rows)
                        swipeLastX += rows * rowH
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - swipeDownX
                val dy = event.y - swipeDownY
                val absDx = kotlin.math.abs(dx)
                val absDy = kotlin.math.abs(dy)
                if (dy > 120 && absDy > absDx * 1.3f) {
                    confirmDisconnect()
                    return true
                }
                // Pure tap or short drag — consume; the terminal doesn't
                // need taps for anything Glass-relevant.
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return false
    }

    /** Two-step swipe-down confirmation. Glass has no positional
     *  touchscreen, so a stock AlertDialog with tappable buttons is
     *  unusable here — the user can't aim at "Stay" vs "Disconnect".
     *  Instead the first swipe-down arms a 4-second window during
     *  which a second swipe-down disconnects; otherwise the
     *  arm self-cancels. The toast announces the gate. */
    private var disconnectArmed = false
    private val disarmRunnable = Runnable {
        disconnectArmed = false
    }

    private fun confirmDisconnect() {
        if (disconnectArmed) {
            ui.removeCallbacks(disarmRunnable)
            finish()
            return
        }
        disconnectArmed = true
        Toast.makeText(
            this,
            "Swipe down again to disconnect",
            Toast.LENGTH_LONG
        ).show()
        ui.postDelayed(disarmRunnable, 4_000L)
    }

    /** Reads the "keep_screen_on" config key from the plugin's normal
     *  (un-encrypted) settings file — same store [PluginConfigHandler]
     *  writes from CONFIG_WRITE on the phone. Defaults to ON since
     *  pretty much any SSH session benefits from a screen that doesn't
     *  blank mid-command. */
    private fun applyKeepScreenOnFlag() {
        val keep = getSharedPreferences(SshPluginService.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("keep_screen_on", true)
        if (keep) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onDestroy() {
        ssh?.disconnect()
        session.finishIfRunning()
        super.onDestroy()
    }

    // --- TerminalView field-level access shim ---
    //
    // TerminalRenderer's font dimensions are package-private — we
    // can't read them directly from outside com.termux.view. These
    // helpers fall back to reasonable defaults if introspection fails;
    // we only care about ballpark values for the initial setPtySize.

    private fun com.termux.view.TerminalRenderer.fontWidthInt(): Int =
        kotlin.math.max(1, this.mFontWidth.toInt())

    private fun com.termux.view.TerminalRenderer.fontHeightInt(): Int =
        kotlin.math.max(1, this.mFontLineSpacing)
}
