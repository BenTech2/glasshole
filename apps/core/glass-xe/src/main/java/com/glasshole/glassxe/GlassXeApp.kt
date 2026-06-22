package com.glasshole.glassxe

import android.app.Activity
import android.app.Application
import android.content.res.Resources
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log

/**
 * Forces the app's reported display density to mdpi (160 dpi) on XE,
 * which physically reports 240 dpi. Both editions share the same
 * 640×360 px panel — the higher reported density makes XE render
 * every dp/sp value 1.5× larger than EE1/EE2 unless the layouts are
 * hand-scaled to compensate. Older XE layouts WERE hand-scaled to
 * ~⅔ of EE2 to compensate; newer additions (wallpaper section, Wi-Fi
 * card, dev plugin entries) weren't, which is why screens started
 * to look visually different across editions.
 *
 * Patching the shared [DisplayMetrics] object at application start
 * is reliable on KitKat: all activities in this process share the
 * Application's [Resources] instance and pull `density` from the
 * same `displayMetrics` object reference. Subsequent dp/sp lookups
 * read the patched values; previously hand-scaled XE layouts now
 * render visibly smaller (because they were already compensating for
 * a 1.5× factor that's no longer there), so paired-down XE-specific
 * dimensions can be unwound over time in favour of the shared layout.
 *
 * Also patches [Resources.getSystem]'s metrics so framework code that
 * reads from the system resources (e.g. Build.VERSION-gated dialog
 * sizing) sees the same density. The lifecycle callback re-applies
 * on every Activity create so an activity whose resources got
 * re-instantiated (e.g. config change, theme switch) doesn't drift
 * back to 240 dpi.
 */
class GlassXeApp : Application() {

    companion object {
        private const val TAG = "GlassXeApp"
        /** Target density: matches EE1/EE2 (mdpi) so all editions
         *  share one layout dimension space. */
        private const val TARGET_DPI = 160
    }

    override fun onCreate() {
        super.onCreate()
        forceDensity()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                patchMetrics(activity.resources.displayMetrics)
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun forceDensity() {
        try {
            patchMetrics(resources.displayMetrics)
            patchMetrics(Resources.getSystem().displayMetrics)
            Log.i(TAG, "Density forced to $TARGET_DPI dpi (was ${resources.displayMetrics.densityDpi})")
        } catch (e: Exception) {
            Log.e(TAG, "Density override failed", e)
        }
    }

    private fun patchMetrics(m: DisplayMetrics) {
        val scale = TARGET_DPI / 160f
        m.density = scale
        m.densityDpi = TARGET_DPI
        m.scaledDensity = scale
        m.xdpi = TARGET_DPI.toFloat()
        m.ydpi = TARGET_DPI.toFloat()
    }
}
