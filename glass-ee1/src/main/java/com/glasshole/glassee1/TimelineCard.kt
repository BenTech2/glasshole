package com.glasshole.glassee1

import android.content.ContentValues
import android.content.Context
import android.util.Log

/**
 * Thin reflection wrapper around the Google Glass Development Kit (GDK) timeline
 * APIs. These classes ship inside the Glass XE system image at
 *   /system/framework/com.google.android.glass.jar
 * and are referenced via `<uses-library android:name="com.google.android.glass"
 * android:required="false"/>` in the manifest.
 *
 * We use reflection so the module still builds without the GDK jar on the
 * classpath and silently no-ops on devices that lack the timeline system
 * (e.g. glass clones, emulators, EE2).
 */
object TimelineCard {

    private const val TAG = "GlassHoleTimeline"

    @Volatile private var available: Boolean? = null

    /** Returns true if this device has the GDK timeline APIs we need. */
    fun isAvailable(context: Context): Boolean {
        available?.let { return it }
        val ok = try {
            Class.forName("com.google.android.glass.widget.CardBuilder")
            Class.forName("com.google.android.glass.timeline.TimelineManager")
            true
        } catch (_: Throwable) { false }
        available = ok
        if (!ok) Log.i(TAG, "GDK timeline APIs not present — falling back")
        return ok
    }

    /**
     * Insert a single text card into the Glass timeline. Returns true if the
     * insertion succeeded. `text` is the card body, `footnote` is the small
     * bottom-line (typically the source app name).
     */
    fun insertText(context: Context, text: String, footnote: String?): Boolean {
        if (!isAvailable(context)) return false
        return try {
            val cardBuilderClass = Class.forName("com.google.android.glass.widget.CardBuilder")
            val layoutClass = Class.forName("com.google.android.glass.widget.CardBuilder\$Layout")
            val textLayout = layoutClass.getField("TEXT").get(null)

            val builder = cardBuilderClass
                .getConstructor(Context::class.java, layoutClass)
                .newInstance(context, textLayout)

            cardBuilderClass.getMethod("setText", CharSequence::class.java)
                .invoke(builder, text)

            if (!footnote.isNullOrEmpty()) {
                try {
                    cardBuilderClass.getMethod("setFootnote", CharSequence::class.java)
                        .invoke(builder, footnote)
                } catch (_: NoSuchMethodException) { /* older GDK: skip */ }
            }

            val values = cardBuilderClass.getMethod("getContentValues").invoke(builder) as ContentValues

            val tmClass = Class.forName("com.google.android.glass.timeline.TimelineManager")
            val tm = tmClass.getMethod("from", Context::class.java).invoke(null, context)
            tmClass.getMethod("insert", ContentValues::class.java).invoke(tm, values)
            Log.i(TAG, "Timeline card inserted")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Timeline insert failed: ${e.message}")
            false
        }
    }
}
