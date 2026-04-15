package com.glasshole.phone.plugin

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import com.glasshole.sdk.PluginConstants

data class DiscoveredPlugin(
    val pluginId: String,
    val name: String,
    val version: Int,
    val glassPackage: String,
    val packageName: String,
    val serviceName: String
)

object PluginDiscovery {

    private const val TAG = "GlassHoleDiscovery"

    fun discoverPlugins(context: Context): List<DiscoveredPlugin> {
        val intent = Intent(PluginConstants.ACTION_PHONE_PLUGIN)
        val resolvedServices: List<ResolveInfo> = context.packageManager
            .queryIntentServices(intent, PackageManager.GET_META_DATA)

        return resolvedServices.mapNotNull { resolveInfo ->
            val serviceInfo = resolveInfo.serviceInfo ?: return@mapNotNull null
            val metaData = serviceInfo.metaData ?: return@mapNotNull null

            val pluginId = metaData.getString(PluginConstants.META_PLUGIN_ID)
            if (pluginId.isNullOrEmpty()) {
                Log.w(TAG, "Plugin ${serviceInfo.name} missing META_PLUGIN_ID")
                return@mapNotNull null
            }

            DiscoveredPlugin(
                pluginId = pluginId,
                name = metaData.getString(PluginConstants.META_PLUGIN_NAME) ?: pluginId,
                version = metaData.getInt(PluginConstants.META_PLUGIN_VERSION, 1),
                glassPackage = metaData.getString(PluginConstants.META_GLASS_PACKAGE) ?: "",
                packageName = serviceInfo.packageName,
                serviceName = serviceInfo.name
            )
        }.also {
            Log.i(TAG, "Discovered ${it.size} plugin(s): ${it.map { p -> p.pluginId }}")
        }
    }
}
