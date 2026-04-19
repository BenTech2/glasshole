package com.glasshole.glass.sdk

object GlassPluginConstants {
    // Intent action for discovering Glass-side plugins
    const val ACTION_GLASS_PLUGIN = "com.glasshole.plugin.GLASS_PLUGIN"

    // Plugin metadata key
    const val META_PLUGIN_ID = "com.glasshole.plugin.ID"

    // Broadcast actions for EE1/XE (API 19) plugin communication
    const val ACTION_MESSAGE_FROM_PHONE = "com.glasshole.plugin.MESSAGE_FROM_PHONE"
    const val ACTION_MESSAGE_TO_PHONE = "com.glasshole.glass.MESSAGE_TO_PHONE"

    // Broadcast extras
    const val EXTRA_PLUGIN_ID = "plugin_id"
    const val EXTRA_MESSAGE_TYPE = "message_type"
    const val EXTRA_PAYLOAD = "payload"
    const val EXTRA_BINARY_DATA = "binary_data"
}
