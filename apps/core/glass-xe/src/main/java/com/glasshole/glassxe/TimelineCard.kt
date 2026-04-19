package com.glasshole.glassxe

import android.content.ContentValues
import android.content.Context
import android.util.Log

/**
 * Reflection wrapper around the Glass Development Kit timeline APIs. See the
 * identical helper in glass-ee1 for the design rationale — we want the module
 * to build without the GDK jar and degrade gracefully if the device doesn't
 * ship with com.google.android.glass.jar.
 */
object TimelineCard {

    private const val TAG = "GlassHoleTimeline"

    @Volatile private var available: Boolean? = null

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
                } catch (_: NoSuchMethodException) { }
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
