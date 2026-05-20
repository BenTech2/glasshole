package com.glasshole.glassee1.home

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
import com.glasshole.glassee1.BluetoothListenerService
import com.glasshole.glassee1.BuildConfig
import com.glasshole.glassee1.R

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
         * Coalesce window for NOW_PLAYING updates. When the user swipe-
         * preves multiple times quickly, the phone emits one metadata
         * update per intermediate track; if we render each one, the
         * overlay (and the media card behind it) flashes the title +
         * cover art of every track passed through. Buffer the latest
         * payload and only commit it after this much quiet so the user
         * sees just the settled track.
         */
        private const val MEDIA_RENDER_DEBOUNCE_MS = 300L
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
    private lateinit var backgroundImage: android.widget.ImageView
    private lateinit var backgroundFade: View
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

    private fun wakeToTimeCardEnabled(): Boolean {
        val prefs = getSharedPreferences(
            com.glasshole.glassee1.BaseSettings.PREFS, MODE_PRIVATE
        )
        return prefs.getBoolean(
            com.glasshole.glassee1.BaseSettings.KEY_WAKE_TO_TIME_CARD, false
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

    private fun isNavCardHeld(): Boolean {
        val navIdx = cardAdapter.positionOf(CardType.NAV)
        if (navIdx < 0 || pager.currentItem != navIdx) return false
        val prefs = getSharedPreferences(
            com.glasshole.glassee1.BaseSettings.PREFS, MODE_PRIVATE
        )
        return prefs.getBoolean(
            com.glasshole.glassee1.BaseSettings.KEY_NAV_KEEP_SCREEN_ON, false
        )
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
            when (key) {
                com.glasshole.glassee1.BaseSettings.KEY_NAV_KEEP_SCREEN_ON -> updateKeepScreenOn()
                com.glasshole.glassee1.BaseSettings.KEY_BACKGROUND_FADE -> applyBackgroundFade()
            }
        }

    /** Read the fade slider value (0..255) from prefs and apply it to
     *  the black overlay covering the wallpaper. 0 = no fade,
     *  255 = solid black (wallpaper effectively hidden). */
    private fun applyBackgroundFade() {
        val alpha = getSharedPreferences(
            com.glasshole.glassee1.BaseSettings.PREFS, MODE_PRIVATE
        ).getInt(com.glasshole.glassee1.BaseSettings.KEY_BACKGROUND_FADE, 0)
            .coerceIn(0, 255)
        if (alpha == 0) {
            backgroundFade.visibility = View.GONE
        } else {
            backgroundFade.visibility = View.VISIBLE
            backgroundFade.alpha = alpha / 255f
        }
    }

    /** Make sure the wallpaper directory exists so adb push works on a
     *  fresh device without the user having to mkdir it first, and so a
     *  failed phone upload doesn't leave a confusing "no folder" state.
     *  Best-effort: storage permission may not yet be granted, in which
     *  case we silently fail and the dir gets created on first
     *  successful BG_UPLOAD instead. */
    private fun ensureWallpaperDir() {
        try {
            val dir = java.io.File("/sdcard/GlassHole/backgrounds")
            if (!dir.exists()) dir.mkdirs()
        } catch (_: Exception) {
        }
    }

    /** Pull the first JPEG/PNG out of /sdcard/GlassHole/backgrounds/
     *  on a worker thread, sub-sample to the display dimensions, and
     *  install it into the backgroundImage view. Wallpaper-tier work
     *  has no business on the main thread; this runs once on each
     *  onStart so dropping a new file via adb + reopening Home picks
     *  it up. */
    private fun loadBackgroundAsync() {
        val dm = resources.displayMetrics
        val targetW = dm.widthPixels
        val targetH = dm.heightPixels
        Thread {
            val bmp = try {
                decodeFirstWallpaper(targetW, targetH)
            } catch (t: Throwable) {
                android.util.Log.w("GlassHoleHome", "Wallpaper decode threw: ${t.message}")
                null
            }
            runOnUiThread {
                if (bmp != null) {
                    android.util.Log.i("GlassHoleHome", "Wallpaper loaded: ${bmp.width}x${bmp.height}")
                    backgroundImage.setImageBitmap(bmp)
                    backgroundImage.visibility = View.VISIBLE
                } else {
                    android.util.Log.i("GlassHoleHome", "Wallpaper: no image installed")
                    backgroundImage.setImageBitmap(null)
                    backgroundImage.visibility = View.GONE
                }
            }
        }.apply { isDaemon = true; name = "HomeBgLoader" }.start()
    }

    private fun decodeFirstWallpaper(targetW: Int, targetH: Int): android.graphics.Bitmap? {
        val dir = java.io.File("/sdcard/GlassHole/backgrounds")
        android.util.Log.i(
            "GlassHoleHome",
            "Wallpaper dir=$dir exists=${dir.exists()} isDir=${dir.isDirectory} " +
                "canRead=${dir.canRead()}"
        )
        if (!dir.isDirectory) return null
        val files = dir.listFiles()
        android.util.Log.i(
            "GlassHoleHome",
            "Wallpaper listing: ${files?.joinToString { it.name } ?: "(null)"}"
        )
        // Most-recently-modified wins, so the latest upload from the
        // phone-side picker is always the active wallpaper without
        // requiring the user to manage filenames.
        val candidate = files
            ?.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            ?.maxByOrNull { it.lastModified() }
            ?: return null
        android.util.Log.i("GlassHoleHome", "Wallpaper picked: ${candidate.absolutePath}")
        // Two-pass decode: first inJustDecodeBounds to read the source
        // dimensions, then re-decode with inSampleSize so we don't
        // blow Glass's tiny heap on a phone-resolution wallpaper.
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(candidate.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > targetW * 2 ||
            bounds.outHeight / sample > targetH * 2
        ) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        return android.graphics.BitmapFactory.decodeFile(candidate.absolutePath, opts)
    }

    private fun setBrightness(value: Float) {
        val lp = window.attributes
        lp.screenBrightness = value
        window.attributes = lp
    }

    private val notifStoreListener: () -> Unit = {
        runOnUiThread { cardAdapter.refreshNotificationCard() }
    }

    /** Fired by BluetoothListenerService after a successful
     *  wallpaper upload from the phone. Triggers an immediate
     *  re-scan of the backgrounds directory so the new image
     *  appears without the user backing out of Home. */
    private val wallpaperChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            loadBackgroundAsync()
        }
    }

    /** Buffer of the latest unrendered NOW_PLAYING payload while the
     *  debounce window is open. See the EE2 copy for the design note. */
    private var pendingMediaPayload: String? = null
    private val applyMediaRunnable = Runnable {
        val payload = pendingMediaPayload ?: return@Runnable
        pendingMediaPayload = null
        val newState = MediaState.fromJson(payload, cardAdapter.mediaState)
        cardAdapter.setMediaState(newState)
        if (mediaOverlayVisible) refreshMediaOverlayText()
    }

    private val pluginMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pluginId = intent.getStringExtra(GlassPluginConstants.EXTRA_PLUGIN_ID) ?: return
            val type = intent.getStringExtra(GlassPluginConstants.EXTRA_MESSAGE_TYPE) ?: return
            val payload = intent.getStringExtra(GlassPluginConstants.EXTRA_PAYLOAD) ?: ""
            when (pluginId) {
                "media" -> if (type == "NOW_PLAYING") {
                    pendingMediaPayload = payload
                    tickHandler.removeCallbacks(applyMediaRunnable)
                    tickHandler.postDelayed(applyMediaRunnable, MEDIA_RENDER_DEBOUNCE_MS)
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
        ensureWallpaperDir()
        setContentView(R.layout.activity_home)

        pager = findViewById(R.id.cardPager)
        mediaOverlay = findViewById(R.id.mediaOverlay)
        sleepOverlay = findViewById(R.id.sleepOverlay)
        backgroundImage = findViewById(R.id.backgroundImage)
        backgroundFade = findViewById(R.id.backgroundFade)
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
        val btIntent = Intent(this, com.glasshole.glassee1.BluetoothListenerService::class.java)
        try { startServiceCompat(btIntent) } catch (_: Exception) {}
        try {
            startService(Intent(this, com.glasshole.glassee1.PluginHostService::class.java))
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
     * (runtime-toggled on API 23+; install-time on older Glass builds).
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
        // EE1 / XE convention: tap = confirm (focused positive button),
        // swipe-down = cancel/skip (BACK key dismisses with
        // setCancelable=true). The two-button DPAD-navigation pattern
        // doesn't translate well to EE1's touchpad-only input — there's
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
                    this, com.glasshole.glassee1.GalleryPermissionActivity::class.java
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

        // Live reload when the phone uploads a new wallpaper —
        // BluetoothListenerService fires this broadcast after writing
        // the file so the user sees the change without backing out
        // and re-opening Home.
        val wallpaperFilter = IntentFilter("com.glasshole.glass.WALLPAPER_CHANGED")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(wallpaperChangedReceiver, wallpaperFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(wallpaperChangedReceiver, wallpaperFilter)
        }

        NotificationStore.addListener(notifStoreListener)
        cardAdapter.refreshNotificationCard()
        refreshFromPhone()
        resetIdleDim()

        // Track setting toggles from the phone so the keep-screen-on flag
        // reacts immediately instead of waiting for the next page change.
        getSharedPreferences(com.glasshole.glassee1.BaseSettings.PREFS, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsChangeListener)
        updateKeepScreenOn()
        applyBackgroundFade()
        loadBackgroundAsync()
    }

    override fun onStop() {
        super.onStop()
        tickHandler.removeCallbacks(tickRunnable)
        tickHandler.removeCallbacks(dimRunnable)
        tickHandler.removeCallbacks(applyMediaRunnable)
        try { unregisterReceiver(pluginMessageReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(wallpaperChangedReceiver) } catch (_: Exception) {}
        NotificationStore.removeListener(notifStoreListener)
        try {
            getSharedPreferences(com.glasshole.glassee1.BaseSettings.PREFS, MODE_PRIVATE)
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
                    val forward = (dx > 0) xor com.glasshole.glassee1.BaseSettings.isNavInverted(this)
                    if (forward) actionNext() else actionPrev()
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
                val isForward = (event?.isShiftPressed != true)
                    .xor(com.glasshole.glassee1.BaseSettings.isNavInverted(this))
                if (isForward) actionNext() else actionPrev()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (com.glasshole.glassee1.BaseSettings.isNavInverted(this)) actionPrev() else actionNext(); true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (com.glasshole.glassee1.BaseSettings.isNavInverted(this)) actionNext() else actionPrev(); true
            }
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
            // Stock home unavailable — at least dim our UI so the user sees
            // *something* changed.
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
        // Clear FLAG_KEEP_SCREEN_ON first — if the Nav card had the
        // keep-screen-on flag set on the window, sleep silently no-ops.
        // The flag gets re-applied by updateKeepScreenOn() next resume.
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        try {
            if (dpm.isAdminActive(adminComponent)) {
                dpm.lockNow()
                return
            }
        } catch (_: Exception) {}
        BluetoothListenerService.instance?.sleepGlass()
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
