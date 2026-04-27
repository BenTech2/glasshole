package com.glasshole.phone.plugindir.worker

import android.content.Context
import android.util.Log
import com.glasshole.phone.plugindir.PluginDirectory
import org.json.JSONArray
import org.json.JSONObject

/**
 * Coordinates phone-side workers for every dynamic plugin. Listens to
 * [PluginDirectory] updates, materialises each plugin's `workers[]`
 * spec into concrete [WorkerPrimitive] instances, keeps them running
 * while their `enabled_when` condition holds, and forwards incoming
 * glass messages to the appropriate primitives.
 *
 * The manager is intentionally stateless regarding plugin settings —
 * glass owns the config, phone only caches it via PluginDirectory.
 * When a plugin's config changes (a CONFIG_RESP arrives), the manager
 * tears down and restarts the affected workers with fresh params.
 */
class WorkerManager(
    private val appContext: Context,
    private val send: (pluginId: String, type: String, payload: String) -> Boolean
) {
    companion object {
        private const val TAG = "WorkerManager"
        // Android's ICU regex engine requires both braces escaped — a bare
        // `}` outside a quantifier is a syntax error on modern API levels.
        private val paramSubRegex = Regex("""\$\{([a-zA-Z0-9_]+)\}""")
    }

    /** Per-plugin list of currently-running primitives. */
    private val running = mutableMapOf<String, List<WorkerPrimitive>>()

    /** Remembered signature of params per plugin so we don't needlessly restart. */
    private val paramSignatures = mutableMapOf<String, String>()

    private val directoryListener: () -> Unit = { reconcileAll() }

    fun start() {
        PluginDirectory.addListener(directoryListener)
        reconcileAll()
    }

    fun stop() {
        PluginDirectory.removeListener(directoryListener)
        for ((id, ps) in running) {
            ps.forEach { safeStop(it) }
            Log.d(TAG, "stopped all workers for $id")
        }
        running.clear()
        paramSignatures.clear()
    }

    /**
     * Broadcast the BT connection state to every running primitive.
     * Called from BridgeService on connect / disconnect.
     */
    fun onConnectionChanged(connected: Boolean) {
        for ((id, ps) in running) {
            for (p in ps) {
                try { p.onConnectionChanged(connected) } catch (e: Exception) {
                    Log.w(TAG, "[$id] onConnectionChanged failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Forward a PLUGIN:<id>:<type>:<payload> message to all active
     * primitives belonging to that plugin. Called from BridgeService.
     */
    fun deliverMessage(pluginId: String, type: String, payload: String) {
        val active = running[pluginId] ?: return
        for (p in active) {
            try { p.onMessage(type, payload) } catch (e: Exception) {
                Log.w(TAG, "[$pluginId] onMessage($type) failed: ${e.message}")
            }
        }
    }

    /**
     * Walk every plugin in the directory and ensure its workers are
     * running (or not) per its current schema + config. Called on
     * every directory update.
     */
    private fun reconcileAll() {
        val present = PluginDirectory.all().map { it.id }.toSet()

        // Tear down workers for plugins that have disappeared.
        val gone = running.keys - present
        for (id in gone) {
            running[id]?.forEach { safeStop(it) }
            running.remove(id)
            paramSignatures.remove(id)
            Log.d(TAG, "plugin $id removed — workers stopped")
        }

        // Start / restart / teardown each plugin's workers based on its
        // current schema + config.
        for (entry in PluginDirectory.all()) {
            reconcilePlugin(entry.id)
        }
    }

    private fun reconcilePlugin(pluginId: String) {
        val schema = PluginDirectory.schemaFor(pluginId)
        val config = PluginDirectory.configFor(pluginId)
        // Need both schema AND config before we can build workers —
        // the schema names the primitives, the config provides the params.
        if (schema == null || config == null) return

        val workerSpecs = schema.optJSONArray("workers") ?: JSONArray()
        val activeSpecs = mutableListOf<JSONObject>()
        for (i in 0 until workerSpecs.length()) {
            val spec = workerSpecs.optJSONObject(i) ?: continue
            if (!conditionHolds(spec.optJSONObject("enabled_when"), config)) continue
            activeSpecs.add(spec)
        }

        // Signature = which primitives + their substituted params. Any
        // change triggers a full restart (simpler than diffing). Stable
        // signatures avoid needless restarts when a setting not used by
        // this worker changes.
        val substituted = activeSpecs.map { spec ->
            val primitive = spec.optString("primitive")
            val params = substituteParams(spec.optJSONObject("params") ?: JSONObject(), config)
            Pair(primitive, params)
        }
        val signature = buildString {
            for ((prim, params) in substituted) {
                append(prim); append("|"); append(params.toString()); append(";")
            }
        }
        if (signature == paramSignatures[pluginId]) return // nothing changed

        // Stop old workers, start new ones.
        running[pluginId]?.forEach { safeStop(it) }
        val fresh = mutableListOf<WorkerPrimitive>()
        for ((primitiveName, params) in substituted) {
            val primitive = WorkerRegistry.create(primitiveName)
            if (primitive == null) {
                Log.w(TAG, "[$pluginId] unknown primitive '$primitiveName' — worker skipped")
                continue
            }
            try {
                primitive.start(appContext, params) { type, payload ->
                    send(pluginId, type, payload)
                }
                fresh.add(primitive)
                Log.i(TAG, "[$pluginId] started primitive '$primitiveName'")
            } catch (e: Exception) {
                Log.w(TAG, "[$pluginId] start('$primitiveName') failed: ${e.message}")
            }
        }
        running[pluginId] = fresh
        paramSignatures[pluginId] = signature
    }

    private fun safeStop(p: WorkerPrimitive) {
        try { p.stop() } catch (e: Exception) { Log.w(TAG, "stop() failed: ${e.message}") }
    }

    // ───────── Schema helpers ─────────

    /**
     * Evaluates a conditional node against the plugin's current config.
     * Supported shapes:
     *   null            → always true
     *   {key,eq}        → single equality check
     *   {all:[ ... ]}   → AND of nested conditions
     * We can grow to {any:[...]} / {not:...} as plugins need them.
     */
    private fun conditionHolds(cond: JSONObject?, config: JSONObject): Boolean {
        if (cond == null) return true
        cond.optJSONArray("all")?.let { arr ->
            for (i in 0 until arr.length()) {
                val sub = arr.optJSONObject(i) ?: continue
                if (!conditionHolds(sub, config)) return false
            }
            return true
        }
        val key = cond.optString("key")
        val expected = cond.opt("eq")
        val actual = config.opt(key)
        if (actual == null && expected == null) return true
        if (actual == null || expected == null) return false
        return actual.toString() == expected.toString()
    }

    /**
     * Walk a params JSONObject and substitute every string value with
     * the config-backed value for any `${key}` references it contains.
     * Works recursively on nested objects and arrays so body templates
     * like `{"chat_id": "${chat_id}", "text": "${message}"}` resolve.
     *
     * Returns a new object — the original is not mutated.
     */
    private fun substituteParams(params: JSONObject, config: JSONObject): JSONObject {
        return substituteObject(params, config) as JSONObject
    }

    private fun substituteObject(obj: JSONObject, config: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out.put(k, substituteAny(obj.opt(k), config))
        }
        return out
    }

    private fun substituteAny(value: Any?, config: JSONObject): Any? = when (value) {
        is String -> substituteString(value, config)
        is JSONObject -> substituteObject(value, config)
        is JSONArray -> substituteArray(value, config)
        else -> value
    }

    private fun substituteArray(arr: JSONArray, config: JSONObject): JSONArray {
        val out = JSONArray()
        for (i in 0 until arr.length()) out.put(substituteAny(arr.opt(i), config))
        return out
    }

    private fun substituteString(s: String, config: JSONObject): Any {
        // Special-case: a string that's JUST "${key}" with nothing else
        // resolves to the raw typed value (int stays int, bool stays bool).
        val exact = Regex("""^\$\{([a-zA-Z0-9_]+)\}$""").matchEntire(s)
        if (exact != null) {
            val k = exact.groupValues[1]
            if (config.has(k)) return config.opt(k) ?: ""
            return ""
        }
        // Otherwise interpolate inline.
        return paramSubRegex.replace(s) { match ->
            val k = match.groupValues[1]
            config.opt(k)?.toString() ?: ""
        }
    }
}
