package com.glasshole.glass.sdk

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Scans the device for installed glass plugins and extracts the
 * dynamic-plugin metadata (name / description / schema-resource) that
 * the phone app consumes via `PLUGIN_LIST`.
 *
 * Returns a concise per-plugin record. Plugins that haven't migrated to
 * the dynamic system yet still appear here (with empty name/description)
 * — they just won't render a settings screen on the phone.
 */
object PluginDirectoryScanner {

    /** Plugin IDs the base app owns itself via HomeActivity — not real plugins. */
    private val HOME_OWNED = setOf("media", "nav")

    /** Core IDs the base app manages (device, gallery, etc.) — hidden from the user directory. */
    private val CORE_HIDDEN = setOf("device", "stream", "gallery")

    data class Entry(
        val id: String,
        val packageName: String,
        val name: String,
        val description: String,
        val version: String,
        val hasSchema: Boolean
    )

    fun scan(context: Context): List<Entry> {
        val pm = context.packageManager
        val query = Intent(GlassPluginConstants.ACTION_GLASS_PLUGIN)
        val services = pm.queryIntentServices(query, PackageManager.GET_META_DATA)
        val out = mutableListOf<Entry>()
        for (info in services) {
            val si = info.serviceInfo ?: continue
            val md = si.metaData ?: continue
            val id = md.getString(GlassPluginConstants.META_PLUGIN_ID) ?: continue
            if (id in HOME_OWNED) continue
            if (id in CORE_HIDDEN) continue

            val pkg = si.packageName
            val appInfo = try { pm.getApplicationInfo(pkg, 0) } catch (_: Exception) { continue }
            val packageInfo = try { pm.getPackageInfo(pkg, 0) } catch (_: Exception) { null }

            val fallbackLabel = try { pm.getApplicationLabel(appInfo).toString() } catch (_: Exception) { pkg }
            val name = md.getString(GlassPluginConstants.META_PLUGIN_NAME) ?: fallbackLabel
            val description = md.getString(GlassPluginConstants.META_PLUGIN_DESCRIPTION) ?: ""
            val version = packageInfo?.versionName ?: ""
            val schemaRes = md.getInt(GlassPluginConstants.META_PLUGIN_SCHEMA, 0)

            out.add(
                Entry(
                    id = id,
                    packageName = pkg,
                    name = name,
                    description = description,
                    version = version,
                    hasSchema = schemaRes != 0
                )
            )
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /** Serialize the scan result for PLUGIN_LIST over BT. */
    fun toJson(entries: List<Entry>): String {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("pkg", e.packageName)
                    .put("name", e.name)
                    .put("description", e.description)
                    .put("version", e.version)
                    .put("has_schema", e.hasSchema)
            )
        }
        return arr.toString()
    }
}
