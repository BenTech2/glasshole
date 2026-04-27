package com.glasshole.phone.plugindir.worker

import android.content.Context
import org.json.JSONObject

/**
 * Phone-side primitive that does the actual work for a dynamic plugin.
 * Plugins reference primitives by name in their `workers[]` schema
 * array; the WorkerManager instantiates and drives them on the plugin's
 * behalf, without the phone app needing any plugin-specific code.
 *
 * Lifecycle contract:
 *   1. `start(ctx, params, emit)` — called once when the primitive
 *      becomes active (enabled_when is true, plugin is present).
 *   2. `onMessage(type, payload)` — called for every PLUGIN:<id>:...
 *      message that arrives from the glass plugin while running.
 *   3. `stop()` — called when the primitive becomes inactive
 *      (enabled_when flips, plugin uninstalls, connection drops).
 *
 * Implementations must be reusable — a primitive may be stopped and
 * later started again after a config change. Hold state only for the
 * duration of a single start/stop cycle.
 */
interface WorkerPrimitive {
    /**
     * Begin running with the given parameters.
     *
     * @param context application context — safe to retain for the lifetime
     *                of this primitive instance (until stop()).
     * @param params  JSON object built from the plugin's worker spec with
     *                ${setting_key} references already substituted.
     * @param emit    callback that sends a PLUGIN:<pluginId>:<type>:<payload>
     *                back to the glass plugin. Safe to call from any
     *                thread.
     */
    fun start(context: Context, params: JSONObject, emit: (type: String, payload: String) -> Unit)

    /**
     * Invoked for each message received from the glass plugin while this
     * primitive is running. Most trigger-style primitives (http-post)
     * filter by `type` against a param like "trigger".
     */
    fun onMessage(type: String, payload: String) {}

    /**
     * Invoked when the BT link to the glass flips. Primitives that hold
     * external network sessions (chat clients, streams) should drop them
     * on `connected == false` so we don't burn battery / API quota while
     * nothing's listening.
     */
    fun onConnectionChanged(connected: Boolean) {}

    /** Release resources (sockets, handlers, threads). */
    fun stop()
}
