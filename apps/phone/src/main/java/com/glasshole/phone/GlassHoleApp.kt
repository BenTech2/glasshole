package com.glasshole.phone

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors

/**
 * Forces dark mode app-wide (light-mode rendering had styling regressions
 * we don't intend to fix yet) and applies wallpaper-derived Material You
 * colors on Android 12+. The values-night/ palette stays in effect either
 * way; on Android 11 and below the seed colors from values/colors.xml
 * (dark fallbacks since dark mode is forced) take over.
 */
class GlassHoleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
