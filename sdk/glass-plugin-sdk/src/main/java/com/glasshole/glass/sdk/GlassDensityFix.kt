package com.glasshole.glass.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log

/**
 * Patches the app's reported display density on XE so plugin UIs render
 * at the same visual size as on EE1/EE2.
 *
 * XE physically reports `densityDpi=240` (hdpi) while EE1 + EE2 report
 * `160` (mdpi); all three have an identical 640×360 px panel. Without
 * a fix, every dp/sp value in a shared layout renders 1.5× larger on
 * XE — which is exactly what the launcher's `GlassXeApp` already
 * compensates for in core. Plugin APKs are separate processes with
 * their own `Application`, so they don't inherit that fix.
 *
 * This helper lives in `glass-plugin-sdk` (the library every plugin
 * depends on) and is invoked by [GlassDensityFixProvider] — a tiny
 * ContentProvider whose declaration is added to the SDK's manifest so
 * manifest-merging propagates it into every consumer plugin without
 * each plugin needing its own Application subclass.
 *
 * ContentProvider.onCreate runs before Application.onCreate (and before
 * any Activity is instantiated), so the patch is in place when the
 * first `setContentView()` inflates a layout — no first-frame oversize
 * glitch.
 */
object GlassDensityFix {

    private const val TAG = "GlassDensityFix"
    /** Target density: matches EE1/EE2 (mdpi) so all editions render
     *  layouts at identical visual size. */
    private const val TARGET_DPI = 160
    /** XE's actual reported density. We only patch when the device
     *  reports this exact value, so EE1/EE2 (and any future Glass at
     *  a different DPI) pass through unchanged. */
    private const val XE_DPI = 240

    /** Patch the app's shared DisplayMetrics if (and only if) we're
     *  running on XE. Idempotent — safe to call repeatedly. Also
     *  registers an [Application.ActivityLifecycleCallbacks] so any
     *  activity whose resources got re-instantiated (config change,
     *  theme switch) gets the patch re-applied. */
    fun applyIfNeeded(context: Context) {
        try {
            val appMetrics = context.applicationContext.resources.displayMetrics
            val sysMetrics = Resources.getSystem().displayMetrics
            if (appMetrics.densityDpi != XE_DPI && sysMetrics.densityDpi != XE_DPI) return
            patch(appMetrics)
            patch(sysMetrics)
            (context.applicationContext as? Application)?.let { app ->
                if (registered) return
                registered = true
                app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                        patch(activity.resources.displayMetrics)
                    }
                    override fun onActivityStarted(activity: Activity) {}
                    override fun onActivityResumed(activity: Activity) {}
                    override fun onActivityPaused(activity: Activity) {}
                    override fun onActivityStopped(activity: Activity) {}
                    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                    override fun onActivityDestroyed(activity: Activity) {}
                })
            }
            Log.i(TAG, "Density patched to $TARGET_DPI dpi (was $XE_DPI)")
        } catch (e: Exception) {
            Log.w(TAG, "Density patch failed: ${e.message}")
        }
    }

    @Volatile private var registered = false

    private fun patch(m: DisplayMetrics) {
        val scale = TARGET_DPI / 160f
        m.density = scale
        m.densityDpi = TARGET_DPI
        m.scaledDensity = scale
        m.xdpi = TARGET_DPI.toFloat()
        m.ydpi = TARGET_DPI.toFloat()
    }
}
