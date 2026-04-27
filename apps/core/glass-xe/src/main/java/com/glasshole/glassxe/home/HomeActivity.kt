package com.glasshole.glassxe.home

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.viewpager2.widget.ViewPager2
import com.glasshole.glass.sdk.GlassPluginConstants
import com.glasshole.glassxe.BluetoothListenerService
import com.glasshole.glassxe.BuildConfig
import com.glasshole.glassxe.R

/**
 * Glass-style card Home. Horizontal ViewPager of cards:
 *   [Settings] ← [Notification] ← [Time] → [Media] → [Nav when active]
 *
 * Time is the default center card. Swipe-down anywhere on a main-line
 * card closes Home. Taps on each card open card-specific drawers or
 * overlays.
 */
class HomeActivity : Activity() {

    companion object {
        private const val TAG = "HomeActivity"
        private const val TICK_INTERVAL_MS = 1_000L
        /**
         * Inactivity before we dim the display to nearly off. OLED black
         * emits no light so at brightness 0.01 the screen is effectively
         * asleep while the activity stays foreground — which is what we
         * want so that waking it (via any touchpad input) doesn't dump
         * the user back onto Glass's stock clock.
         */
        private const val IDLE_DIM_MS = 30_000L
        private const val DIM_BRIGHTNESS = 0.0f
    }

    private lateinit var pager: ViewPager2
    private lateinit var cardAdapter: CardAdapter
    private lateinit var mediaOverlay: View
    private lateinit var sleepOverlay: View
    private var mediaOverlayVisible: Boolean = false

    // Swipe-down detection (same pattern the plugins use). We track in
    // dispatchTouchEvent so ViewPager2 still gets its horizontal drag
    // events — we only consume the UP event when it was a vertical swipe.
    private var downX = 0f
    private var downY = 0f

    private val tickHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            cardAdapter.refreshTimeCard()
            cardAdapter.refreshMediaProgress()
            tickHandler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    // Device-admin handle for DevicePolicyManager.lockNow(). Only works
    // if the user has previously activated HomeDeviceAdminReceiver via
    // the "Enable sleep" first-run dialog. If not, we fall back to the
    // black overlay so the screen at least *looks* off.
    private val dpm: DevicePolicyManager by lazy {
        getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }
    private val adminComponent: ComponentName by lazy {
        ComponentName(this, HomeDeviceAdminReceiver::class.java)
    }

    private var dimmed: Boolean = false
    private val dimRunnable = Runnable {
        sleepOverlay.visibility = View.VISIBLE
        setBrightness(DIM_BRIGHTNESS)
        dimmed = true
        try {
            if (dpm.isAdminActive(adminComponent)) {
                dpm.lockNow()
            }
        } catch (_: Exception) {}
    }

    private fun resetIdleDim() {
        tickHandler.removeCallbacks(dimRunnable)
        if (dimmed) {
            setBrightness(-1f) // system default
            sleepOverlay.visibility = View.GONE
            dimmed = false
            if (wakeToTimeCardEnabled()) snapToTimeCard()
        }
        // "Keep screen on while nav is visible" — when the Nav card is the
        // current page and the user has enabled the setting, never re-arm
        // the dim timer. Swiping to a different card cancels this via the
        // pager's onPageSelected callback, which calls resetIdleDim again.
        if (isNavCardHeld()) return
        tickHandler.postDelayed(dimRunnable, IDLE_DIM_MS)
    }

    private fun isNavCardHeld(): Boolean {
        val navIdx = cardAdapter.positionOf(CardType.NAV)
        if (navIdx < 0 || pager.currentItem != navIdx) return false
        val prefs = getSharedPreferences(
            com.glasshole.glassxe.BaseSettings.PREFS, MODE_PRIVATE
        )
        return prefs.getBoolean(
            com.glasshole.glassxe.BaseSettings.KEY_NAV_KEEP_SCREEN_ON, false
        )
    }

    private fun wakeToTimeCardEnabled(): Boolean {
        val prefs = getSharedPreferences(
            com.glasshole.glassxe.BaseSettings.PREFS, MODE_PRIVATE
        )
        return prefs.getBoolean(
            com.glasshole.glassxe.BaseSettings.KEY_WAKE_TO_TIME_CARD, false
        )
    }

    private fun snapToTimeCard() {
        if (mediaOverlayVisible) hideMediaOverlay()
        val timeIdx = cardAdapter.positionOf(CardType.TIME)
        if (timeIdx >= 0) pager.setCurrentItem(timeIdx, false)
    }

