package com.glasshole.glassee2.home

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.viewpager2.widget.ViewPager2
import com.glasshole.glass.sdk.GlassPluginConstants
import com.glasshole.glassee2.BluetoothListenerService
import com.glasshole.glassee2.BuildConfig
import com.glasshole.glassee2.R

/**
 * Glass-style card Home. Horizontal ViewPager of cards:
 *   [Notification] ← [Time] → [Media] → [Nav when active]
 *
 * Time is the default center card. Swipe-down anywhere on a main-line
 * card closes Home. Taps on each card open card-specific drawers or
 * overlays — handled by the individual card implementations (milestones
 * M2+).
 *
 * This is the first step of the longer-term "replace the default
 * launcher" plan — for M1 it's just an app in the Glass launcher.
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

    // Idle-sleep timer. Black overlay goes up first (visible confirmation
    // the sleep is happening) then we ask the system to actually power
    // the display down via lockNow if admin is active.
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
            com.glasshole.glassee2.BaseSettings.PREFS, MODE_PRIVATE
        )
        return prefs.getBoolean(
            com.glasshole.glassee2.BaseSettings.KEY_NAV_KEEP_SCREEN_ON, false
        )
    }

    private fun wakeToTimeCardEnabled(): Boolean {
        val prefs = getSharedPreferences(
            com.glasshole.glassee2.BaseSettings.PREFS, MODE_PRIVATE
        )
        return prefs.getBoolean(
            com.glasshole.glassee2.BaseSettings.KEY_WAKE_TO_TIME_CARD, false
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
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == com.glasshole.glassee2.BaseSettings.KEY_NAV_KEEP_SCREEN_ON) {
                updateKeepScreenOn()
            }
        }

    private fun setBrightness(value: Float) {
        val lp = window.attributes
        lp.screenBrightness = value
        window.attributes = lp
    }

    private val notifStoreListener: () -> Unit = {
        // Posted from whatever thread NotificationStore was updated on —
        // BT reader runs off main, so bounce back to UI thread for adapter.
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
                    "NAV_UPDATE" -> {
                        handleNavUpdate(payload)
                        maybeWakeForNavUpdate()
                    }
                    "NAV_END" -> handleNavEnd()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Intentionally no FLAG_KEEP_SCREEN_ON: we want Glass's idle
        // timeout to sleep the display normally. The manifest's
        // showWhenLocked + turnScreenOn flags bring us back to the
        // foreground on wake so the user returns to Home rather than
        // the stock Glass clock.
        setContentView(R.layout.activity_home)

        pager = findViewById(R.id.cardPager)
        mediaOverlay = findViewById(R.id.mediaOverlay)
        sleepOverlay = findViewById(R.id.sleepOverlay)
        cardAdapter = CardAdapter(this)
        pager.adapter = cardAdapter
        pager.offscreenPageLimit = 1
        // Glass's touchpad doesn't stream ACTION_MOVE events reliably on
        // all three variants, so ViewPager2's built-in drag detection
        // misses swipes. We handle all touch input at the Activity level
        // and drive the pager via setCurrentItem.
        pager.isUserInputEnabled = false

        // Open centered on the Time card so the first thing the user sees
        // is the clock, not the leftmost notification placeholder.
        val timeIdx = cardAdapter.positionOf(CardType.TIME)
        if (timeIdx >= 0) pager.setCurrentItem(timeIdx, false)

        // Track the current card so the idle-dim logic knows when the Nav
        // card is in view — the "keep screen on during nav" setting gates
        // the dim timer on this.
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // Any page change also counts as activity so the dim timer
                // re-arms (or stays cancelled while Nav is current).
                resetIdleDim()
                updateKeepScreenOn()
            }
        })

        // Bring up BT + plugin host if nothing else has already. MainActivity
        // does the same thing on entry, but after `pm clear` or a fresh
        // install the user may reach Home first (it's the launcher tile
        // they're most likely to tap), and cards like Media and Nav are
        // useless until BT is up.
        val btIntent = Intent(this, com.glasshole.glassee2.BluetoothListenerService::class.java)
        try { startForegroundService(btIntent) } catch (_: Exception) {}
        try {
            startService(Intent(this, com.glasshole.glassee2.PluginHostService::class.java))
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
     * brightness / screen-timeout / sleep-now / time sync all need
     * WRITE_SETTINGS, which is a runtime-toggled "Modify system settings"
     * permission on API 23+. Prompt once on first run; user taps to open
     * the system settings page, swipe-down to skip.
     */
    private fun maybePromptForWriteSettings() {
        if (HomePrefs.hasPromptedForWriteSettings(this)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return  // pre-23 = install-time
        if (Settings.System.canWrite(this)) return
        HomePrefs.markPromptedForWriteSettings(this)
        AlertDialog.Builder(this)
            .setTitle("Allow system control?")
            .setMessage(
                "Lets the phone app change the glass's brightness, screen " +
                "timeout, time, and put the display to sleep on demand."
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
            .setNegativeButton("Skip") { d, _ -> d.dismiss() }
            .show()
    }

    /**
     * Acquire a brief FULL_WAKE_LOCK when a NAV_UPDATE arrives and the
     * "wake on nav update" setting is on. Duplicated with the same hook
     * in BluetoothListenerService — the service-side wake is sometimes
     * silently no-op'd on API 28+ (background-service screen-wake
     * restrictions), so HomeActivity retries it from a user-facing
     * context where the wake goes through.
     */
    private fun maybeWakeForNavUpdate() {
        val prefs = getSharedPreferences(
            com.glasshole.glassee2.BaseSettings.PREFS, MODE_PRIVATE
        )
        if (!prefs.getBoolean(
                com.glasshole.glassee2.BaseSettings.KEY_NAV_WAKE_ON_UPDATE, false
            )) return
        @Suppress("DEPRECATION")
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isInteractive) return
            val wl = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "GlassHole:NavWakeActivity"
            )
            wl.acquire(3_000L)
        } catch (_: Exception) {}
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
        AlertDialog.Builder(this)
            .setTitle("Allow photo sync?")
            .setMessage(
                "To sync your Glass photos and videos to your phone, " +
                "GlassHole needs access to the device's storage."
            )
            .setCancelable(false)
            .setPositiveButton("Enable") { _, _ ->
                val intent = Intent(
                    this, com.glasshole.glassee2.GalleryPermissionActivity::class.java
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                try { startActivity(intent) } catch (_: Exception) {}
            }
            .setNegativeButton("Skip") { d, _ -> d.dismiss() }
            .show()
    }

    private fun maybePromptForDeviceAdmin() {
        // Standalone never invokes lockNow(); the device-admin receiver
        // isn't even declared in the standalone manifest. Skip the prompt.
        if (BuildConfig.FLAVOR != "launcher") return
        if (HomePrefs.hasPromptedForAdmin(this)) return
        if (dpm.isAdminActive(adminComponent)) return
        HomePrefs.markPromptedForAdmin(this)
        AlertDialog.Builder(this)
            .setTitle("Enable sleep?")
            .setMessage(
                "To put the display to sleep on idle, GlassHole Home needs " +
                "device-admin rights.\n\n" +
                "The only power it gets is locking the screen. Nothing else."
            )
            .setCancelable(false)
            .setPositiveButton("Enable") { _, _ ->
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Lets GlassHole Home sleep the display when idle."
                    )
                }
                try { startActivity(intent) } catch (_: Exception) {}
            }
            .setNegativeButton("Skip") { d, _ -> d.dismiss() }
            .show()
    }

    override fun onStart() {
        super.onStart()
        tickHandler.post(tickRunnable)

        // Catch plugin-bridge broadcasts from BluetoothListenerService for
        // Home-owned plugin IDs (media, nav).
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
        getSharedPreferences(com.glasshole.glassee2.BaseSettings.PREFS, MODE_PRIVATE)
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
            getSharedPreferences(com.glasshole.glassee2.BaseSettings.PREFS, MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(prefsChangeListener)
        } catch (_: Exception) {}
    }

    private fun refreshFromPhone() {
        sendPluginMessageToPhone("media", "REFRESH", "")
        // Nav plugin dedupes identical updates, so if a trip is already in
        // progress when Home opens we'd never see a NAV_UPDATE without
        // explicitly poking the phone to replay.
        sendPluginMessageToPhone("nav", "REFRESH", "")
    }

    private fun handleNavUpdate(payload: String) {
        val newState = NavState.fromJson(payload, cardAdapter.navState)
        val cardWasNew = !cardAdapter.hasCard(CardType.NAV)
        if (cardWasNew) {
            // Nav card lives at the end of the main line (after Media).
            cardAdapter.addCard(CardType.NAV, cardAdapter.itemCount)
        }
        cardAdapter.setNavState(newState)
        // Auto-jump the user to the new Nav card the first time the trip
        // update arrives so they see turn instructions without having to
        // swipe there manually. Subsequent updates don't steal focus away
        // from whatever card they're on.
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

    /**
     * Swipe-down / Back handler for the main-line cards.
     *
     * EXIT card always exits — standalone finish()es, launcher hands
     * control back to the stock Glass home explicitly (finish() would
     * just be re-launched by the system since GlassHole is HOME).
     *
     * Non-EXIT cards: launcher sleeps the display, standalone consumes
     * silently to prevent accidental back-out while paging.
     */
    private fun exitOrSleep() {
        if (cardAdapter.cardAt(pager.currentItem) == CardType.EXIT) {
            if (BuildConfig.FLAVOR == "launcher") launchStockHome() else finish()
            return
        }
        if (BuildConfig.FLAVOR == "launcher") sleepGlass()
    }

    /** Launch Glass's stock home explicitly. Bypasses default-HOME resolution
     *  (which would loop us back to GlassHole) by setting an explicit
     *  ComponentName. */
    private fun launchStockHome() {
        try {
            startActivity(
                Intent().apply {
                    component = ComponentName(
                        "com.google.glass.nowtown",
                        "com.google.glass.nowtown.home.HomeActivity"
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
        try {
            if (dpm.isAdminActive(adminComponent)) {
                dpm.lockNow()
                return
            }
        } catch (_: Exception) {}
        BluetoothListenerService.instance?.sleepGlass()
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
        // EE1 / XE route SOURCE_TOUCHPAD events here. EE2 goes through the
        // key-event path above instead (GDK gesture detector converts
        // temple swipes directly to TAB / SHIFT+TAB / BACK keys).
        if (event != null && handleGesture(event)) return true
        return super.onGenericMotionEvent(event)
    }

    private fun handleGesture(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val absDx = kotlin.math.abs(dx)
                val absDy = kotlin.math.abs(dy)

                // Swipe down → close Home (standalone) or sleep the glass
                // (launcher — finish()ing a launcher is a no-op). Wins over
                // horizontal when clearly vertical.
                if (dy > 120 && absDy > absDx * 1.3f) {
                    exitOrSleep()
                    return true
                }

                // Horizontal swipe → advance / retreat one card. Threshold
                // intentionally low (60px) since the EE2 temple touchpad
                // registers shorter swipes than a phone screen.
                if (absDx > 60 && absDx > absDy) {
                    val current = pager.currentItem
                    val delta = if (dx > 0) 1 else -1
                    val target = (current + delta).coerceIn(0, cardAdapter.itemCount - 1)
                    if (target != current) pager.setCurrentItem(target, true)
                    return true
                }

                // Tap detection — reserved for card-specific openers that
                // later milestones wire up (app drawer, media controls,
                // notification drawer). Consuming so it doesn't fall
                // through to child click listeners.
                if (absDx < 25 && absDy < 25) {
                    return true
                }
            }
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Any key event counts as user activity — reset the dim timer.
        resetIdleDim()

        // When the Media controls overlay is up it gets first dibs on all
        // input. Temple swipes and taps drive playback commands instead of
        // card navigation; swipe-down dismisses just the overlay.
        if (mediaOverlayVisible) return handleMediaOverlayKey(keyCode, event)

        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { exitOrSleep(); true }
            // Glass EE2 maps swipe-forward to TAB and swipe-back to
            // SHIFT+TAB (Android's "previous focus" convention). XE/EE1
            // may send DPAD_LEFT / DPAD_RIGHT directly — keep those too.
            KeyEvent.KEYCODE_TAB -> {
                if (event?.isShiftPressed == true) navigateCard(-1) else navigateCard(1)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { navigateCard(1); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { navigateCard(-1); true }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                // Consume the shift modifier that arrives just before the
                // TAB in the swipe-back combo — nothing to do, but we don't
                // want the framework to treat it as focus navigation.
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                handleCardTap()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun handleCardTap() {
        val current = pager.currentItem
        when (cardAdapter.cardAt(current)) {
            CardType.TIME -> startActivity(Intent(this, AppDrawerActivity::class.java))
            CardType.MEDIA -> if (cardAdapter.mediaState.hasSession) showMediaOverlay()
            CardType.NOTIFICATION -> if (NotificationStore.count() > 0) {
                startActivity(Intent(this, NotificationDrawerActivity::class.java))
            }
            CardType.SETTINGS -> startActivity(Intent(this, SettingsDrawerActivity::class.java))
            // EXIT card explicitly does NOT close on tap — only swipe-down
            // counts. The hint text on the card says so. This avoids a
            // stray tap closing Home when the user just meant to confirm
            // they're on the right tile.
            CardType.EXIT -> Unit
            else -> Unit
        }
    }

    private fun handleMediaOverlayKey(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { hideMediaOverlay(); true }
            KeyEvent.KEYCODE_TAB -> {
                val which = if (event?.isShiftPressed == true) "PREV" else "NEXT"
                sendPluginMessageToPhone("media", which, "")
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                sendPluginMessageToPhone("media", "NEXT", ""); true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                sendPluginMessageToPhone("media", "PREV", ""); true
            }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> true
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                sendPluginMessageToPhone("media", "TOGGLE", "")
                true
            }
            else -> true // don't let anything leak past the overlay
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
