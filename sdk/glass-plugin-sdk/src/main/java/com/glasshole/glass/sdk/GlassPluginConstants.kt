package com.glasshole.glass.sdk

object GlassPluginConstants {
    // Intent action for discovering Glass-side plugins
    const val ACTION_GLASS_PLUGIN = "com.glasshole.plugin.GLASS_PLUGIN"

    // Plugin metadata keys — only ID is required; NAME / DESCRIPTION /
    // SCHEMA surface the plugin in the phone app's dynamic plugin directory.
    const val META_PLUGIN_ID = "com.glasshole.plugin.ID"
    const val META_PLUGIN_NAME = "com.glasshole.plugin.NAME"
    const val META_PLUGIN_DESCRIPTION = "com.glasshole.plugin.DESCRIPTION"
    const val META_PLUGIN_SCHEMA = "com.glasshole.plugin.SCHEMA" // res id

    // Dynamic-plugin message types sent from phone → glass and back via
    // the existing PLUGIN:<id>:<type>:<payload> envelope.
    const val MSG_SCHEMA_REQ = "SCHEMA_REQ"       // phone → plugin
    const val MSG_SCHEMA_RESP = "SCHEMA_RESP"     // plugin → phone
    const val MSG_CONFIG_READ = "CONFIG_READ"     // phone → plugin
    const val MSG_CONFIG = "CONFIG"               // plugin → phone
    const val MSG_CONFIG_WRITE = "CONFIG_WRITE"   // phone → plugin

    // Broadcast actions for EE1/XE (API 19) plugin communication
    const val ACTION_MESSAGE_FROM_PHONE = "com.glasshole.plugin.MESSAGE_FROM_PHONE"
    const val ACTION_MESSAGE_TO_PHONE = "com.glasshole.glass.MESSAGE_TO_PHONE"

    // Broadcast extras
    const val EXTRA_PLUGIN_ID = "plugin_id"
    const val EXTRA_MESSAGE_TYPE = "message_type"
    const val EXTRA_PAYLOAD = "payload"
    const val EXTRA_BINARY_DATA = "binary_data"
}
