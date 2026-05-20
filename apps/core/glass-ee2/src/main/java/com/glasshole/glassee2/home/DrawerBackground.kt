package com.glasshole.glassee2.home

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.ImageView
import com.glasshole.glassee2.BaseSettings

/**
 * Drives the optional Home-wallpaper layer behind the Settings and App
 * cover-flow drawers. Both activities have an identical
 * `backgroundImage` + `backgroundFade` layered above their root pager
 * (added in activity_settings_drawer.xml / activity_app_drawer.xml);
 * this helper:
 *
 *   - reads the per-surface toggle from BaseSettings (so the user can
 *     have Home wallpaper on but the drawers off, or vice versa),
 *   - reuses the existing fade alpha from BaseSettings.KEY_BACKGROUND_FADE,
 *   - sub-samples the most-recently-modified file out of
 *     /sdcard/GlassHole/backgrounds/ on a worker thread,
 *   - registers a `WALLPAPER_CHANGED` broadcast receiver so a phone
 *     upload while the drawer's open re-renders without backing out.
 *
 * Lifecycle: call attach() in the activity's onResume and detach() in
 * onPause. The receiver registration follows the same window.
 */
class DrawerBackground(
    private val activity: Activity,
    private val backgroundImage: ImageView,
    private val backgroundFade: View,
    /** Which per-surface toggle to honour — KEY_WALLPAPER_ON_SETTINGS
     *  or KEY_WALLPAPER_ON_APP_DRAWER. */
    private val enabledPrefKey: String,
) {
    private val wallpaperReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            apply()
        }
    }

    fun attach() {
        val filter = IntentFilter("com.glasshole.glass.WALLPAPER_CHANGED")
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(wallpaperReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            activity.registerReceiver(wallpaperReceiver, filter)
        }
        apply()
    }

    fun detach() {
        try { activity.unregisterReceiver(wallpaperReceiver) } catch (_: Exception) {}
    }

    private fun apply() {
        val prefs = activity.getSharedPreferences(BaseSettings.PREFS, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(enabledPrefKey, false)
        if (!enabled) {
            backgroundImage.setImageBitmap(null)
            backgroundImage.visibility = View.GONE
            backgroundFade.visibility = View.GONE
            return
        }
        applyFade(prefs.getInt(BaseSettings.KEY_BACKGROUND_FADE, 0))
        loadAsync()
    }

    private fun applyFade(rawAlpha: Int) {
        val alpha = rawAlpha.coerceIn(0, 255)
        if (alpha == 0) {
            backgroundFade.visibility = View.GONE
        } else {
            backgroundFade.visibility = View.VISIBLE
            backgroundFade.alpha = alpha / 255f
        }
    }

    private fun loadAsync() {
        val dm = activity.resources.displayMetrics
        val targetW = dm.widthPixels
        val targetH = dm.heightPixels
        Thread {
            val bmp = try {
                decodeLatest(targetW, targetH)
            } catch (t: Throwable) {
                Log.w(TAG, "Wallpaper decode threw: ${t.message}")
                null
            }
            activity.runOnUiThread {
                if (bmp != null) {
                    backgroundImage.setImageBitmap(bmp)
                    backgroundImage.visibility = View.VISIBLE
                } else {
                    backgroundImage.setImageBitmap(null)
                    backgroundImage.visibility = View.GONE
                }
            }
        }.apply { isDaemon = true; name = "DrawerBgLoader" }.start()
    }

    private fun decodeLatest(targetW: Int, targetH: Int): Bitmap? {
        val dir = java.io.File("/sdcard/GlassHole/backgrounds")
        if (!dir.isDirectory) return null
        val candidate = dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            ?.maxByOrNull { it.lastModified() }
            ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(candidate.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > targetW * 2 ||
            bounds.outHeight / sample > targetH * 2) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(candidate.absolutePath, opts)
    }

    companion object { private const val TAG = "DrawerBackground" }
}