    // Watches the system's screen-on/off broadcasts so we can snap the Home
    // carousel back to the Time card on every wake (when the user has opted
    // in via the phone-side toggle). onStart/onStop alone can't tell us this
    // — they also fire when a drawer activity covers Home, where we want to
    // preserve the user's card position.
    private var screenWasOff = false
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> screenWasOff = true
                Intent.ACTION_SCREEN_ON -> if (screenWasOff) {
                    screenWasOff = false
                    if (wakeToTimeCardEnabled()) snapToTimeCard()
                }
            }
        }
    }

    /**
     * Reflect the current "keep nav screen on" setting onto the window. Our
     * own dim timer's `isNavCardHeld()` gate is not enough — the system's
     * SCREEN_OFF_TIMEOUT still fires independently. FLAG_KEEP_SCREEN_ON is
     * the only thing that suppresses both our dim and the system timeout
     * while the nav card is in view.
     */
    private fun updateKeepScreenOn() {
        if (isNavCardHeld()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private val prefsChangeListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == com.glasshole.glassxe.BaseSettings.KEY_NAV_KEEP_SCREEN_ON) {
                updateKeepScreenOn()
            }
        }

    private fun setBrightness(value: Float) {
        val lp = window.attributes
        lp.screenBrightness = value
        window.attributes = lp
    }

    private val notifStoreListener: () -> Unit = {
        runOnUiThread { cardAdapter.refreshNotificationCard() }
    }

    private val pluginMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pluginId = intent.getStringExtra(GlassPluginConstants.EXTRA_PLUGIN_ID) ?: return
            val type = intent.getStringExtra(GlassPluginConstants.EXTRA_MESSAGE_TYPE) ?: return
            val payload = intent.getStringExtra(GlassPluginConstants.EXTRA_PAYLOAD) ?: ""
            when (pluginId) {
                "media" -> if (type == "NOW_PLAYING") {
                    val newState = MediaState.fromJson(payload, cardAdapter.mediaState)
                    cardAdapter.setMediaState(newState)
                    if (mediaOverlayVisible) refreshMediaOverlayText()
                }
                "nav" -> when (type) {
                    "NAV_UPDATE" -> handleNavUpdate(payload)
                    "NAV_END" -> handleNavEnd()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // EE1 runs Android 4.4.4 (API 19, same as XE) — well below the
        // API 27 minimum for android:showWhenLocked / turnScreenOn
        // manifest attrs. Use the WindowManager flag equivalents so
        // Home redraws on top when the screen wakes instead of dumping
        // to the stock Glass clock.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        setContentView(R.layout.activity_home)

        pager = findViewById(R.id.cardPager)
        mediaOverlay = findViewById(R.id.mediaOverlay)
        sleepOverlay = findViewById(R.id.sleepOverlay)
        cardAdapter = CardAdapter(this)
        pager.adapter = cardAdapter
        pager.offscreenPageLimit = 1
        // Glass's touchpad doesn't stream ACTION_MOVE events reliably on
        // EE1 / XE, so ViewPager2's built-in drag detection misses swipes.
        // We handle all touch input at the Activity level and drive the
        // pager via setCurrentItem.
        pager.isUserInputEnabled = false

        // Open centered on the Time card so the first thing the user sees
        // is the clock, not the leftmost Settings / Notification card.
        val timeIdx = cardAdapter.positionOf(CardType.TIME)
        if (timeIdx >= 0) pager.setCurrentItem(timeIdx, false)

        // Track the current card so the idle-dim logic knows when the Nav
        // card is in view — the "keep screen on during nav" setting gates
        // the dim timer on this.
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                resetIdleDim()
                updateKeepScreenOn()
            }
        })

        // Bring up BT + plugin host if nothing else has already. MainActivity
        // does the same thing on entry, but after `pm clear` or a fresh
        // install the user may reach Home first (it's the launcher tile
        // they're most likely to tap), and cards like Media and Nav are
        // useless until BT is up.
        val btIntent = Intent(this, com.glasshole.glassxe.BluetoothListenerService::class.java)
        try { startServiceCompat(btIntent) } catch (_: Exception) {}
        try {
            startService(Intent(this, com.glasshole.glassxe.PluginHostService::class.java))
        } catch (_: Exception) {}

        // Persistent across activity pause/stop so we still see SCREEN_OFF
        // when the device admin lockNow() pulls us out of foreground.
        try {
            registerReceiver(screenStateReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            })
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(screenStateReceiver) } catch (_: Exception) {}
    }

    /** startForegroundService is API 26+; EE1 runs API 19, fall back to
     *  plain startService where background-service restrictions aren't
     *  in force yet. */
    private fun startServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        resetIdleDim()
        updateKeepScreenOn()
        maybePromptForDeviceAdmin()
        maybePromptForStorageAccess()
        maybePromptForWriteSettings()
    }

    /**
     * Folded in from the retired plugin-device-glass APK's DeviceAccessActivity:
     * brightness / screen-timeout / sleep-now / time sync need WRITE_SETTINGS
     * (runtime-toggled on API 23+; install-time on KitKat XE — the canWrite()
     * branch silently returns true via the SDK_INT guard so no prompt fires).
     */
    private fun maybePromptForWriteSettings() {
        if (HomePrefs.hasPromptedForWriteSettings(this)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (Settings.System.canWrite(this)) return
        HomePrefs.markPromptedForWriteSettings(this)
        AlertDialog.Builder(this)
            .setTitle("Allow system control?")
            .setMessage(
                "Lets the phone app change the glass's brightness, screen " +
                "timeout, time, and put the display to sleep on demand.\n\n" +
                "Tap to enable · swipe down to skip."
            )
            .setCancelable(true)
            .setPositiveButton("Enable") { _, _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                            data = android.net.Uri.parse("package:$packageName")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                } catch (_: Exception) {}
            }
            .create().also { dialog ->
                dialog.show()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
            }
    }

    private fun maybePromptForStorageAccess() {
        if (HomePrefs.hasPromptedForStorage(this)) return
        val pm = packageManager
        val read = pm.checkPermission(
            android.Manifest.permission.READ_EXTERNAL_STORAGE, packageName
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val write = pm.checkPermission(
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE, packageName
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (read && write) return
        HomePrefs.markPromptedForStorage(this)
        // XE convention: tap = confirm (focused positive button),
        // swipe-down = cancel/skip (BACK key dismisses the dialog when
        // setCancelable=true). The two-button DPAD-navigation pattern
        // doesn't translate well to XE's touchpad-only input — there's
        // no way to swipe focus between buttons.
        val dialog = AlertDialog.Builder(this)
            .setTitle("Allow photo sync?")
            .setMessage(
                "To sync your Glass photos and videos to your phone, " +
                "GlassHole needs access to the device's storage.\n\n" +
                "Tap to enable · swipe down to skip."
            )
            .setCancelable(true)
            .setPositiveButton("Enable") { _, _ ->
                val intent = Intent(
                    this, com.glasshole.glassxe.GalleryPermissionActivity::class.java
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                try { startActivity(intent) } catch (_: Exception) {}
            }
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }

    private fun maybePromptForDeviceAdmin() {
        // Standalone never invokes lockNow(); the device-admin receiver
        // isn't even declared in the standalone manifest. Skip the prompt.
        if (BuildConfig.FLAVOR != "launcher") return
        if (HomePrefs.hasPromptedForAdmin(this)) return
        if (dpm.isAdminActive(adminComponent)) return
        HomePrefs.markPromptedForAdmin(this)
        // Same pattern as the storage prompt — see comment there.
        val dialog = AlertDialog.Builder(this)
            .setTitle("Enable sleep?")
            .setMessage(
                "To put the display to sleep on idle, GlassHole needs " +
                "device-admin rights.\n\n" +
                "The only power it gets is locking the screen. Nothing else.\n\n" +
                "Tap to enable · swipe down to skip."
            )
            .setCancelable(true)
            .setPositiveButton("Enable") { _, _ ->
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Lets GlassHole sleep the display when idle."
                    )
                }
                try { startActivity(intent) } catch (_: Exception) {}
            }
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        tickHandler.post(tickRunnable)

        val filter = IntentFilter(GlassPluginConstants.ACTION_MESSAGE_FROM_PHONE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(pluginMessageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pluginMessageReceiver, filter)
        }

        NotificationStore.addListener(notifStoreListener)
        cardAdapter.refreshNotificationCard()
        refreshFromPhone()
        resetIdleDim()

        // Track setting toggles from the phone so the keep-screen-on flag
        // reacts immediately instead of waiting for the next page change.
        getSharedPreferences(com.glasshole.glassxe.BaseSettings.PREFS, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsChangeListener)
        updateKeepScreenOn()
    }

    override fun onStop() {
        super.onStop()
        tickHandler.removeCallbacks(tickRunnable)
        tickHandler.removeCallbacks(dimRunnable)
        try { unregisterReceiver(pluginMessageReceiver) } catch (_: Exception) {}
        NotificationStore.removeListener(notifStoreListener)
        try {
            getSharedPreferences(com.glasshole.glassxe.BaseSettings.PREFS, MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(prefsChangeListener)
        } catch (_: Exception) {}
    }

    private fun refreshFromPhone() {
        sendPluginMessageToPhone("media", "REFRESH", "")
        sendPluginMessageToPhone("nav", "REFRESH", "")
    }

    private fun handleNavUpdate(payload: String) {
        val newState = NavState.fromJson(payload, cardAdapter.navState)
        val cardWasNew = !cardAdapter.hasCard(CardType.NAV)
        if (cardWasNew) {
            cardAdapter.addCard(CardType.NAV, cardAdapter.itemCount)
        }
        cardAdapter.setNavState(newState)
        if (cardWasNew) {
            val idx = cardAdapter.positionOf(CardType.NAV)
            if (idx >= 0) pager.setCurrentItem(idx, true)
        }
    }

    private fun handleNavEnd() {
        cardAdapter.setNavState(NavState.EMPTY)
        if (cardAdapter.hasCard(CardType.NAV)) {
            cardAdapter.removeCard(CardType.NAV)
        }
    }

    private fun sendPluginMessageToPhone(pluginId: String, type: String, payload: String) {
        val intent = Intent("com.glasshole.glass.MESSAGE_TO_PHONE").apply {
            setPackage(packageName)
            putExtra("plugin_id", pluginId)
            putExtra("message_type", type)
            putExtra("payload", payload)
        }
        sendBroadcast(intent)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (handleGesture(event)) return true
        return super.dispatchTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        // EE1 (Android 6) and XE (Android 4.4) deliver SOURCE_TOUCHPAD
        // motion events here. On EE2 the GDK gesture detector converts
        // those same gestures to TAB/SHIFT+TAB/BACK/DPAD_CENTER keys
        // that we handle via onKeyDown — both paths route to the same
        // action* methods below for consistency.
        if (event != null && handleGesture(event)) return true
        return super.onGenericMotionEvent(event)
    }

    private fun handleGesture(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                resetIdleDim()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val absDx = kotlin.math.abs(dx)
                val absDy = kotlin.math.abs(dy)
                if (dy > 120 && absDy > absDx * 1.3f) { actionBack(); return true }
                if (absDx > 60 && absDx > absDy) {
                    if (dx > 0) actionNext() else actionPrev()
                    return true
                }
                if (absDx < 25 && absDy < 25) { actionTap(); return true }
            }
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        resetIdleDim()
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { actionBack(); true }
            KeyEvent.KEYCODE_TAB -> {
                if (event?.isShiftPressed == true) actionPrev() else actionNext()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { actionNext(); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { actionPrev(); true }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> true
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { actionTap(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // --- Shared action dispatchers. Both key and motion paths call these,
    // and each respects the current overlay state so a tap means
    // "play/pause" when the media overlay is up, vs "open drawer" when it
    // isn't.

    private fun actionBack() {
        if (mediaOverlayVisible) { hideMediaOverlay(); return }
        // EXIT card always exits — standalone finish()es, launcher hands
        // control back to the stock Glass home explicitly (finish() would
        // just be re-launched by the system since GlassHole is HOME).
        if (cardAdapter.cardAt(pager.currentItem) == CardType.EXIT) {
            if (BuildConfig.FLAVOR == "launcher") launchStockHome() else finish()
            return
        }
        // Non-EXIT cards: launcher sleeps; standalone consumes silently so
        // the user doesn't accidentally back out while paging.
        if (BuildConfig.FLAVOR == "launcher") sleepGlass()
    }

    /** Launch Glass's stock home explicitly. Bypasses default-HOME resolution
     *  (which would loop us back to GlassHole) by setting an explicit
     *  ComponentName. XE's stock home is com.google.glass.home (timeline);
     *  EE1 uses nowtown. */
    private fun launchStockHome() {
        try {
            startActivity(
                Intent().apply {
                    component = ComponentName(
                        "com.google.glass.home",
                        "com.google.glass.home.timeline.MainTimelineActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {
            sleepGlass()
        }
    }

    /**
     * Instant sleep for the launcher variant. Uses device-admin lockNow()
     * first (same path the idle-dim timer uses) since it's immediate; falls
     * back to the device plugin's SLEEP_NOW (which bumps SCREEN_OFF_TIMEOUT
     * via WRITE_SETTINGS) when admin hasn't been accepted yet.
     */
    private fun sleepGlass() {
        // Clear FLAG_KEEP_SCREEN_ON first. If the user was on the Nav card
        // with keep-screen-on enabled, the flag holds the screen on
        // regardless of timeout, so sleep silently no-ops. The flag gets
        // re-applied by updateKeepScreenOn() next time Home resumes.
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 1. Device admin lockNow — instant when admin is enabled.
        try {
            if (dpm.isAdminActive(adminComponent)) {
                dpm.lockNow()
                android.util.Log.i(TAG, "sleepGlass: lockNow")
                return
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "sleepGlass: lockNow failed: ${e.message}")
        }

        // 2. PowerManager.goToSleep via reflection — @hide / system-only,
        //    but it often works on Glass XE platform images. Cheap to try
        //    and silently no-ops if blocked.
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val m = pm.javaClass.getMethod("goToSleep", Long::class.javaPrimitiveType)
            m.invoke(pm, android.os.SystemClock.uptimeMillis())
            android.util.Log.i(TAG, "sleepGlass: goToSleep (reflected)")
            return
        } catch (e: Throwable) {
            android.util.Log.d(TAG, "sleepGlass: goToSleep reflection failed: ${e.message}")
        }

        // 3. Try to drop SCREEN_OFF_TIMEOUT via WRITE_SETTINGS. Honored on
        //    EE2 but Glass XE's custom power manager ignores changes to this
        //    setting, so this is best-effort.
        BluetoothListenerService.instance?.sleepGlass()

        // 4. Fallback fake-sleep: Glass XE doesn't expose any non-privileged
        //    sleep API and ignores SCREEN_OFF_TIMEOUT. Trigger the same
        //    dim-overlay path the idle-dim timer uses — brightness 0 + a
        //    fullscreen black overlay. Not real screen-off (BT, sensors,
        //    backlight stay on), but the OLED panel emits no light so it
        //    looks asleep, and any touchpad input wakes us via resetIdleDim.
        tickHandler.removeCallbacks(dimRunnable)
        tickHandler.post(dimRunnable)
    }

    private fun actionNext() {
        if (mediaOverlayVisible) {
            sendPluginMessageToPhone("media", "NEXT", "")
        } else navigateCard(1)
    }

    private fun actionPrev() {
        if (mediaOverlayVisible) {
            sendPluginMessageToPhone("media", "PREV", "")
        } else navigateCard(-1)
    }

    private fun actionTap() {
        if (mediaOverlayVisible) {
            sendPluginMessageToPhone("media", "TOGGLE", "")
            return
        }
        val current = pager.currentItem
        when (cardAdapter.cardAt(current)) {
            CardType.TIME -> startActivity(Intent(this, AppDrawerActivity::class.java))
            CardType.MEDIA -> if (cardAdapter.mediaState.hasSession) showMediaOverlay()
            CardType.NOTIFICATION -> if (NotificationStore.count() > 0) {
                startActivity(Intent(this, NotificationDrawerActivity::class.java))
            }
            CardType.SETTINGS -> startActivity(Intent(this, SettingsDrawerActivity::class.java))
            // EXIT card explicitly does NOT close on tap — only swipe-down
            // counts. The hint text on the card says so.
            CardType.EXIT -> Unit
            else -> Unit
        }
    }

    private fun showMediaOverlay() {
        refreshMediaOverlayText()
        mediaOverlay.visibility = View.VISIBLE
        mediaOverlayVisible = true
    }

    private fun hideMediaOverlay() {
        mediaOverlay.visibility = View.GONE
        mediaOverlayVisible = false
    }

    private fun refreshMediaOverlayText() {
        val state = cardAdapter.mediaState
        mediaOverlay.findViewById<TextView>(R.id.overlayTitle)?.text =
            state.title.ifEmpty { "—" }
        mediaOverlay.findViewById<TextView>(R.id.overlayArtist)?.text = state.artist
        mediaOverlay.findViewById<android.widget.ImageView>(R.id.overlayPlayPause)
            ?.setImageResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun navigateCard(delta: Int) {
        val current = pager.currentItem
        val target = (current + delta).coerceIn(0, cardAdapter.itemCount - 1)
        if (target != current) pager.setCurrentItem(target, true)
    }
}
