package com.glasshole.phone

import android.util.Log

/**
 * Shared process-wide log sink. Any plugin / service / activity can call
 * `AppLog.log("tag", "msg")` and it will go to logcat AND be forwarded to
 * whichever UI has attached itself via `AppLog.sink`. MainActivity wires
 * its log panel up to this so every component can surface human-readable
 * debug output in one place.
 */
object AppLog {

    /** Set by MainActivity to route log lines to the on-screen panel. */
    @Volatile
    var sink: ((String) -> Unit)? = null

    fun log(tag: String, msg: String) {
        Log.i(tag, msg)
        sink?.invoke("[$tag] $msg")
    }

    fun warn(tag: String, msg: String) {
        Log.w(tag, msg)
        sink?.invoke("[$tag] ⚠ $msg")
    }

    fun error(tag: String, msg: String, t: Throwable? = null) {
        Log.e(tag, msg, t)
        sink?.invoke("[$tag] ✗ $msg${if (t != null) " — ${t.message}" else ""}")
    }
}
