package com.glasshole.phone.plugindir

import org.json.JSONArray
import org.json.JSONObject

/**
 * Phone-side in-memory cache of dynamic plugins discovered on the glass.
 *
 * Populated by `PLUGIN_LIST` pushes from the glass base app on connect,
 * and by replies to `PLUGIN:<id>:SCHEMA_REQ` / `CONFIG_READ`. The
 * SettingsActivity consumes this data; no persistence on the phone —
 * glass is the source of truth.
 */
object PluginDirectory {

    data class Entry(
        val id: String,
        val packageName: String,
        val name: String,
        val description: String,
        val version: String,
        val hasSchema: Boolean
    )

    @Volatile private var entries: List<Entry> = emptyList()
    private val schemas = mutableMapOf<String, JSONObject>()
    private val configs = mutableMapOf<String, JSONObject>()
    private val listeners = mutableListOf<() -> Unit>()

    /** Replace the directory with a freshly-decoded PLUGIN_LIST payload. */
    @Synchronized
    fun updateList(listJson: String) {
        val arr = try { JSONArray(listJson) } catch (_: Exception) { return }
        val newEntries = mutableListOf<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            newEntries.add(
                Entry(
                    id = o.optString("id"),
                    packageName = o.optString("pkg"),
                    name = o.optString("name"),
                    description = o.optString("description"),
                    version = o.optString("version"),
                    hasSchema = o.optBoolean("has_schema")
                )
            )
        }
        entries = newEntries
        // Drop stale schema / config caches for plugins no longer present.
        val ids = newEntries.map { it.id }.toSet()
        schemas.keys.retainAll(ids)
        configs.keys.retainAll(ids)
        fire()
    }

    @Synchronized
    fun updateSchema(id: String, schemaJson: String) {
        val obj = try { JSONObject(schemaJson) } catch (_: Exception) { return }
        schemas[id] = obj
        fire()
    }

    @Synchronized
    fun updateConfig(id: String, configJson: String) {
        val obj = try { JSONObject(configJson) } catch (_: Exception) { return }
        configs[id] = obj
        fire()
    }

    @Synchronized
    fun all(): List<Entry> = entries

    @Synchronized
    fun entry(id: String): Entry? = entries.firstOrNull { it.id == id }

    @Synchronized
    fun schemaFor(id: String): JSONObject? = schemas[id]

    @Synchronized
    fun configFor(id: String): JSONObject? = configs[id]

    @Synchronized
    fun addListener(listener: () -> Unit) { listeners.add(listener) }

    @Synchronized
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    private fun fire() {
        val snapshot = listeners.toList()
        for (l in snapshot) try { l() } catch (_: Exception) {}
    }
}
