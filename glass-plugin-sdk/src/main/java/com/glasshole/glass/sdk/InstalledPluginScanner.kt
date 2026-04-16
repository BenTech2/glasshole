package com.glasshole.glass.sdk

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import java.io.File

/**
 * Finds user-launchable GlassHole plugins installed on the glass.
 *
 * Plugins are identified by the [GlassPluginConstants.ACTION_GLASS_PLUGIN]
 * service intent filter + the [GlassPluginConstants.META_PLUGIN_ID] metadata.
 * We further filter to plugins that declare a launcher activity — that
 * drops the "core" plugins (device controls, stream router) which only
 * run in the background as services and have no UI to launch.
 */
object InstalledPluginScanner {

    /**
     * Plugin IDs known to only target Glass Enterprise Edition 2 (minSdk 27).
     * Used to label each entry in the launcher details dialog.
     */
    private val EE2_ONLY = setOf("camera2", "gallery2")

    /**
     * Plugin IDs that back core GlassHole functionality rather than being
     * user-launchable apps. They may declare a launcher activity (e.g. the
     * photo-sync permission tile) but shouldn't show up in the plugin
     * carousel on the main screen.
     */
    private val CORE_PLUGIN_IDS = setOf("device", "stream", "gallery")

    data class InstalledPlugin(
        val pluginId: String,
        val packageName: String,
        val label: String,
        val versionName: String,
        val icon: Drawable?,
        val sizeBytes: Long,
        val launchIntent: Intent,
        val variant: String,
    )

    fun scan(context: Context): List<InstalledPlugin> {
        val pm = context.packageManager
        val query = Intent(GlassPluginConstants.ACTION_GLASS_PLUGIN)
        val services = pm.queryIntentServices(query, PackageManager.GET_META_DATA)

        val out = mutableListOf<InstalledPlugin>()
        for (info in services) {
            val serviceInfo = info.serviceInfo ?: continue
            val pkg = serviceInfo.packageName
            val pluginId = serviceInfo.metaData
                ?.getString(GlassPluginConstants.META_PLUGIN_ID) ?: continue

            if (pluginId in CORE_PLUGIN_IDS) continue

            val launchIntent = pm.getLaunchIntentForPackage(pkg) ?: continue

            val appInfo = try { pm.getApplicationInfo(pkg, 0) } catch (_: Exception) { continue }
            val packageInfo = try { pm.getPackageInfo(pkg, 0) } catch (_: Exception) { continue }

            val label = try { pm.getApplicationLabel(appInfo).toString() } catch (_: Exception) { pkg }
            val icon = try { pm.getApplicationIcon(appInfo) } catch (_: Exception) { null }
            val size = try { File(appInfo.sourceDir).length() } catch (_: Exception) { 0L }

            out.add(
                InstalledPlugin(
                    pluginId = pluginId,
                    packageName = pkg,
                    label = label,
                    versionName = packageInfo.versionName ?: "",
                    icon = icon,
                    sizeBytes = size,
                    launchIntent = launchIntent,
                    variant = if (pluginId in EE2_ONLY) "EE2 only" else "Universal",
                )
            )
        }
        return out.sortedBy { it.label.lowercase() }
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "?"
        val mb = bytes / 1024.0 / 1024.0
        return if (mb < 1.0) "${(bytes / 1024)} KB" else String.format("%.1f MB", mb)
    }
}
